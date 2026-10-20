package io.github.atelci.callrouter.sip;

import java.time.Duration;

import javax.sip.header.CSeqHeader;
import javax.sip.header.CallIdHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.atelci.callrouter.redis.RedisKeys;

/**
 * Records every SIP message the server receives or sends, so the browser can draw the handshake
 * as a ladder diagram. Each message is appended to {@code sip:{callId}} (kept for an hour, so a page
 * opened mid-call can load the history) and published on {@code sip-messages} for live updates.
 */
@Component
public class SipTrace {

    /**
     * @param direction IN = caller to router (requests), OUT = router to caller (responses)
     * @param label     first line in short form: "INVITE" or "180 Ringing"
     * @param method    CSeq method, tells which request a response belongs to (200 OK for BYE vs INVITE)
     * @param raw       the full message text, shown when a message is clicked in the diagram
     */
    public record SipMessage(String callId, String direction, String label, String method, String raw, long timestamp) {
    }

    private static final Logger log = LoggerFactory.getLogger(SipTrace.class);
    private static final Duration TTL = Duration.ofHours(1);
    private static final int MAX_MESSAGES_PER_CALL = 50;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public SipTrace(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    public void incoming(Request request) {
        record("IN", request.getMethod(), request);
    }

    public void outgoing(Response response) {
        record("OUT", response.getStatusCode() + " " + response.getReasonPhrase(), response);
    }

    private void record(String direction, String label, Message message) {
        CallIdHeader callId = (CallIdHeader) message.getHeader(CallIdHeader.NAME);
        CSeqHeader cseq = (CSeqHeader) message.getHeader(CSeqHeader.NAME);
        if (callId == null) {
            return;
        }
        SipMessage entry = new SipMessage(callId.getCallId(), direction, label,
                cseq == null ? "?" : cseq.getMethod(), message.toString(), System.currentTimeMillis());
        try {
            String key = RedisKeys.sipTrace(entry.callId());
            String value = json.writeValueAsString(entry);
            redis.opsForList().rightPush(key, value);
            redis.opsForList().trim(key, -MAX_MESSAGES_PER_CALL, -1);
            redis.expire(key, TTL);
            redis.convertAndSend(RedisKeys.SIP_CHANNEL, value);
        } catch (JsonProcessingException | RuntimeException e) {
            // tracing is for the demo UI only and must never break call handling
            log.debug("Could not trace {} for {}", label, entry.callId(), e);
        }
    }
}
