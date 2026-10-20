package io.github.atelci.callrouter.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.atelci.callrouter.simulator.CallGenerator;

/**
 * Lets the browser page start simulated callers, e.g.
 * {@code {"calls":10,"interval":0.5,"talkMin":3,"talkMax":8,"patience":20,"skills":["en","billing"]}}.
 */
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final CallGenerator generator;

    public SimulatorController(CallGenerator generator) {
        this.generator = generator;
    }

    @PostMapping("/calls")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void start(@RequestBody CallGenerator.Run run) {
        try {
            generator.start(run);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
