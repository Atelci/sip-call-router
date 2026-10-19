package io.github.atelci.callrouter.agent;

/**
 * @param idleSince epoch ms the agent became free; null unless AVAILABLE
 */
public record Agent(String id, AgentStatus status, String currentCallId, Long idleSince) {
}
