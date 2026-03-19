package de.mirkosertic.mcp.luceneserver.transport;

import de.mirkosertic.mcp.luceneserver.mcp.LatestProtocolStdioServerTransportProvider;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Factory for creating MCP transport providers based on system properties.
 * Supports STDIO (default) and HTTP transports.
 */
public class TransportFactory {

    private static final Logger logger = LoggerFactory.getLogger(TransportFactory.class);

    private static final String TRANSPORT_PROPERTY = "mcp.transport";
    private static final String TRANSPORT_HTTP = "http";

    /**
     * Determine the transport type from system properties.
     */
    public static TransportType getTransportType() {
        final String transportValue = System.getProperty(TRANSPORT_PROPERTY, "stdio");

        if (TRANSPORT_HTTP.equalsIgnoreCase(transportValue)) {
            return TransportType.HTTP;
        }

        return TransportType.STDIO;
    }

    /**
     * Create a STDIO transport provider.
     */
    public static LatestProtocolStdioServerTransportProvider createStdioTransport(final JacksonMcpJsonMapper jsonMapper) {
        logger.info("Creating STDIO transport");
        return new LatestProtocolStdioServerTransportProvider(jsonMapper);
    }

    /**
     * Create an HTTP transport with Jetty embedded server using MCP SDK's streamable HTTP transport.
     * Returns a wrapper that includes the Jetty server and transport provider.
     */
    public static HttpTransportWrapper createHttpTransport(final JacksonMcpJsonMapper jsonMapper) {

        final HttpTransportConfig config = HttpTransportConfig.fromSystemProperties();

        logger.info("Creating HTTP transport on {}", config.getUrl());

        try {
            // Create SDK transport provider for streamable HTTP using builder
            final HttpServletStreamableServerTransportProvider transportProvider =
                    HttpServletStreamableServerTransportProvider.builder()
                            .jsonMapper(jsonMapper)
                            .mcpEndpoint(config.endpoint())
                            .build();

            // Create Jetty server
            final Server server = new Server(new InetSocketAddress(config.host(), config.port()));

            // Create servlet context
            final ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
            context.setContextPath("/");
            server.setHandler(context);

            // Register the transport provider servlet directly (it extends HttpServlet)
            final ServletHolder servletHolder = new ServletHolder(transportProvider);
            context.addServlet(servletHolder, "/*");

            return new HttpTransportWrapper(server, transportProvider, config);

        } catch (final Exception e) {
            throw new RuntimeException("Failed to create HTTP transport: " + e.getMessage(), e);
        }
    }

    /**
         * Wrapper class that holds the Jetty server and transport provider for HTTP transport.
         */
        public record HttpTransportWrapper(Server server, HttpServletStreamableServerTransportProvider transportProvider,
                                           HttpTransportConfig config) {

        /**
             * Start the Jetty server.
             */
            public void start() throws Exception {
                server.start();
                logger.info("HTTP server started on {}", config.getUrl());
            }

            /**
             * Stop the Jetty server.
             */
            public void stop() throws Exception {
                if (server != null) {
                    server.stop();
                    logger.info("HTTP server stopped");
                }
            }

            /**
             * Join the Jetty server thread (block until server stops).
             */
            public void join() throws InterruptedException {
                server.join();
            }
        }
}
