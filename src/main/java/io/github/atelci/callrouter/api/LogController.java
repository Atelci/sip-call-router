package io.github.atelci.callrouter.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.atelci.callrouter.logs.LogTailer;

/** The end of this instance's log file; new lines then arrive over SSE /api/events as event "log". */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogTailer tailer;

    public LogController(LogTailer tailer) {
        this.tailer = tailer;
    }

    @GetMapping
    public List<LogTailer.LogLine> recent() {
        return tailer.recent();
    }
}
