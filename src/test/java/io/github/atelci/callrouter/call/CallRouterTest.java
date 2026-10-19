package io.github.atelci.callrouter.call;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.atelci.callrouter.agent.AgentService;
import io.github.atelci.callrouter.config.RouterProperties;
import io.github.atelci.callrouter.redis.RedisKeys;

/**
 * CallRouter against a real Redis in Docker, with a fake SIP layer.
 * No Spring context is started, so the SIP server never binds UDP 5060:
 * the few objects the router needs are wired by hand.
 */
@Testcontainers
class CallRouterTest {

    private static final int MAX_QUEUE_SIZE = 2;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private AgentService agents;
    private CallRouter router;
    private FakeSignaling signaling;

    /** Stands in for SipServer: records which calls were answered and by whom. */
    static class FakeSignaling implements CallSignaling {
        final List<String> answered = new ArrayList<>();

        @Override
        public boolean answer(String callId, String agentId) {
            answered.add(callId + "->" + agentId);
            return true;
        }
    }

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();   // every test starts from an empty Redis
            return null;
        });
        agents = new AgentService(redis);
        RouterProperties props = new RouterProperties(null, MAX_QUEUE_SIZE, List.of());
        router = new CallRouter(redis, agents, new CallEventPublisher(redis, new ObjectMapper()), props);
        signaling = new FakeSignaling();
        router.setSignaling(signaling);
    }

    @Test
    void newCallGoesToLongestIdleAgent() throws InterruptedException {
        freeAgent("alice");
        freeAgent("bob");

        RoutingDecision decision = router.onIncomingCall("c1", "caller1", "support");

        assertThat(decision).isEqualTo(new RoutingDecision.Connected("alice"));
        assertThat(signaling.answered).containsExactly("c1->alice");
        assertThat(callState("c1")).isEqualTo("CONNECTED");
        assertThat(redis.opsForSet().members(RedisKeys.CALLS_ACTIVE)).containsExactly("c1");
    }

    @Test
    void callsWaitInFifoOrderWhenNoAgentIsFree() {
        assertThat(router.onIncomingCall("c1", "caller1", "support")).isEqualTo(new RoutingDecision.Queued(1));
        assertThat(router.onIncomingCall("c2", "caller2", "support")).isEqualTo(new RoutingDecision.Queued(2));

        assertThat(redis.opsForList().range(RedisKeys.CALL_QUEUE, 0, -1)).containsExactly("c1", "c2");
        assertThat(signaling.answered).isEmpty();
    }

    @Test
    void callIsRejectedWhenQueueIsFull() {
        router.onIncomingCall("c1", "caller1", "support");
        router.onIncomingCall("c2", "caller2", "support");

        RoutingDecision decision = router.onIncomingCall("c3", "caller3", "support");

        assertThat(decision).isInstanceOf(RoutingDecision.Rejected.class);
        assertThat(callState("c3")).isEqualTo("REJECTED");
        assertThat(redis.opsForList().size(RedisKeys.CALL_QUEUE)).isEqualTo(MAX_QUEUE_SIZE);
    }

    @Test
    void hangupFreesAgentForNextQueuedCaller() throws InterruptedException {
        freeAgent("alice");
        router.onIncomingCall("c1", "caller1", "support");   // connected to alice
        router.onIncomingCall("c2", "caller2", "support");   // queued
        router.onIncomingCall("c3", "caller3", "support");   // queued

        router.onCallEnded("c1");                             // SIP BYE

        assertThat(callState("c1")).isEqualTo("ENDED");
        assertThat(callState("c2")).isEqualTo("CONNECTED");
        assertThat(signaling.answered).containsExactly("c1->alice", "c2->alice");
        assertThat(redis.opsForList().range(RedisKeys.CALL_QUEUE, 0, -1)).containsExactly("c3");
        assertThat(redis.opsForSet().members(RedisKeys.CALLS_ACTIVE)).containsExactly("c2");
    }

    @Test
    void cancelRemovesCallFromQueue() {
        router.onIncomingCall("c1", "caller1", "support");
        router.onIncomingCall("c2", "caller2", "support");

        router.onCallAbandoned("c1");                         // SIP CANCEL

        assertThat(callState("c1")).isEqualTo("ABANDONED");
        assertThat(redis.opsForList().range(RedisKeys.CALL_QUEUE, 0, -1)).containsExactly("c2");
    }

    /** Registers an agent as free. The short sleep gives each agent a distinct idle-since score. */
    private void freeAgent(String agentId) throws InterruptedException {
        agents.register(agentId);
        agents.markAvailable(agentId);
        Thread.sleep(5);
    }

    private String callState(String callId) {
        return (String) redis.opsForHash().get(RedisKeys.call(callId), "state");
    }
}
