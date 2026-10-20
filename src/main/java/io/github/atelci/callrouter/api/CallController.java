package io.github.atelci.callrouter.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.atelci.callrouter.redis.RedisKeys;

/** Read-only views of call state, straight from Redis. */
@RestController
@RequestMapping("/api")
public class CallController {

    private final StringRedisTemplate redis;

    public CallController(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Waiting calls per queue ("general" plus one per skill), each in queue order. */
    @GetMapping("/queue")
    public Map<String, List<Map<String, Object>>> queue() {
        Map<String, List<Map<String, Object>>> queues = new TreeMap<>();
        queueKeys().forEach((name, key) -> {
            List<String> ids = redis.opsForList().range(key, 0, -1);
            queues.put(name, ids == null ? List.of() : ids.stream().map(this::call).toList());
        });
        return queues;
    }

    @GetMapping("/calls/active")
    public List<Map<String, Object>> activeCalls() {
        Set<String> ids = redis.opsForSet().members(RedisKeys.CALLS_ACTIVE);
        return ids == null ? List.of() : ids.stream().map(this::call).toList();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> stats = new TreeMap<>();
        redis.opsForHash().entries(RedisKeys.STATS).forEach((k, v) -> stats.put(k.toString(), Long.parseLong(v.toString())));
        Map<String, Long> queueLengths = new TreeMap<>();
        queueKeys().forEach((name, key) -> queueLengths.put(name, redis.opsForList().size(key)));
        stats.put("queueLength", queueLengths.values().stream().mapToLong(Long::longValue).sum());
        stats.put("queues", queueLengths);
        stats.put("activeCalls", redis.opsForSet().size(RedisKeys.CALLS_ACTIVE));
        stats.put("availableAgents", redis.opsForZSet().zCard(RedisKeys.AGENTS_AVAILABLE));
        return stats;
    }

    /** Queue name -> Redis key: "general" and one per known skill. */
    private Map<String, String> queueKeys() {
        Map<String, String> keys = new TreeMap<>();
        keys.put("general", RedisKeys.callQueue(null));
        Set<String> skills = redis.opsForSet().members(RedisKeys.SKILLS);
        if (skills != null) {
            skills.forEach(skill -> keys.put(skill, RedisKeys.callQueue(skill)));
        }
        return keys;
    }

    private Map<String, Object> call(String callId) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("callId", callId);
        redis.opsForHash().entries(RedisKeys.call(callId)).forEach((k, v) -> call.put(k.toString(), v));
        return call;
    }
}
