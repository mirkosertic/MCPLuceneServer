package de.mirkosertic.mcp.luceneserver.transport;

/**
 * Enumeration of supported MCP transport types.
 */
public enum TransportType {
    /**
     * Standard I/O transport (default) - for use with Claude Desktop and other MCP clients.
     */
    STDIO,

    /**
     * HTTP transport - for web-based clients and remote access.
     */
    HTTP
}
