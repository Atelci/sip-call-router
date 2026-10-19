package io.github.atelci.callrouter.call;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.atelci.callrouter.redis.RedisKeys;

/**
 * Fire-and-forget call events over Redis pub/sub. Any number of consumers (a wallboard,
 * a reporting job, {@code redis-cli SUBSCRIBE call-events}) can listen without touching the router.
 */
@Component
public class CallEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CallEventPublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public CallEventPublisher(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void publish(CallEvent event) {
        try {
            redis.convertAndSend(RedisKeys.EVENTS_CHANNEL, json.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize {}", event, e);
        }
    }
}
