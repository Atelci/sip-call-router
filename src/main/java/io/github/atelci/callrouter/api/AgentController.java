package io.github.atelci.callrouter.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.atelci.callrouter.agent.Agent;
import io.github.atelci.callrouter.agent.AgentService;
import io.github.atelci.callrouter.agent.AgentStatus;
import io.github.atelci.callrouter.call.CallRouter;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    public record StatusChange(AgentStatus status) {
    }

    private final AgentService agents;
    private final CallRouter router;

    public AgentController(AgentService agents, CallRouter router) {
        this.agents = agents;
        this.router = router;
    }

    @GetMapping
    public List<Agent> list() {
        return agents.findAll();
    }

    /** Adds a new agent (OFFLINE until it is set AVAILABLE). */
    @PostMapping("/{agentId}")
    public void register(@PathVariable String agentId) {
        agents.register(agentId);
    }

    /** Agent goes AVAILABLE (and may pick up a queued call right away) or OFFLINE. */
    @PostMapping("/{agentId}/status")
    public void changeStatus(@PathVariable String agentId, @RequestBody StatusChange change) {
        if (!agents.exists(agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown agent " + agentId);
        }
        switch (change.status()) {
            case AVAILABLE -> {
                agents.markAvailable(agentId);
                router.drainQueue();
            }
            case OFFLINE -> agents.markOffline(agentId);
            case BUSY -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "BUSY is set by the router when a call is connected");
        }
    }
}
