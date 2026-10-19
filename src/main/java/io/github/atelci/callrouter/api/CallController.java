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

    /** Waiting calls in queue order. */
    @GetMapping("/queue")
    public List<Map<String, Object>> queue() {
        List<String> ids = redis.opsForList().range(RedisKeys.CALL_QUEUE, 0, -1);
        return ids == null ? List.of() : ids.stream().map(this::call).toList();
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
        stats.put("queueLength", redis.opsForList().size(RedisKeys.CALL_QUEUE));
        stats.put("activeCalls", redis.opsForSet().size(RedisKeys.CALLS_ACTIVE));
        stats.put("availableAgents", redis.opsForZSet().zCard(RedisKeys.AGENTS_AVAILABLE));
        return stats;
    }

    private Map<String, Object> call(String callId) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("callId", callId);
        redis.opsForHash().entries(RedisKeys.call(callId)).forEach((k, v) -> call.put(k.toString(), v));
        return call;
    }
}
