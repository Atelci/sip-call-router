package io.github.atelci.callrouter.agent;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import io.github.atelci.callrouter.redis.RedisKeys;

/**
 * Agent state lives entirely in Redis. A free agent sits in the all-agents pool and in one pool
 * per skill, all sorted sets scored by the time it became idle, so the lowest score is the
 * longest-idle agent. Claiming is one Lua script that takes the agent out of every pool at once.
 */
@Service
public class AgentService {

    /** A queued call and the agent that just took it. */
    public record Pairing(String callId, String agentId) {
    }

    /** Returns [callId, agentId] or an empty list. Raw List because RedisScript takes a Class. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final RedisScript<List<String>> CLAIM_AGENT = (RedisScript) RedisScript.of(
            new ClassPathResource("redis/claim-agent.lua"), List.class);

    private final StringRedisTemplate redis;

    public AgentService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void register(String agentId) {
        register(agentId, List.of());
    }

    public void register(String agentId, Collection<String> skills) {
        redis.opsForSet().add(RedisKeys.AGENTS_ALL, agentId);
        redis.opsForHash().put(RedisKeys.agent(agentId), "skills", String.join(",", skills));
        redis.opsForHash().putIfAbsent(RedisKeys.agent(agentId), "status", AgentStatus.OFFLINE.name());
        if (!skills.isEmpty()) {
            redis.opsForSet().add(RedisKeys.SKILLS, skills.toArray(String[]::new));
        }
    }

    public boolean exists(String agentId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(RedisKeys.AGENTS_ALL, agentId));
    }

    /** Every skill some agent has; a call for anything else is treated as a general call. */
    public Set<String> knownSkills() {
        Set<String> skills = redis.opsForSet().members(RedisKeys.SKILLS);
        return skills == null ? Set.of() : skills;
    }

    public void markAvailable(String agentId) {
        redis.opsForHash().put(RedisKeys.agent(agentId), "status", AgentStatus.AVAILABLE.name());
        redis.opsForHash().delete(RedisKeys.agent(agentId), "currentCallId");
        long now = System.currentTimeMillis();
        redis.opsForZSet().add(RedisKeys.AGENTS_AVAILABLE, agentId, now);
        for (String skill : skillsOf(agentId)) {
            redis.opsForZSet().add(RedisKeys.agentsAvailable(skill), agentId, now);
        }
    }

    public void markOffline(String agentId) {
        redis.opsForZSet().remove(RedisKeys.AGENTS_AVAILABLE, agentId);
        for (String skill : skillsOf(agentId)) {
            redis.opsForZSet().remove(RedisKeys.agentsAvailable(skill), agentId);
        }
        redis.opsForHash().put(RedisKeys.agent(agentId), "status", AgentStatus.OFFLINE.name());
    }

    /** A new call: takes the longest-idle free agent with that skill (null = any agent) and marks it busy. */
    public Optional<String> claimForCall(String callId, String skill) {
        List<String> pair = redis.execute(CLAIM_AGENT, List.of(RedisKeys.agentsAvailable(skill)),
                RedisKeys.AGENT_PREFIX, RedisKeys.AGENTS_AVAILABLE, callId);
        return pair == null || pair.size() < 2 ? Optional.empty() : Optional.of(pair.get(1));
    }

    /** Serving a queue: pops its oldest call together with the longest-idle agent that has the skill. */
    public Optional<Pairing> claimForQueue(String skill) {
        List<String> pair = redis.execute(CLAIM_AGENT,
                List.of(RedisKeys.agentsAvailable(skill), RedisKeys.callQueue(skill)),
                RedisKeys.AGENT_PREFIX, RedisKeys.AGENTS_AVAILABLE);
        return pair == null || pair.size() < 2 ? Optional.empty() : Optional.of(new Pairing(pair.get(0), pair.get(1)));
    }

    public long availableCount() {
        Long size = redis.opsForZSet().zCard(RedisKeys.AGENTS_AVAILABLE);
        return size == null ? 0 : size;
    }

    public List<Agent> findAll() {
        Set<String> ids = redis.opsForSet().members(RedisKeys.AGENTS_ALL);
        if (ids == null) {
            return List.of();
        }
        return ids.stream()
                .map(this::load)
                .sorted(Comparator.comparing(Agent::id))
                .toList();
    }

    private List<String> skillsOf(String agentId) {
        Object skills = redis.opsForHash().get(RedisKeys.agent(agentId), "skills");
        return split(skills);
    }

    private static List<String> split(Object skills) {
        return skills == null || skills.toString().isEmpty() ? List.of() : Arrays.asList(skills.toString().split(","));
    }

    private Agent load(String agentId) {
        Map<Object, Object> hash = redis.opsForHash().entries(RedisKeys.agent(agentId));
        Object status = hash.getOrDefault("status", AgentStatus.OFFLINE.name());
        Double score = redis.opsForZSet().score(RedisKeys.AGENTS_AVAILABLE, agentId);
        return new Agent(
                agentId,
                split(hash.get("skills")),
                AgentStatus.valueOf(status.toString()),
                (String) hash.get("currentCallId"),
                score == null ? null : score.longValue());
    }
}
