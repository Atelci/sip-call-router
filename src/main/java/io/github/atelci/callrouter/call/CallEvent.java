package io.github.atelci.callrouter.call;

/** Published as JSON on the Redis {@code call-events} channel. */
public record CallEvent(String type, String callId, String agentId, String from, long timestamp) {

    public static CallEvent of(String type, String callId, String agentId, String from) {
        return new CallEvent(type, callId, agentId, from, System.currentTimeMillis());
    }
}
