package de.mirkosertic.mcp.luceneserver.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mirkosertic.mcp.luceneserver.mcp.Description;
import org.jspecify.annotations.Nullable;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchAction(
    @Description("Action type: nextPage | prevPage | drillDown | fetchContent")
    String type,
    @Description("Name of the MCP tool to call")
    String tool,
    @Description("Complete, ready-to-use parameters for the tool call — pass directly without modification")
    Map<String, Object> parameters,
    @Nullable
    @Description("Expected result count — only present for drillDown actions")
    Long hits
) {}
