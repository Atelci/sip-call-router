package io.github.atelci.callrouter.agent;

import java.util.List;

/**
 * @param skills    what the agent can handle (e.g. en, billing); empty = general calls only
 * @param idleSince epoch ms the agent became free; null unless AVAILABLE
 */
public record Agent(String id, List<String> skills, AgentStatus status, String currentCallId, Long idleSince) {
}
