package de.mirkosertic.mcp.luceneserver.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.List;

public interface McpToolProvider {
    List<McpServerFeatures.SyncToolSpecification> getToolSpecifications();
}
