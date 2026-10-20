package io.github.atelci.callrouter.redis;

/**
 * Every Redis key the app uses, in one place.
 *
 * <pre>
 * agents:all                SET    every known agent id
 * skills                    SET    every skill some agent has (en, de, billing, ...)
 * agents:available          ZSET   all free agents, score = idle-since (epoch ms); ZPOPMIN = longest idle
 * agents:available:{skill}  ZSET   free agents with that skill (same scores)
 * agent:{id}                HASH   status, currentCallId, skills (comma separated)
 * queue:calls               LIST   general calls waiting for any agent (RPUSH in, LPOP out = FIFO)
 * queue:calls:{skill}       LIST   calls waiting for an agent with that skill
 * calls:active              SET    call ids currently connected to an agent
 * call:{callId}             HASH   from, to, skill, state, agentId, createdAt, connectedAt, waitMs (TTL after end)
 * stats                     HASH   counters: total, connected, queued, rejected, abandoned, ended, waitMsTotal
 * call-events               PUB/SUB channel with JSON call events
 * </pre>
 *
 * A {@code null} skill means "general": the all-agents pool and the general queue.
 */
public final class RedisKeys {

    public static final String AGENTS_ALL = "agents:all";
    public static final String SKILLS = "skills";
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

    /** Free-agent pool for a skill, or the all-agents pool for a general call. */
    public static String agentsAvailable(String skill) {
        return skill == null ? AGENTS_AVAILABLE : AGENTS_AVAILABLE + ":" + skill;
    }

    /** Waiting-call queue for a skill, or the general queue. */
    public static String callQueue(String skill) {
        return skill == null ? CALL_QUEUE : CALL_QUEUE + ":" + skill;
    }
}
