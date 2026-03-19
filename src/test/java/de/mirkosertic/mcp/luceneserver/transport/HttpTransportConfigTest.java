package de.mirkosertic.mcp.luceneserver.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpTransportConfig}.
 */
class HttpTransportConfigTest {

    private Map<String, String> originalProperties;

    @BeforeEach
    void setUp() {
        // Save original system properties to restore later
        originalProperties = new HashMap<>();
        saveProperty("mcp.http.host");
        saveProperty("mcp.http.port");
        saveProperty("mcp.http.endpoint");
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
        System.clearProperty("mcp.http.host");
        System.clearProperty("mcp.http.port");
        System.clearProperty("mcp.http.endpoint");

        // Restore original values
        originalProperties.forEach(System::setProperty);
    }

    @Test
    void testFromSystemProperties_withDefaults() {
        // Given: No system properties set

        // When
        final HttpTransportConfig config = HttpTransportConfig.fromSystemProperties();

        // Then
        assertEquals("0.0.0.0", config.host());
        assertEquals(8080, config.port());
        assertEquals("/mcp/message", config.endpoint());
    }

    @Test
    void testFromSystemProperties_withCustomHost() {
        // Given
        System.setProperty("mcp.http.host", "localhost");

        // When
        final HttpTransportConfig config = HttpTransportConfig.fromSystemProperties();

        // Then
        assertEquals("localhost", config.host());
        assertEquals(8080, config.port());
        assertEquals("/mcp/message", config.endpoint());
    }

    @Test
    void testFromSystemProperties_withCustomPort() {
        // Given
        System.setProperty("mcp.http.port", "9090");

        // When
        final HttpTransportConfig config = HttpTransportConfig.fromSystemProperties();

        // Then
        assertEquals("0.0.0.0", config.host());
        assertEquals(9090, config.port());
        assertEquals("/mcp/message", config.endpoint());
    }

    @Test
    void testFromSystemProperties_withCustomEndpoint() {
        // Given
        System.setProperty("mcp.http.endpoint", "/api/mcp");

        // When
        final HttpTransportConfig config = HttpTransportConfig.fromSystemProperties();

        // Then
        assertEquals("0.0.0.0", config.host());
        assertEquals(8080, config.port());
        assertEquals("/api/mcp", config.endpoint());
    }

    @Test
    void testFromSystemProperties_withAllCustomValues() {
        // Given
        System.setProperty("mcp.http.host", "192.168.1.100");
        System.setProperty("mcp.http.port", "3000");
        System.setProperty("mcp.http.endpoint", "/custom/endpoint");

        // When
        final HttpTransportConfig config = HttpTransportConfig.fromSystemProperties();

        // Then
        assertEquals("192.168.1.100", config.host());
        assertEquals(3000, config.port());
        assertEquals("/custom/endpoint", config.endpoint());
    }

    @Test
    void testFromSystemProperties_withInvalidPort() {
        // Given
        System.setProperty("mcp.http.port", "invalid");

        // When/Then
        assertThrows(NumberFormatException.class, HttpTransportConfig::fromSystemProperties);
    }

    @Test
    void testFromSystemProperties_withNegativePort() {
        // Given
        System.setProperty("mcp.http.port", "-1");

        // When
        final HttpTransportConfig config = HttpTransportConfig.fromSystemProperties();

        // Then
        assertEquals(-1, config.port());
    }

    @Test
    void testFromSystemProperties_withPortZero() {
        // Given
        System.setProperty("mcp.http.port", "0");

        // When
        final HttpTransportConfig config = HttpTransportConfig.fromSystemProperties();

        // Then
        assertEquals(0, config.port());
    }

    @Test
    void testFromSystemProperties_withMaxPort() {
        // Given
        System.setProperty("mcp.http.port", "65535");

        // When
        final HttpTransportConfig config = HttpTransportConfig.fromSystemProperties();

        // Then
        assertEquals(65535, config.port());
    }

    @Test
    void testGetUrl_withDefaults() {
        // Given
        final HttpTransportConfig config = new HttpTransportConfig("0.0.0.0", 8080, "/mcp/message");

        // When
        final String url = config.getUrl();

        // Then
        assertEquals("http://0.0.0.0:8080/mcp/message", url);
    }

    @Test
    void testGetUrl_withCustomValues() {
        // Given
        final HttpTransportConfig config = new HttpTransportConfig("localhost", 9090, "/api/mcp");

        // When
        final String url = config.getUrl();

        // Then
        assertEquals("http://localhost:9090/api/mcp", url);
    }

    @Test
    void testGetUrl_withIPv4Address() {
        // Given
        final HttpTransportConfig config = new HttpTransportConfig("192.168.1.100", 8080, "/mcp");

        // When
        final String url = config.getUrl();

        // Then
        assertEquals("http://192.168.1.100:8080/mcp", url);
    }

    @Test
    void testGetUrl_withEndpointWithoutLeadingSlash() {
        // Given
        final HttpTransportConfig config = new HttpTransportConfig("localhost", 8080, "mcp/message");

        // When
        final String url = config.getUrl();

        // Then
        // Note: The implementation doesn't add a leading slash if missing
        assertEquals("http://localhost:8080mcp/message", url);
    }

    @Test
    void testGetUrl_withRootEndpoint() {
        // Given
        final HttpTransportConfig config = new HttpTransportConfig("localhost", 8080, "/");

        // When
        final String url = config.getUrl();

        // Then
        assertEquals("http://localhost:8080/", url);
    }

    @Test
    void testConstructor() {
        // Given/When
        final HttpTransportConfig config = new HttpTransportConfig("test-host", 1234, "/test");

        // Then
        assertEquals("test-host", config.host());
        assertEquals(1234, config.port());
        assertEquals("/test", config.endpoint());
    }

    @Test
    void testGetUrl_withNonStandardPort() {
        // Given
        final HttpTransportConfig config = new HttpTransportConfig("example.com", 3000, "/api");

        // When
        final String url = config.getUrl();

        // Then
        assertEquals("http://example.com:3000/api", url);
    }
}
