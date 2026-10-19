package io.github.atelci.callrouter.call;

/**
 * The router's view of the signaling layer. Keeps routing logic free of SIP details,
 * so it can be unit-tested with a fake and the SIP stack could be swapped out.
 */
public interface CallSignaling {

    /**
     * Answers a waiting call and connects it to the agent.
     *
     * @return false if the call can no longer be answered (caller already hung up)
     */
    boolean answer(String callId, String agentId);
}
