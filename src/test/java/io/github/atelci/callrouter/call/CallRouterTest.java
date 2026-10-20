package io.github.atelci.callrouter.call;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
        final List<String> answered = Collections.synchronizedList(new ArrayList<>());

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
        RouterProperties props = new RouterProperties(null, MAX_QUEUE_SIZE, Map.of());
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

    @Test
    void drainPairsOldestCallsWithLongestIdleAgents() throws InterruptedException {
        router.onIncomingCall("c1", "caller1", "support");   // queued, no agents yet
        router.onIncomingCall("c2", "caller2", "support");
        freeAgent("alice");                                   // markAvailable alone does not drain
        freeAgent("bob");
        agents.register("carol");                             // registered but offline: never used

        router.drainQueue();

        assertThat(signaling.answered).containsExactly("c1->alice", "c2->bob");
        assertThat(redis.opsForList().size(RedisKeys.CALL_QUEUE)).isZero();
        assertThat(redis.opsForHash().get(RedisKeys.agent("bob"), "status")).isEqualTo("BUSY");
        assertThat(redis.opsForHash().get(RedisKeys.agent("bob"), "currentCallId")).isEqualTo("c2");
    }

    @Test
    void drainLeavesAgentFreeWhenQueueIsEmpty() throws InterruptedException {
        freeAgent("alice");

        router.drainQueue();

        assertThat(signaling.answered).isEmpty();
        assertThat(agents.availableCount()).isEqualTo(1);
    }

    /**
     * Two router instances (as if two app servers share one Redis) drain the same queue from
     * many threads at once. Every call must get exactly one agent and no agent may be used twice.
     */
    @Test
    void twoInstancesDrainingTogetherNeverSplitOrReusePairs() throws Exception {
        int calls = 40;
        for (int i = 0; i < calls; i++) {
            redis.opsForList().rightPush(RedisKeys.CALL_QUEUE, "c" + i);
            agents.register("a" + i);
            agents.markAvailable("a" + i);
        }
        FakeSignaling shared = new FakeSignaling();
        RouterProperties props = new RouterProperties(null, calls, Map.of());
        CallEventPublisher events = new CallEventPublisher(redis, new ObjectMapper());
        List<CallRouter> instances = List.of(
                new CallRouter(redis, agents, events, props),
                new CallRouter(redis, agents, events, props));
        instances.forEach(r -> r.setSignaling(shared));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> runs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            runs.add(pool.submit(instances.get(i % 2)::drainQueue));
        }
        for (Future<?> run : runs) {
            run.get();
        }
        pool.shutdown();

        List<String> answered = shared.answered;
        assertThat(answered).hasSize(calls);
        assertThat(answered.stream().map(p -> p.split("->")[0]).distinct()).hasSize(calls);
        assertThat(answered.stream().map(p -> p.split("->")[1]).distinct()).hasSize(calls);
        assertThat(redis.opsForList().size(RedisKeys.CALL_QUEUE)).isZero();
        assertThat(agents.availableCount()).isZero();
    }

    // ------------------------------------------------------------- skills-based routing

    @Test
    void skilledCallGoesToAgentWithThatSkillEvenIfAnotherIsIdleLonger() throws InterruptedException {
        freeAgent("alice", "en");                             // idle longest, but no German
        freeAgent("bob", "de");

        RoutingDecision decision = router.onIncomingCall("c1", "caller1", "de", "de");

        assertThat(decision).isEqualTo(new RoutingDecision.Connected("bob"));
        assertThat(redis.opsForHash().get(RedisKeys.call("c1"), "skill")).isEqualTo("de");
    }

    @Test
    void skilledCallWaitsInItsOwnQueueWhileOtherAgentsStayFree() throws InterruptedException {
        freeAgent("alice", "en");
        agents.register("carol", List.of("de"));             // the only German speaker, offline

        RoutingDecision decision = router.onIncomingCall("c1", "caller1", "de", "de");

        assertThat(decision).isInstanceOf(RoutingDecision.Queued.class);
        assertThat(redis.opsForList().range(RedisKeys.callQueue("de"), 0, -1)).containsExactly("c1");
        assertThat(agents.availableCount()).isEqualTo(1);   // alice was not taken for a German call
        assertThat(router.onIncomingCall("c2", "caller2", "support"))
                .isEqualTo(new RoutingDecision.Connected("alice"));
    }

    @Test
    void callForASkillNobodyHasIsTreatedAsGeneral() throws InterruptedException {
        freeAgent("alice", "en");

        RoutingDecision decision = router.onIncomingCall("c1", "caller1", "support", "support");

        assertThat(decision).isEqualTo(new RoutingDecision.Connected("alice"));
        assertThat(redis.opsForHash().get(RedisKeys.call("c1"), "skill")).isNull();
    }

    @Test
    void claimedAgentLeavesEveryPoolItIsIn() throws InterruptedException {
        freeAgent("alice", "en", "billing");

        router.onIncomingCall("c1", "caller1", "support");   // general call takes alice

        assertThat(redis.opsForZSet().zCard(RedisKeys.AGENTS_AVAILABLE)).isZero();
        assertThat(redis.opsForZSet().zCard(RedisKeys.agentsAvailable("en"))).isZero();
        assertThat(redis.opsForZSet().zCard(RedisKeys.agentsAvailable("billing"))).isZero();
        assertThat(router.onIncomingCall("c2", "caller2", "billing", "billing"))
                .isInstanceOf(RoutingDecision.Queued.class);  // alice is not handed out twice
    }

    @Test
    void freedAgentTakesTheLongestWaitingCallItCanHandle() throws InterruptedException {
        freeAgent("alice", "en", "de");
        agents.register("bob", List.of("billing"));          // makes "billing" known, stays offline
        router.onIncomingCall("c1", "caller1", "en", "en");             // alice is busy from here on
        router.onIncomingCall("c2", "caller2", "billing", "billing");   // oldest, but alice can't take it
        Thread.sleep(5);
        router.onIncomingCall("c3", "caller3", "de", "de");             // alice can
        Thread.sleep(5);
        router.onIncomingCall("c4", "caller4", "support");              // general: anyone can

        router.onCallEnded("c1");

        assertThat(signaling.answered).containsExactly("c1->alice", "c3->alice");
        assertThat(redis.opsForList().range(RedisKeys.callQueue("billing"), 0, -1)).containsExactly("c2");
        assertThat(redis.opsForList().range(RedisKeys.CALL_QUEUE, 0, -1)).containsExactly("c4");
    }

    @Test
    void cancelRemovesCallFromItsSkillQueue() throws InterruptedException {
        agents.register("bob", List.of("billing"));
        router.onIncomingCall("c1", "caller1", "billing", "billing");

        router.onCallAbandoned("c1");

        assertThat(callState("c1")).isEqualTo("ABANDONED");
        assertThat(redis.opsForList().size(RedisKeys.callQueue("billing"))).isZero();
    }

    /** Registers an agent as free. The short sleep gives each agent a distinct idle-since score. */
    private void freeAgent(String agentId, String... skills) throws InterruptedException {
        agents.register(agentId, List.of(skills));
        agents.markAvailable(agentId);
        Thread.sleep(5);
    }

    private String callState(String callId) {
        return (String) redis.opsForHash().get(RedisKeys.call(callId), "state");
    }
}
