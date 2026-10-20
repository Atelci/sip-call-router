package io.github.atelci.callrouter.logs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.atelci.callrouter.api.EventStreamController;

/**
 * {@code tail -f} for the app's own log file ({@code logging.file.name}), streamed to the browser.
 * Every half second it reads whatever was appended since the last read, keeps the last lines in
 * memory for pages that open later ({@code GET /api/logs}), and sends new lines as SSE event "log".
 *
 * Deliberately never logs anything itself: a log line about tailing would be tailed again.
 */
@Component
public class LogTailer {

    /** One log line; seq lets the browser skip lines it already got from GET /api/logs. */
    public record LogLine(long seq, String text) {
    }

    private static final int KEEP_LINES = 500;
    private static final int START_BACKLOG_BYTES = 64 * 1024;   // on startup, show the end of the file
    private static final int MAX_READ_BYTES = 1024 * 1024;      // per poll, so one burst can't stall it

    private final Path file;
    private final EventStreamController browsers;
    private final ObjectMapper json;
    private final Deque<LogLine> recent = new ArrayDeque<>();

    private long position = -1;   // byte offset read so far; -1 = not started
    private long seq;

    public LogTailer(@Value("${logging.file.name:}") String fileName,
                     EventStreamController browsers, ObjectMapper json) {
        this.file = fileName.isBlank() ? null : Path.of(fileName);
        this.browsers = browsers;
        this.json = json;
    }

    /** The last lines of the log, oldest first. */
    public synchronized List<LogLine> recent() {
        return new ArrayList<>(recent);
    }

    @Scheduled(fixedDelay = 500)
    public void poll() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        List<LogLine> lines;
        try {
            lines = readNewLines();
        } catch (IOException e) {
            return;   // file being rotated; try again next time
        }
        if (!lines.isEmpty()) {
            try {
                browsers.forward("log", json.writeValueAsString(lines));
            } catch (JsonProcessingException e) {
                // cannot happen for a list of records of strings and longs
            }
        }
    }

    private synchronized List<LogLine> readNewLines() throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            boolean firstRead = position < 0;
            if (firstRead) {
                position = Math.max(0, length - START_BACKLOG_BYTES);
            } else if (length < position) {
                position = 0;   // file was rotated or truncated: start over from the top
            }
            if (length == position) {
                return List.of();
            }

            byte[] buffer = new byte[(int) Math.min(length - position, MAX_READ_BYTES)];
            raf.seek(position);
            raf.readFully(buffer);

            // only take complete lines; a half-written last line is read next time
            int end = lastNewline(buffer);
            if (end < 0) {
                return List.of();
            }
            position += end + 1;

            String[] texts = new String(buffer, 0, end, StandardCharsets.UTF_8).split("\r?\n", -1);
            List<LogLine> lines = new ArrayList<>();
            // a backlog read usually starts mid-line: drop that first fragment
            for (int i = (firstRead && position - end - 1 > 0) ? 1 : 0; i < texts.length; i++) {
                LogLine line = new LogLine(++seq, texts[i]);
                lines.add(line);
                recent.addLast(line);
                if (recent.size() > KEEP_LINES) {
                    recent.removeFirst();
                }
            }
            return lines;
        }
    }

    private static int lastNewline(byte[] buffer) {
        for (int i = buffer.length - 1; i >= 0; i--) {
            if (buffer[i] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
