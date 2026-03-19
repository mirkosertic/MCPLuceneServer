package de.mirkosertic.mcp.luceneserver.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for HTTP transport.
 * Reads system properties to configure host, port, and endpoint.
 */
public record HttpTransportConfig(String host, int port, String endpoint) {

    private static final Logger logger = LoggerFactory.getLogger(HttpTransportConfig.class);

    private static final String DEFAULT_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_ENDPOINT = "/mcp/message";

    /**
     * Create configuration from system properties.
     */
    public static HttpTransportConfig fromSystemProperties() {
        final String host = System.getProperty("mcp.http.host", DEFAULT_HOST);
        final int port = Integer.parseInt(System.getProperty("mcp.http.port", String.valueOf(DEFAULT_PORT)));
        final String endpoint = System.getProperty("mcp.http.endpoint", DEFAULT_ENDPOINT);

        logger.info("HTTP transport configuration: host={}, port={}, endpoint={}", host, port, endpoint);

        return new HttpTransportConfig(host, port, endpoint);
    }

    /**
     * Get the full URL for the HTTP endpoint.
     */
    public String getUrl() {
        return String.format("http://%s:%d%s", host, port, endpoint);
    }
}
