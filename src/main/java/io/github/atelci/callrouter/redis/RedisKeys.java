package io.github.atelci.callrouter.redis;

/**
 * Every Redis key the app uses, in one place.
 *
 * <pre>
 * agents:all          SET    every known agent id
 * agents:available    ZSET   free agents, score = idle-since (epoch ms); ZPOPMIN = longest idle
 * agent:{id}          HASH   status, currentCallId
 * queue:calls         LIST   call ids waiting for an agent (RPUSH in, LPOP out = FIFO)
 * calls:active        SET    call ids currently connected to an agent
 * call:{callId}       HASH   from, to, state, agentId, createdAt, connectedAt, waitMs (TTL after end)
 * stats               HASH   counters: total, connected, queued, rejected, abandoned, ended
 * call-events         PUB/SUB channel with JSON call events
 * </pre>
 */
public final class RedisKeys {

    public static final String AGENTS_ALL = "agents:all";
    public static final String AGENTS_AVAILABLE = "agents:available";
    public static final String CALL_QUEUE = "queue:calls";
    public static final String CALLS_ACTIVE = "calls:active";
    public static final String STATS = "stats";
    public static final String EVENTS_CHANNEL = "call-events";
    public static final String AGENT_PREFIX = "agent:";

    private RedisKeys() {
    }

    public static String agent(String agentId) {
        return AGENT_PREFIX + agentId;
    }

    public static String call(String callId) {
        return "call:" + callId;
    }
}
