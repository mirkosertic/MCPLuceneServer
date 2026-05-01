package de.mirkosertic.mcp.luceneserver.mcp.dto;

import de.mirkosertic.mcp.luceneserver.mcp.Description;

import java.util.List;

public record SearchState(
    @Description("Query string used in the current search")
    String query,
    @Description("Active filters in the current search")
    List<SearchFilter> filters,
    @Description("Current page (0-based)")
    int page,
    @Description("Results per page")
    int pageSize
) {}
