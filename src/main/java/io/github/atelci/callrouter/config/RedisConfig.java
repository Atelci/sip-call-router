package io.github.atelci.callrouter.config;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.github.atelci.callrouter.redis.RedisKeys;

@Configuration
public class RedisConfig {

    private static final Logger eventLog = LoggerFactory.getLogger("call-events");

    /**
     * Subscribes to our own event channel and logs every event. Stands in for a real
     * consumer such as a supervisor wallboard or a reporting service.
     */
    @Bean
    RedisMessageListenerContainer callEventListener(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> eventLog.info(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(RedisKeys.EVENTS_CHANNEL));
        return container;
    }
}
