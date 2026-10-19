package io.github.atelci.callrouter.call;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import io.github.atelci.callrouter.agent.AgentService;
import io.github.atelci.callrouter.agent.AgentStatus;
import io.github.atelci.callrouter.config.RouterProperties;
import io.github.atelci.callrouter.redis.RedisKeys;

/**
 * The routing brain, a tiny ACD (automatic call distributor):
 * <ul>
 *   <li>new call + free agent  -> connect to the longest-idle agent</li>
 *   <li>new call + no agent    -> wait in a FIFO queue (up to maxQueueSize)</li>
 *   <li>queue full             -> reject</li>
 *   <li>agent frees up         -> next queued call gets that agent</li>
 * </ul>
 * All state is in Redis. Methods are synchronized because this MVP runs as a single instance;
 * scaling out would need the claim-and-dequeue step moved into a Redis Lua script.
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

    public synchronized RoutingDecision onIncomingCall(String callId, String from, String to) {
        redis.opsForHash().putAll(RedisKeys.call(callId), Map.of(
                "from", from,
                "to", to,
                "state", CallState.QUEUED.name(),
                "createdAt", String.valueOf(System.currentTimeMillis())));
        incrementStat("total");
        events.publish(CallEvent.of("CALL_RECEIVED", callId, null, from));

        Optional<String> agent = agents.claimLongestIdle(callId);
        if (agent.isPresent()) {
            return connect(callId, agent.get())
                    ? new RoutingDecision.Connected(agent.get())
                    : new RoutingDecision.Rejected();
        }

        Long queueSize = redis.opsForList().size(RedisKeys.CALL_QUEUE);
        if (queueSize != null && queueSize >= props.maxQueueSize()) {
            finish(callId, CallState.REJECTED, "rejected");
            log.info("Call {} from {} rejected: queue full ({})", callId, from, queueSize);
            return new RoutingDecision.Rejected();
        }

        Long position = redis.opsForList().rightPush(RedisKeys.CALL_QUEUE, callId);
        incrementStat("queued");
        events.publish(CallEvent.of("CALL_QUEUED", callId, null, from));
        log.info("Call {} from {} queued at position {}", callId, from, position);
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
        Long removed = redis.opsForList().remove(RedisKeys.CALL_QUEUE, 1, callId);
        if (removed != null && removed > 0) {
            finish(callId, CallState.ABANDONED, "abandoned");
            log.info("Call {} abandoned in queue", callId);
        }
    }

    /** Pairs queued calls with free agents until one side runs out. */
    public synchronized void drainQueue() {
        while (agents.availableCount() > 0) {
            String callId = redis.opsForList().leftPop(RedisKeys.CALL_QUEUE);
            if (callId == null) {
                return;
            }
            Optional<String> agent = agents.claimLongestIdle(callId);
            if (agent.isEmpty()) {
                redis.opsForList().leftPush(RedisKeys.CALL_QUEUE, callId);
                return;
            }
            connect(callId, agent.get());
        }
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
