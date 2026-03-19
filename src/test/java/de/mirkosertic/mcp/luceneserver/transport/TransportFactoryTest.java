package de.mirkosertic.mcp.luceneserver.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.mirkosertic.mcp.luceneserver.mcp.LatestProtocolStdioServerTransportProvider;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TransportFactory}.
 */
class TransportFactoryTest {

    private Map<String, String> originalProperties;
    private JacksonMcpJsonMapper jsonMapper;

    @BeforeEach
    void setUp() {
        // Save original system properties to restore later
        originalProperties = new HashMap<>();
        saveProperty("mcp.transport");
        saveProperty("mcp.http.host");
        saveProperty("mcp.http.port");
        saveProperty("mcp.http.endpoint");

        // Create JSON mapper for transport creation
        jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        // Restore original system properties
        restoreProperties();
    }

    private void saveProperty(final String key) {
        final String value = System.getProperty(key);
        if (value != null) {
            originalProperties.put(key, value);
        }
    }

    private void restoreProperties() {
        // Clear all test properties first
        System.clearProperty("mcp.transport");
        System.clearProperty("mcp.http.host");
        System.clearProperty("mcp.http.port");
        System.clearProperty("mcp.http.endpoint");

        // Restore original values
        originalProperties.forEach(System::setProperty);
    }

