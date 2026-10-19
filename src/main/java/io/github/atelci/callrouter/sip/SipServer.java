package io.github.atelci.callrouter.sip;

import java.text.ParseException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.ListeningPoint;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.AddressFactory;
import javax.sip.header.CallIdHeader;
import javax.sip.header.ContactHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.HeaderFactory;
import javax.sip.header.ToHeader;
import javax.sip.message.MessageFactory;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import io.github.atelci.callrouter.call.CallRouter;
import io.github.atelci.callrouter.call.CallSignaling;
import io.github.atelci.callrouter.call.RoutingDecision;
import io.github.atelci.callrouter.config.RouterProperties;

/**
 * SIP user agent server (UAS) on top of JAIN-SIP. Signaling only, no media (RTP).
 *
 * <pre>
 * INVITE  -> 100 Trying, then either
 *              180 Ringing + 200 OK (X-Agent-Id: agent)   agent free
 *              182 Queued (X-Queue-Position: n)          waits; 180 + 200 OK later
 *              486 Busy Here                             queue full
 * ACK     -> nothing to do (completes the INVITE handshake)
 * BYE     -> 200 OK, agent released, next queued call served
 * CANCEL  -> 200 OK + 487 Request Terminated on the INVITE, call removed from queue
 * OPTIONS -> 200 OK (handy health check / SIPp ping)
 * </pre>
 *
 * Open INVITE transactions are kept in memory: they are live protocol objects and
 * cannot go to Redis. Everything about the call itself is in Redis.
 */
@Component
public class SipServer implements SipListener, CallSignaling {

    private static final Logger log = LoggerFactory.getLogger(SipServer.class);

    private record PendingInvite(ServerTransaction tx, Request request, String toTag) {
    }

    private final CallRouter router;
    private final RouterProperties.Sip config;
    private final Map<String, PendingInvite> pending = new ConcurrentHashMap<>();

    private SipStack stack;
    private SipProvider provider;
    private MessageFactory messageFactory;
    private HeaderFactory headerFactory;
    private ContactHeader contact;

    public SipServer(CallRouter router, RouterProperties props) {
        this.router = router;
        this.config = props.sip();
    }

