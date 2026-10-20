package io.github.atelci.callrouter.simulator;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One simulated customer: a minimal SIP user agent client (UAC) on its own UDP socket, the Java twin
 * of a caller in {@code simulator/callgen.py}. It sends real SIP packets to the router's UDP port,
 * so the router cannot tell it apart from an external phone.
 *
 * <pre>
 * INVITE  -> 100 Trying, maybe 182 Queued, then 180 Ringing + 200 OK  -> ACK, talk, BYE -> 200 OK
 *         -> 486 Busy Here                                             -> ACK, give up
 *         -> still queued after "patience" seconds                     -> CANCEL -> 200 OK + 487, ACK
 * </pre>
 */
class SimulatedCaller implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCaller.class);

    /** The parts of a SIP response this caller cares about. */
    private record SipResponse(int status, String reason, Map<String, String> headers) {

        static SipResponse parse(String text) {
            String[] lines = text.split("\r\n\r\n", 2)[0].split("\r\n");
            String[] statusLine = lines[0].split(" ", 3);
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.putIfAbsent(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                            lines[i].substring(colon + 1).trim());
                }
            }
            return new SipResponse(Integer.parseInt(statusLine[1]), statusLine.length > 2 ? statusLine[2] : "", headers);
        }

        String cseqMethod() {
            String[] cseq = headers.getOrDefault("cseq", "0 ?").split(" ");
            return cseq[cseq.length - 1];
        }

        String toTag() {
            String to = headers.getOrDefault("to", "");
            int i = to.indexOf("tag=");
            return i < 0 ? null : to.substring(i + 4).split(";")[0];
        }
    }

    private final int n;
    private final String dialed;
    private final InetSocketAddress router;
    private final double talkSeconds;
    private final double patienceSeconds;

    private final String callId = UUID.randomUUID().toString().substring(0, 12) + "@ui-sim";
    private final String fromTag = newId(8);
    private final String inviteBranch = branch();
    private final String requestUri;

    private DatagramSocket socket;
    private String localHost;
    private int localPort;

    SimulatedCaller(int n, String dialed, InetSocketAddress router, double talkSeconds, double patienceSeconds) {
        this.n = n;
        this.dialed = dialed;
        this.router = router;
        this.talkSeconds = talkSeconds;
        this.patienceSeconds = patienceSeconds;
        this.requestUri = "sip:" + dialed + "@" + router.getHostString() + ":" + router.getPort();
    }

    @Override
    public void run() {
        try (DatagramSocket s = new DatagramSocket()) {
            socket = s;
            socket.connect(router);   // picks the right local address + port
            localHost = socket.getLocalAddress().getHostAddress();
            localPort = socket.getLocalPort();
            call();
        } catch (Exception e) {   // keep one broken call from affecting the others
            log.warn("sim caller {} failed: {}", n, e.toString());
        }
    }

    private void call() throws IOException, InterruptedException {
        long start = System.nanoTime();
        send("INVITE", requestUri, inviteBranch, 1, null);

        boolean cancelled = false;
        while (true) {
            double remaining = patienceSeconds - (System.nanoTime() - start) / 1e9;
            if (remaining <= 0 && !cancelled) {
                send("CANCEL", requestUri, inviteBranch, 1, null);
                cancelled = true;
            }
            SipResponse resp = receive(cancelled ? 5 : remaining);
            if (resp == null) {
                if (cancelled) {
                    return;   // no answer to our CANCEL; give up quietly
                }
                continue;
            }
            if (resp.cseqMethod().equals("CANCEL") || resp.status() < 200) {
                continue;     // 200 OK for the CANCEL itself, or 100 / 180 / 182 provisional responses
            }
            if (resp.status() < 300) {
                talk(resp);
                return;
            }
            // final failure (486 Busy Here, 487 Request Terminated): the ACK reuses the INVITE branch
            send("ACK", requestUri, inviteBranch, 1, resp.toTag());
            return;
        }
    }

    private void talk(SipResponse ok) throws IOException, InterruptedException {
        String toTag = ok.toTag();
        String contact = ok.headers().getOrDefault("contact", "").replaceAll("[<>]", "");
        String target = contact.isEmpty() ? requestUri : contact;

        // the ACK for a 2xx is its own transaction: new branch, sent to the Contact
        send("ACK", target, branch(), 1, toTag);
        Thread.sleep((long) (talkSeconds * 1000));
        send("BYE", target, branch(), 2, toTag);

        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            SipResponse resp = receive((deadline - System.nanoTime()) / 1e9);
            if (resp == null || resp.cseqMethod().equals("BYE")) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ SIP plumbing

    private void send(String method, String uri, String branch, int cseq, String toTag) throws IOException {
        String to = "<sip:" + dialed + "@" + router.getHostString() + ">" + (toTag == null ? "" : ";tag=" + toTag);
        String message = String.join("\r\n",
                method + " " + uri + " SIP/2.0",
                "Via: SIP/2.0/UDP " + localHost + ":" + localPort + ";branch=" + branch + ";rport",
                "Max-Forwards: 70",
                "From: \"Sim " + n + "\" <sip:sim" + n + "@" + localHost + ">;tag=" + fromTag,
                "To: " + to,
                "Call-ID: " + callId,
                "CSeq: " + cseq + " " + method,
                "Contact: <sip:sim" + n + "@" + localHost + ":" + localPort + ">",
                "User-Agent: sip-call-router UI simulator",
                "Content-Length: 0",
                "",
                "");
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(bytes, bytes.length));
    }

    private SipResponse receive(double timeoutSeconds) throws IOException {
        socket.setSoTimeout((int) Math.max(10, timeoutSeconds * 1000));
        byte[] buffer = new byte[65535];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
            return SipResponse.parse(new String(buffer, 0, packet.getLength(), StandardCharsets.UTF_8));
        } catch (SocketTimeoutException e) {
            return null;
        }
    }

    private static String branch() {
        return "z9hG4bK" + newId(16);   // magic cookie required by RFC 3261
    }

    private static String newId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }
}
