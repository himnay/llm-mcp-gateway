package com.org.llm.mcpgateway.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound backend URL checks applied by {@link UrlAllowlistValidator} at startup.
 *
 * <pre>
 * gateway:
 *   security:
 *     ssrf:
 *       block-private-networks: false
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "gateway.security.ssrf")
public class GatewaySsrfProperties {

    /**
     * Also reject loopback and private-range backends (127.0.0.0/8, 10/8, 172.16/12, 192.168/16,
     * fc00::/7). Off by default: MCP backends normally run on localhost or on a private
     * Docker/Kubernetes network. Turn it on when every backend is a public endpoint.
     */
    private boolean blockPrivateNetworks = false;
}
