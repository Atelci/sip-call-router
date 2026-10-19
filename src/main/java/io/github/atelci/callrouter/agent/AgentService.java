package io.github.atelci.callrouter.agent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;

import io.github.atelci.callrouter.redis.RedisKeys;

/**
 * Agent state lives entirely in Redis. Free agents sit in a sorted set scored by the time
 * they became idle, so ZPOPMIN hands out the longest-idle agent in one atomic step.
 */
@Service
public class AgentService {

    private final StringRedisTemplate redis;

    public AgentService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void register(String agentId) {
        redis.opsForSet().add(RedisKeys.AGENTS_ALL, agentId);
        redis.opsForHash().putIfAbsent(RedisKeys.agent(agentId), "status", AgentStatus.OFFLINE.name());
    }

    public boolean exists(String agentId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(RedisKeys.AGENTS_ALL, agentId));
    }

    public void markAvailable(String agentId) {
        redis.opsForHash().put(RedisKeys.agent(agentId), "status", AgentStatus.AVAILABLE.name());
        redis.opsForHash().delete(RedisKeys.agent(agentId), "currentCallId");
        redis.opsForZSet().add(RedisKeys.AGENTS_AVAILABLE, agentId, System.currentTimeMillis());
    }

    public void markOffline(String agentId) {
        redis.opsForZSet().remove(RedisKeys.AGENTS_AVAILABLE, agentId);
        redis.opsForHash().put(RedisKeys.agent(agentId), "status", AgentStatus.OFFLINE.name());
    }

    /** Atomically takes the longest-idle free agent and marks it busy on the given call. */
    public Optional<String> claimLongestIdle(String callId) {
        TypedTuple<String> popped = redis.opsForZSet().popMin(RedisKeys.AGENTS_AVAILABLE);
        if (popped == null || popped.getValue() == null) {
            return Optional.empty();
        }
        String agentId = popped.getValue();
        redis.opsForHash().putAll(RedisKeys.agent(agentId), Map.of(
                "status", AgentStatus.BUSY.name(),
                "currentCallId", callId));
        return Optional.of(agentId);
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

    private Agent load(String agentId) {
        Map<Object, Object> hash = redis.opsForHash().entries(RedisKeys.agent(agentId));
        Object status = hash.getOrDefault("status", AgentStatus.OFFLINE.name());
        Double score = redis.opsForZSet().score(RedisKeys.AGENTS_AVAILABLE, agentId);
        return new Agent(
                agentId,
                AgentStatus.valueOf(status.toString()),
                (String) hash.get("currentCallId"),
                score == null ? null : score.longValue());
    }
}
