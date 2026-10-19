package io.github.atelci.callrouter.agent;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.github.atelci.callrouter.config.RouterProperties;
import io.github.atelci.callrouter.redis.RedisKeys;

/**
 * On startup: clears call state left over from a previous run (its SIP transactions are gone),
 * then registers the demo agents from application.yml and makes them available.
 */
@Component
public class AgentSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentSeeder.class);

    private final AgentService agents;
    private final RouterProperties props;
    private final StringRedisTemplate redis;

    public AgentSeeder(AgentService agents, RouterProperties props, StringRedisTemplate redis) {
        this.agents = agents;
        this.props = props;
        this.redis = redis;
    }

    @Override
    public void run(ApplicationArguments args) {
        redis.delete(List.of(RedisKeys.CALL_QUEUE, RedisKeys.CALLS_ACTIVE));
        if (props.agents() == null) {
            return;
        }
        for (String id : props.agents()) {
            agents.register(id);
            agents.markAvailable(id);
        }
        log.info("Seeded {} available agents: {}", props.agents().size(), props.agents());
    }
}
