package de.mirkosertic.mcp.luceneserver.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;

import java.util.List;

/**
 * Custom STDIO transport provider that advertises support for the latest MCP protocol versions.
 * <p>
 * The default {@link StdioServerTransportProvider} only advertises support for the
 * {@code 2024-11-05} protocol version. This custom implementation advertises support
 * for newer versions to enable the latest MCP features.
 * <p>
 * Protocol versions are listed in order of preference (newest first).
 */
public class LatestProtocolStdioServerTransportProvider extends StdioServerTransportProvider {

    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
            ProtocolVersions.MCP_2025_06_18,
            ProtocolVersions.MCP_2025_03_26,
            ProtocolVersions.MCP_2024_11_05
    );

    /**
     * Creates a new transport provider with the specified JSON mapper.
     *
     * @param jsonMapper The JSON mapper for serialization/deserialization
     */
    public LatestProtocolStdioServerTransportProvider(final McpJsonMapper jsonMapper) {
        super(jsonMapper);
    }

    /**
     * Returns the list of supported protocol versions.
     * <p>
     * This implementation advertises support for:
     * <ul>
     *   <li>{@code 2025-06-18} - Latest protocol version</li>
     *   <li>{@code 2025-03-26} - March 2025 version</li>
     *   <li>{@code 2024-11-05} - Original stable version (fallback)</li>
     * </ul>
     *
     * @return list of supported protocol versions, newest first
     */
    @Override
    public List<String> protocolVersions() {
        return SUPPORTED_PROTOCOL_VERSIONS;
    }
}
