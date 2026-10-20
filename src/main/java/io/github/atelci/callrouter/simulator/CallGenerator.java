package io.github.atelci.callrouter.simulator;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import io.github.atelci.callrouter.config.RouterProperties;

/**
 * Starts batches of simulated callers from the browser (POST /api/simulator/calls).
 * Each caller is a blocking little SIP client on a virtual thread: thousands would be cheap,
 * and the code stays plain sequential "send, wait for reply" without callbacks.
 */
@Service
public class CallGenerator {

    /**
     * @param calls      number of callers
     * @param interval   seconds between new callers
     * @param talkMin    shortest talk time in seconds
     * @param talkMax    longest talk time in seconds
     * @param patience   seconds a caller waits in the queue before it sends CANCEL
     * @param skills     users to dial, one picked at random per caller (support = general call)
     */
    public record Run(int calls, double interval, double talkMin, double talkMax, double patience, List<String> skills) {
    }

    private static final Logger log = LoggerFactory.getLogger(CallGenerator.class);
    static final int MAX_CALLS = 50;

    private final InetSocketAddress router;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    public CallGenerator(RouterProperties props) {
        // callers run inside this JVM, so they reach the SIP server on loopback when it listens everywhere
        String host = props.sip().bindHost().equals("0.0.0.0") ? "127.0.0.1" : props.sip().bindHost();
        this.router = new InetSocketAddress(host, props.sip().port());
    }

    /** Starts the run in the background and returns at once. */
    public void start(Run run) {
        if (run.calls() < 1 || run.calls() > MAX_CALLS) {
            throw new IllegalArgumentException("calls must be between 1 and " + MAX_CALLS);
        }
        List<String> skills = run.skills() == null || run.skills().isEmpty() ? List.of("support") : run.skills();
        log.info("UI simulator: {} calls to {} at {}", run.calls(), skills, router);
        threads.submit(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int n = 1; n <= run.calls(); n++) {
                String dialed = skills.get(random.nextInt(skills.size()));
                double talk = run.talkMin() + random.nextDouble() * Math.max(0, run.talkMax() - run.talkMin());
                threads.submit(new SimulatedCaller(n, dialed, router, talk, run.patience()));
                Thread.sleep((long) (run.interval() * 1000));
            }
            return null;
        });
    }

    @PreDestroy
    public void stop() {
        threads.shutdownNow();
    }
}