    /** Starts after the app is ready, so agents are already seeded when the first call lands. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() throws Exception {
        SipFactory factory = SipFactory.getInstance();
        factory.setPathName("gov.nist");

        Properties stackProps = new Properties();
        stackProps.setProperty("javax.sip.STACK_NAME", "sip-call-router");
        // We manage call state ourselves (in Redis), so no automatic dialog layer.
        stackProps.setProperty("javax.sip.AUTOMATIC_DIALOG_SUPPORT", "off");
        stackProps.setProperty("gov.nist.javax.sip.TRACE_LEVEL", "0");

        stack = factory.createSipStack(stackProps);
        messageFactory = factory.createMessageFactory();
        headerFactory = factory.createHeaderFactory();
        AddressFactory addressFactory = factory.createAddressFactory();

        ListeningPoint lp = stack.createListeningPoint(config.bindHost(), config.port(), ListeningPoint.UDP);
        provider = stack.createSipProvider(lp);
        provider.addSipListener(this);
        stack.start();

        contact = headerFactory.createContactHeader(
                addressFactory.createAddress("sip:router@" + config.advertiseHost() + ":" + config.port()));
        router.setSignaling(this);
        log.info("SIP server listening on udp {}:{}", config.bindHost(), config.port());
    }

    @PreDestroy
    public void stop() {
        if (stack != null) {
            stack.stop();
        }
    }

    // ---------------------------------------------------------------- incoming requests

    @Override
    public void processRequest(RequestEvent event) {
        Request request = event.getRequest();
        try {
            switch (request.getMethod()) {
                case Request.INVITE -> handleInvite(event);
                case Request.ACK -> log.debug("ACK for {}", callId(request));
                case Request.BYE -> handleBye(event);
                case Request.CANCEL -> handleCancel(event);
                case Request.OPTIONS -> serverTransaction(event)
                        .sendResponse(messageFactory.createResponse(Response.OK, request));
                default -> serverTransaction(event)
                        .sendResponse(messageFactory.createResponse(Response.NOT_IMPLEMENTED, request));
            }
        } catch (Exception e) {
            log.error("Failed to handle {} {}", request.getMethod(), callId(request), e);
        }
    }

    private void handleInvite(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        String callId = callId(request);
        if (pending.containsKey(callId)) {
            return; // retransmission of an INVITE we are already handling
        }
        ServerTransaction tx = serverTransaction(event);
        tx.sendResponse(messageFactory.createResponse(Response.TRYING, request));

        PendingInvite invite = new PendingInvite(tx, request, newTag());
        pending.put(callId, invite);

        String from = ((FromHeader) request.getHeader(FromHeader.NAME)).getAddress().getURI().toString();
        String to = ((ToHeader) request.getHeader(ToHeader.NAME)).getAddress().getURI().toString();
        log.info("INVITE {} from {}", callId, from);

        RoutingDecision decision = router.onIncomingCall(callId, from, to);
        switch (decision) {
            case RoutingDecision.Connected connected -> {
                // already answered through answer()
            }
            case RoutingDecision.Queued queued -> {
                Response response = response(Response.QUEUED, invite);
                response.addHeader(headerFactory.createHeader("X-Queue-Position", String.valueOf(queued.position())));
                try {
                    tx.sendResponse(response);
                } catch (SipException e) {
                    // an agent freed up and answered between routing and here; 182 is no longer needed
                    log.debug("Skipped 182 for {}: {}", callId, e.getMessage());
                }
            }
            case RoutingDecision.Rejected rejected -> {
                if (pending.remove(callId) != null) {
                    tx.sendResponse(response(Response.BUSY_HERE, invite));
                }
            }
        }
    }

    private void handleBye(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        serverTransaction(event).sendResponse(messageFactory.createResponse(Response.OK, request));
        log.info("BYE {}", callId(request));
        router.onCallEnded(callId(request));
    }

    private void handleCancel(RequestEvent event) throws Exception {
        Request request = event.getRequest();
        String callId = callId(request);
        serverTransaction(event).sendResponse(messageFactory.createResponse(Response.OK, request));

        PendingInvite invite = pending.remove(callId);
        if (invite != null) {
            invite.tx().sendResponse(response(Response.REQUEST_TERMINATED, invite));
            router.onCallAbandoned(callId);
        }
        log.info("CANCEL {}", callId);
    }

    // ---------------------------------------------------------------- CallSignaling

    @Override
    public boolean answer(String callId, String agentId) {
        PendingInvite invite = pending.remove(callId);
        if (invite == null) {
            return false;
        }
        try {
            invite.tx().sendResponse(response(Response.RINGING, invite));

            Response ok = response(Response.OK, invite);
            ok.addHeader(contact);
            ok.addHeader(headerFactory.createHeader("X-Agent-Id", agentId));
            invite.tx().sendResponse(ok);
            return true;
        } catch (Exception e) {
            log.warn("Could not answer call {}", callId, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

    private ServerTransaction serverTransaction(RequestEvent event) throws SipException {
        ServerTransaction tx = event.getServerTransaction();
        return tx != null ? tx : provider.getNewServerTransaction(event.getRequest());
    }

    /** Response that carries our To-tag, so all responses to one INVITE belong to one dialog. */
    private Response response(int status, PendingInvite invite) throws ParseException {
        Response response = messageFactory.createResponse(status, invite.request());
        ((ToHeader) response.getHeader(ToHeader.NAME)).setTag(invite.toTag());
        return response;
    }

    private static String callId(Request request) {
        CallIdHeader header = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
        return header == null ? "?" : header.getCallId();
    }

    private static String newTag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------------------------------------------------------------- unused listener callbacks

    @Override
    public void processResponse(ResponseEvent event) {
        // we never send requests, so no responses to handle
    }

    @Override
    public void processTimeout(TimeoutEvent event) {
        log.debug("SIP transaction timeout");
    }

    @Override
    public void processIOException(IOExceptionEvent event) {
        log.warn("SIP I/O error to {}:{}", event.getHost(), event.getPort());
    }

    @Override
    public void processTransactionTerminated(TransactionTerminatedEvent event) {
    }

    @Override
    public void processDialogTerminated(DialogTerminatedEvent event) {
    }
}
