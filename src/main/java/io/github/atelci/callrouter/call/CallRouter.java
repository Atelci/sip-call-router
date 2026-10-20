package io.github.atelci.callrouter.call;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import io.github.atelci.callrouter.agent.AgentService;
import io.github.atelci.callrouter.agent.AgentService.Pairing;
import io.github.atelci.callrouter.agent.AgentStatus;
import io.github.atelci.callrouter.config.RouterProperties;
import io.github.atelci.callrouter.redis.RedisKeys;

/**
 * The routing brain, a tiny ACD (automatic call distributor) with skills-based routing:
 * <ul>
 *   <li>a call asks for a skill (en, billing, ...) or is general (any agent will do)</li>
 *   <li>new call + free agent with the skill -> connect to the longest-idle such agent</li>
 *   <li>new call + no such agent             -> wait in that skill's FIFO queue (up to maxQueueSize)</li>
 *   <li>queue full                           -> reject</li>
 *   <li>agent frees up                       -> the longest-waiting call it can take gets it</li>
 * </ul>
 * All state is in Redis. Claiming an agent (and, when serving a queue, popping the call) is one
 * atomic Lua script, so that step is safe with several instances. Methods are still synchronized
 * because the new-call path (claim agent, else check size and enqueue) is several Redis commands;
 * see the README design notes.
 */
@Service
public class CallRouter {

    private static final Logger log = LoggerFactory.getLogger(CallRouter.class);
    private static final Duration FINISHED_CALL_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final AgentService agents;
    private final CallEventPublisher events;
    private final RouterProperties props;

    private volatile CallSignaling signaling;

    public CallRouter(StringRedisTemplate redis, AgentService agents,
                      CallEventPublisher events, RouterProperties props) {
        this.redis = redis;
        this.agents = agents;
        this.events = events;
        this.props = props;
    }

    /** Called by the signaling layer once it is up (avoids a constructor cycle). */
    public void setSignaling(CallSignaling signaling) {
        this.signaling = signaling;
    }

    /** A general call: any agent can take it. */
    public RoutingDecision onIncomingCall(String callId, String from, String to) {
        return onIncomingCall(callId, from, to, null);
    }

    /**
     * @param requestedSkill what the caller dialed (e.g. "billing"); if it is null or no agent
     *                       has that skill, the call is general
     */
    public synchronized RoutingDecision onIncomingCall(String callId, String from, String to, String requestedSkill) {
        String skill = requestedSkill != null && agents.knownSkills().contains(requestedSkill) ? requestedSkill : null;
        Map<String, String> call = new HashMap<>(Map.of(
                "from", from,
                "to", to,
                "state", CallState.QUEUED.name(),
                "createdAt", String.valueOf(System.currentTimeMillis())));
        if (skill != null) {
            call.put("skill", skill);
        }
        redis.opsForHash().putAll(RedisKeys.call(callId), call);
        incrementStat("total");
        events.publish(CallEvent.of("CALL_RECEIVED", callId, null, from));

        Optional<String> agent = agents.claimForCall(callId, skill);
        if (agent.isPresent()) {
            return connect(callId, agent.get())
                    ? new RoutingDecision.Connected(agent.get())
                    : new RoutingDecision.Rejected();
        }

        String queue = RedisKeys.callQueue(skill);
        Long queueSize = redis.opsForList().size(queue);
        if (queueSize != null && queueSize >= props.maxQueueSize()) {
            finish(callId, CallState.REJECTED, "rejected");
            log.info("Call {} from {} rejected: {} full ({})", callId, from, queue, queueSize);
            return new RoutingDecision.Rejected();
        }

        Long position = redis.opsForList().rightPush(queue, callId);
        incrementStat("queued");
        events.publish(CallEvent.of("CALL_QUEUED", callId, null, from));
        log.info("Call {} from {} queued in {} at position {}", callId, from, queue, position);
        return new RoutingDecision.Queued(position == null ? 0 : position);
    }

    /** Caller or agent hung up (SIP BYE). Frees the agent and serves the next queued call. */
    public synchronized void onCallEnded(String callId) {
        Map<Object, Object> call = redis.opsForHash().entries(RedisKeys.call(callId));
        if (!CallState.CONNECTED.name().equals(call.get("state"))) {
            log.debug("Ignoring end of call {} in state {}", callId, call.get("state"));
            return;
        }
        redis.opsForSet().remove(RedisKeys.CALLS_ACTIVE, callId);
        finish(callId, CallState.ENDED, "ended");

        String agentId = (String) call.get("agentId");
        if (agentId != null && isBusy(agentId)) {
            agents.markAvailable(agentId);
        }
        log.info("Call {} ended, agent {} released", callId, agentId);
        drainQueue();
    }

