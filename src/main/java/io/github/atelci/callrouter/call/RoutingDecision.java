package io.github.atelci.callrouter.call;

/** What the router decided for a new incoming call. The SIP layer maps it to a response. */
public sealed interface RoutingDecision {

    /** An agent was free; the call was already answered via {@link CallSignaling}. */
    record Connected(String agentId) implements RoutingDecision {
    }

    /** No agent free; the call waits in the queue (SIP 182 Queued). */
    record Queued(long position) implements RoutingDecision {
    }

    /** Queue full (SIP 486 Busy Here). */
    record Rejected() implements RoutingDecision {
    }
}
