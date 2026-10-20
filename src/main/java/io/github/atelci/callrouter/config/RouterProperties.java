package io.github.atelci.callrouter.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * App settings bound from the {@code callrouter.*} section of application.yml.
 *
 * @param sip          SIP listener settings
 * @param maxQueueSize calls allowed to wait for an agent; the next caller gets 486 Busy Here
 * @param agents       agent id -> skills, seeded as AVAILABLE on startup (demo data)
 */
@ConfigurationProperties(prefix = "callrouter")
public record RouterProperties(Sip sip, int maxQueueSize, Map<String, List<String>> agents) {

    /**
     * @param bindHost      interface the SIP stack listens on
     * @param advertiseHost host put in the Contact header of our responses
     * @param port          UDP port (5060 is the SIP default)
     */
    public record Sip(String bindHost, String advertiseHost, int port) {
    }
}