    @Test
    void testGetTransportType_noPropertySet_returnsStdio() {
        // Given: No system property set

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.STDIO, transportType);
    }

    @Test
    void testGetTransportType_stdioPropertySet() {
        // Given
        System.setProperty("mcp.transport", "stdio");

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.STDIO, transportType);
    }

    @Test
    void testGetTransportType_httpPropertySet() {
        // Given
        System.setProperty("mcp.transport", "http");

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.HTTP, transportType);
    }

    @Test
    void testGetTransportType_httpUpperCase() {
        // Given
        System.setProperty("mcp.transport", "HTTP");

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.HTTP, transportType);
    }

    @Test
    void testGetTransportType_httpMixedCase() {
        // Given
        System.setProperty("mcp.transport", "Http");

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.HTTP, transportType);
    }

    @Test
    void testGetTransportType_stdioUpperCase() {
        // Given
        System.setProperty("mcp.transport", "STDIO");

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.STDIO, transportType);
    }

    @Test
    void testGetTransportType_stdioMixedCase() {
        // Given
        System.setProperty("mcp.transport", "Stdio");

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.STDIO, transportType);
    }

    @Test
    void testGetTransportType_invalidValue_returnsStdio() {
        // Given
        System.setProperty("mcp.transport", "invalid");

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.STDIO, transportType, "Invalid transport type should fall back to STDIO");
    }

    @Test
    void testGetTransportType_emptyValue_returnsStdio() {
        // Given
        System.setProperty("mcp.transport", "");

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.STDIO, transportType, "Empty transport type should fall back to STDIO");
    }

    @Test
    void testGetTransportType_whitespaceValue_returnsStdio() {
        // Given
        System.setProperty("mcp.transport", "   ");

        // When
        final TransportType transportType = TransportFactory.getTransportType();

        // Then
        assertEquals(TransportType.STDIO, transportType, "Whitespace transport type should fall back to STDIO");
    }

    @Test
    void testCreateStdioTransport() {
        // When
        final LatestProtocolStdioServerTransportProvider transport = TransportFactory.createStdioTransport(jsonMapper);

        // Then
        assertNotNull(transport);
        assertInstanceOf(LatestProtocolStdioServerTransportProvider.class, transport);
    }

    @Test
    void testCreateHttpTransport_withDefaultConfig() {
        // When
        final TransportFactory.HttpTransportWrapper wrapper = TransportFactory.createHttpTransport(jsonMapper);

        // Then
        assertNotNull(wrapper);
        assertNotNull(wrapper.server());
        assertNotNull(wrapper.transportProvider());
        assertNotNull(wrapper.config());

        // Verify config defaults
        assertEquals("0.0.0.0", wrapper.config().host());
        assertEquals(8080, wrapper.config().port());
        assertEquals("/mcp/message", wrapper.config().endpoint());
    }

    @Test
    void testCreateHttpTransport_withCustomConfig() {
        // Given
        System.setProperty("mcp.http.host", "localhost");
        System.setProperty("mcp.http.port", "9090");
        System.setProperty("mcp.http.endpoint", "/api/mcp");

        // When
        final TransportFactory.HttpTransportWrapper wrapper = TransportFactory.createHttpTransport(jsonMapper);

        // Then
        assertNotNull(wrapper);
        assertNotNull(wrapper.config());

        // Verify custom config
        assertEquals("localhost", wrapper.config().host());
        assertEquals(9090, wrapper.config().port());
        assertEquals("/api/mcp", wrapper.config().endpoint());
    }

    @Test
    void testHttpTransportWrapper_getServer() {
        // Given
        final TransportFactory.HttpTransportWrapper wrapper = TransportFactory.createHttpTransport(jsonMapper);

        // When
        final Server server = wrapper.server();

        // Then
        assertNotNull(server);
        assertInstanceOf(Server.class, server);
    }

    @Test
    void testHttpTransportWrapper_getTransportProvider() {
        // Given
        final TransportFactory.HttpTransportWrapper wrapper = TransportFactory.createHttpTransport(jsonMapper);

        // When
        final HttpServletStreamableServerTransportProvider provider = wrapper.transportProvider();

        // Then
        assertNotNull(provider);
        assertInstanceOf(HttpServletStreamableServerTransportProvider.class, provider);
    }

    @Test
    void testHttpTransportWrapper_getConfig() {
        // Given
        System.setProperty("mcp.http.host", "192.168.1.1");
        System.setProperty("mcp.http.port", "3000");
        System.setProperty("mcp.http.endpoint", "/test");

        final TransportFactory.HttpTransportWrapper wrapper = TransportFactory.createHttpTransport(jsonMapper);

        // When
        final HttpTransportConfig config = wrapper.config();

        // Then
        assertNotNull(config);
        assertEquals("192.168.1.1", config.host());
        assertEquals(3000, config.port());
        assertEquals("/test", config.endpoint());
    }

    @Test
    void testHttpTransportWrapper_start_stop() throws Exception {
        // Given
        System.setProperty("mcp.http.port", "0"); // Use port 0 to let OS assign a free port
        final TransportFactory.HttpTransportWrapper wrapper = TransportFactory.createHttpTransport(jsonMapper);

        try {
            // When - Start server
            wrapper.start();

            // Then - Server should be running
            assertTrue(wrapper.server().isStarted());

            // When - Stop server
            wrapper.stop();

            // Then - Server should be stopped
            assertTrue(wrapper.server().isStopped());
        } finally {
            // Cleanup
            if (wrapper.server().isStarted()) {
                wrapper.stop();
            }
        }
    }

    @Test
    void testHttpTransportWrapper_multipleStarts() throws Exception {
        // Given
        System.setProperty("mcp.http.port", "0");
        final TransportFactory.HttpTransportWrapper wrapper = TransportFactory.createHttpTransport(jsonMapper);

        try {
            // When
            wrapper.start();

            // Then - Server should be started
            assertTrue(wrapper.server().isStarted());

            // Note: Jetty may not throw an exception when starting an already started server
            // Instead, it just returns without doing anything
            // This is acceptable behavior, so we just verify the server is still started
            wrapper.start(); // This should be a no-op
            assertTrue(wrapper.server().isStarted());
        } finally {
            // Cleanup
            if (wrapper.server().isStarted()) {
                wrapper.stop();
            }
        }
    }

    @Test
    void testHttpTransportWrapper_stopWithoutStart() {
        // Given
        final TransportFactory.HttpTransportWrapper wrapper = TransportFactory.createHttpTransport(jsonMapper);

        // When/Then - Stopping a non-started server should not throw
        assertDoesNotThrow(wrapper::stop);
    }

    @Test
    void testCreateStdioTransport_withNullMapper() {
        // When/Then
        // The MCP SDK throws IllegalArgumentException for null JsonMapper
        assertThrows(IllegalArgumentException.class, () ->
                TransportFactory.createStdioTransport(null)
        );
    }

    @Test
    void testCreateHttpTransport_withNullMapper() {
        // When/Then
        // The MCP SDK throws IllegalArgumentException wrapped in RuntimeException
        assertThrows(RuntimeException.class, () ->
                TransportFactory.createHttpTransport(null)
        );
    }

    @Test
    void testCreateHttpTransport_withInvalidPort() {
        // Given
        System.setProperty("mcp.http.port", "invalid");

        // When/Then
        assertThrows(RuntimeException.class, () ->
                TransportFactory.createHttpTransport(jsonMapper)
        );
    }

    @Test
    void testHttpTransportWrapper_getUrl() {
        // Given
        System.setProperty("mcp.http.host", "test.example.com");
        System.setProperty("mcp.http.port", "8888");
        System.setProperty("mcp.http.endpoint", "/mcp/v1");

        final TransportFactory.HttpTransportWrapper wrapper = TransportFactory.createHttpTransport(jsonMapper);

        // When
        final String url = wrapper.config().getUrl();

        // Then
        assertEquals("http://test.example.com:8888/mcp/v1", url);
    }
}
