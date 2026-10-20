package io.github.atelci.callrouter.api;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams two Redis channels to browsers as Server-Sent Events:
 * {@code call-events} as SSE event "call" and {@code sip-messages} as SSE event "sip".
 *
 * <pre>
 * router --PUBLISH--> Redis call-events / sip-messages --(listener in RedisConfig)--> every open GET /api/events
 * </pre>
 *
 * Because the source is Redis pub/sub and not the router itself, a browser connected to any
 * instance sees the events of all instances. Try it: {@code curl -N localhost:8080/api/events}
 */
@RestController
@RequestMapping("/api")
public class EventStreamController {

    private final List<SseEmitter> clients = new CopyOnWriteArrayList<>();

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() throws IOException {
        SseEmitter emitter = new SseEmitter(0L);   // 0 = no timeout; the browser decides when to leave
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(e -> clients.remove(emitter));
        // Without a first write the response headers wait for the first call event, and an idle
        // browser would sit at "connecting". An SSE comment line flushes them and is ignored by clients.
        emitter.send(SseEmitter.event().comment("connected"));
        clients.add(emitter);
        return emitter;
    }

    /**
     * A browser that closed its tab or reloaded makes the next send fail, and Spring then finishes that
     * request with the IOException. That is normal for a stream, so swallow it instead of logging an error.
     */
    @ExceptionHandler({IOException.class, AsyncRequestNotUsableException.class})
    public void clientDisconnected() {
    }

    /** Called by the Redis listener container for every message on one of our channels. */
    public void forward(String eventName, String json) {
        for (SseEmitter client : clients) {
            try {
                client.send(SseEmitter.event().name(eventName).data(json, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                clients.remove(client);   // browser went away
            }
        }
    }
}