    /** Caller gave up while waiting (SIP CANCEL). */
    public synchronized void onCallAbandoned(String callId) {
        Object skill = redis.opsForHash().get(RedisKeys.call(callId), "skill");
        String queue = RedisKeys.callQueue(skill == null ? null : skill.toString());
        Long removed = redis.opsForList().remove(queue, 1, callId);
        if (removed != null && removed > 0) {
            finish(callId, CallState.ABANDONED, "abandoned");
            log.info("Call {} abandoned in {}", callId, queue);
        }
    }

    /**
     * Pairs queued calls with free agents until no more pairs can be made. Queues are tried in
     * order of how long their first caller has waited, so a freed agent with several skills takes
     * the longest-waiting call it can handle. Each pairing is one Lua script (claim-agent.lua), so
     * two router instances draining at once can never split a pair or hand out an agent twice.
     */
    public synchronized void drainQueue() {
        boolean paired = true;
        while (paired) {
            paired = false;
            for (String skill : queuesOldestFirst()) {
                Optional<Pairing> pair = agents.claimForQueue(skill);
                if (pair.isPresent()) {
                    connect(pair.get().callId(), pair.get().agentId());
                    paired = true;
                    break;   // waiting times changed: sort the queues again
                }
            }
        }
    }

    /** Non-empty queues (null = general), the one whose first call has waited longest first. */
    private List<String> queuesOldestFirst() {
        List<String> skills = new ArrayList<>();
        skills.add(null);
        skills.addAll(agents.knownSkills());

        Map<String, Long> headCreatedAt = new HashMap<>();   // HashMap allows the null (general) key
        for (String skill : skills) {
            String head = redis.opsForList().index(RedisKeys.callQueue(skill), 0);
            if (head != null) {
                Object createdAt = redis.opsForHash().get(RedisKeys.call(head), "createdAt");
                headCreatedAt.put(skill, createdAt == null ? 0L : Long.parseLong(createdAt.toString()));
            }
        }
        return headCreatedAt.keySet().stream()
                .sorted(Comparator.comparing(headCreatedAt::get))
                .toList();
    }

    private boolean connect(String callId, String agentId) {
        CallSignaling s = signaling;
        if (s == null || !s.answer(callId, agentId)) {
            log.warn("Call {} could not be answered, releasing agent {}", callId, agentId);
            agents.markAvailable(agentId);
            finish(callId, CallState.ABANDONED, "abandoned");
            return false;
        }

        long now = System.currentTimeMillis();
        Object createdAt = redis.opsForHash().get(RedisKeys.call(callId), "createdAt");
        long waitMs = createdAt == null ? 0 : now - Long.parseLong(createdAt.toString());

        redis.opsForHash().putAll(RedisKeys.call(callId), Map.of(
                "state", CallState.CONNECTED.name(),
                "agentId", agentId,
                "connectedAt", String.valueOf(now),
                "waitMs", String.valueOf(waitMs)));
        redis.opsForSet().add(RedisKeys.CALLS_ACTIVE, callId);
        incrementStat("connected");
        redis.opsForHash().increment(RedisKeys.STATS, "waitMsTotal", waitMs);   // avg wait = waitMsTotal / connected
        events.publish(CallEvent.of("CALL_CONNECTED", callId, agentId, null));
        log.info("Call {} connected to agent {} after {} ms", callId, agentId, waitMs);
        return true;
    }

    /** Marks a call as finished and lets Redis expire its record later. */
    private void finish(String callId, CallState state, String stat) {
        redis.opsForHash().put(RedisKeys.call(callId), "state", state.name());
        redis.opsForHash().put(RedisKeys.call(callId), "endedAt", String.valueOf(System.currentTimeMillis()));
        redis.expire(RedisKeys.call(callId), FINISHED_CALL_TTL);
        incrementStat(stat);
        events.publish(CallEvent.of("CALL_" + state.name(), callId, null, null));
    }

    private boolean isBusy(String agentId) {
        Object status = redis.opsForHash().get(RedisKeys.agent(agentId), "status");
        return AgentStatus.BUSY.name().equals(status);
    }

    private void incrementStat(String field) {
        redis.opsForHash().increment(RedisKeys.STATS, field, 1);
    }
}
