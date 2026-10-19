package io.github.atelci.callrouter.config;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import io.github.atelci.callrouter.api.EventStreamController;
import io.github.atelci.callrouter.redis.RedisKeys;

@Configuration
public class RedisConfig {

    private static final Logger eventLog = LoggerFactory.getLogger("call-events");

    /**
     * Subscribes to our own event channel: logs every event and forwards it to browsers
     * connected to {@code /api/events}. Other consumers (the Go wallboard) subscribe on their own.
     */
    @Bean
    RedisMessageListenerContainer callEventListener(RedisConnectionFactory connectionFactory,
                                                    EventStreamController browsers) {
        ChannelTopic events = new ChannelTopic(RedisKeys.EVENTS_CHANNEL);
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> eventLog.info(new String(message.getBody(), StandardCharsets.UTF_8)),
                events);
        container.addMessageListener(browsers, events);
        return container;
    }
}
