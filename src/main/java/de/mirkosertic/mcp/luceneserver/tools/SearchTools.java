package de.mirkosertic.mcp.luceneserver.tools;

import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.index.QueryRuntimeStats;
import de.mirkosertic.mcp.luceneserver.mcp.SchemaGenerator;
import de.mirkosertic.mcp.luceneserver.mcp.ToolResultHelper;
import de.mirkosertic.mcp.luceneserver.mcp.dto.GetTopTermsRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.GetTopTermsResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SuggestTermsRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SuggestTermsResponse;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.lucene.queryparser.classic.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP tools for search operations: search, profileQuery, suggestTerms, getTopTerms.
 */
public class SearchTools implements McpToolProvider {

    private static final Logger logger = LoggerFactory.getLogger(SearchTools.class);

    private static final String SEARCH_DESCRIPTION =
            "Search documents using Lucene query syntax with Snowball stemming for German and English " +
            "(morphological variants found automatically, e.g. 'Vertrag' matches 'Verträge'/'Vertrages'). Exact matches rank highest.\n\n" +
            "SYNTAX:\n" +
            "- Terms: 'hello world' → AND (implicit); phrases: \"exact phrase\"\n" +
            "- Boolean: AND, OR, NOT, grouping with ()\n" +
            "- Wildcards: word* (suffix, BM25 if >=4 chars prefix), ? (single char), *word (leading, fast via reversed field), *word* (contains, constant score)\n" +
            "- Fuzzy: term~2; Proximity: \"phrase\"~5\n" +
            "- Multi-word phrases auto-expanded: \"Domain Design\" → exact(2x boost) OR near(slop=3)\n" +
            "- Field-specific: field:value\n\n" +
            "FILTERS (metadata, use 'filters' array):\n" +
            "- operators: eq (default), in, not, not_in, range\n" +
            "- faceted fields (DrillSideways, OR within same field): language, file_extension, file_type, author\n" +
            "- string fields (exact match): file_path, content_hash\n" +
            "- numeric/date fields (range supported): file_size, created_date, modified_date, indexed_date\n" +
            "- dates: ISO-8601 (2024-01-15 / 2024-01-15T10:30:00 / 2024-01-15T10:30:00Z)\n" +
            "- logic: different fields=AND, same faceted field=OR, not/not_in=MUST_NOT\n\n" +
            "SORT: sortBy=_score (default), modified_date, created_date, file_size; sortOrder=desc (default), asc\n\n" +
            "BEST PRACTICES:\n" +
            "- Synonyms: use OR — (contract OR agreement), not automatic\n" +
            "- German compounds: use *vertrag (finds Arbeitsvertrag, Mietvertrag, etc.)\n" +
            "- Multilingual: (contract OR Vertrag OR contrat)\n" +
            "- ICU folding active: Müller≈Muller, café≈cafe\n" +
            "- Irregular verbs: use OR — (run OR running OR ran)\n\n" +
            "NOT SUPPORTED: automatic synonyms, phonetic matching, semantic search, stemming for non-DE/EN languages";

    private final LuceneIndexService indexService;
    private final QueryRuntimeStats queryRuntimeStats;

    public SearchTools(final LuceneIndexService indexService, final QueryRuntimeStats queryRuntimeStats) {
        this.indexService = indexService;
        this.queryRuntimeStats = queryRuntimeStats;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        // Search tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("search")
                        .description(SEARCH_DESCRIPTION)
                        .inputSchema(SchemaGenerator.generateSchema(SearchRequest.class))
                        .build())
                .callHandler((exchange, request) -> search(request.arguments()))
                .build());

        // Profile query tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("profileQuery")
                        .description("Debug and optimize search queries. Analyzes query structure, scoring, filter impact, and performance.\n\n" +
                                "ANALYSIS LEVELS (cumulative):\n" +
                                "- Level 1 (always, ~5-10ms): query structure, rewrites (e.g. *vertrag→reversed field), term stats (df/IDF/rarity), search metrics, cost estimates per clause\n" +
                                "- Level 2 (analyzeFilterImpact=true, ~50-200ms): per-filter selectivity (low/medium/high/very high) and timing; costs N+1 queries\n" +
                                "- Level 3 (analyzeDocumentScoring=true, ~100-300ms): BM25 breakdown per top doc, term contribution%; maxDocExplanations default=5 (max 10)\n" +
                                "- Level 4 (analyzeFacetCost=true, ~20-50ms): facet computation overhead per dimension; costs 2 queries\n" +
                                "- All levels combined: ~200-500ms\n\n" +
                                "Returns structured output with actionable recommendations. " +
                                "Start with Level 1 (fast, often sufficient). Enable deeper levels only for specific debugging: Level 2 for filter tuning, Level 3 for ranking issues.\n\n" +
                                "LIMITATIONS: explains only matched documents; wildcard internals opaque; no passage-level scoring")
                        .inputSchema(SchemaGenerator.generateSchema(ProfileQueryRequest.class))
                        .build())
                .callHandler((exchange, request) -> profileQuery(request.arguments()))
                .build());

        // Suggest terms tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("suggestTerms")
                        .description("Suggest index terms matching a prefix. Useful for discovering vocabulary, " +
                                "finding German compound words (prefix on 'content'), exploring author names, " +
                                "or auto-completing field values. Returns terms sorted by document frequency. " +
                                "For analyzed fields (content, title, etc.), the prefix is automatically lowercased to match indexed tokens.")
                        .inputSchema(SchemaGenerator.generateSchema(SuggestTermsRequest.class))
                        .build())
                .callHandler((exchange, request) -> suggestTerms(request.arguments()))
                .build());

        // Get top terms tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getTopTerms")
                        .description("Get the most frequent terms in a field. Useful for understanding index vocabulary, " +
                                "discovering common values (languages, file types, authors), and identifying dominant terms in content. " +
                                "Returns terms sorted by document frequency. " +
                                "Warning: On large content fields this enumerates all terms — use suggestTerms with a prefix for targeted exploration.")
                        .inputSchema(SchemaGenerator.generateSchema(GetTopTermsRequest.class))
                        .build())
                .callHandler((exchange, request) -> getTopTerms(request.arguments()))
                .build());

        return tools;
    }

    private McpSchema.CallToolResult search(final Map<String, Object> args) {
        final SearchRequest request = SearchRequest.fromMap(args);
        final List<SearchFilter> effectiveFilters = request.effectiveFilters();

        // Validate sort parameters
        String sortError = SearchRequest.validateSortBy(request.sortBy());
        if (sortError != null) {
            logger.warn("Invalid sortBy parameter: {}", sortError);
            return ToolResultHelper.createResult(SearchResponse.error(sortError));
        }
        sortError = SearchRequest.validateSortOrder(request.sortOrder());
        if (sortError != null) {
            logger.warn("Invalid sortOrder parameter: {}", sortError);
            return ToolResultHelper.createResult(SearchResponse.error(sortError));
        }

        logger.info("Search request: query='{}', filters={}, page={}, pageSize={}, sortBy={}, sortOrder={}",
                request.query(), effectiveFilters.size(), request.page(), request.pageSize(),
                request.effectiveSortBy(), request.effectiveSortOrder());

        try {
            final long startTime = System.nanoTime();
            final LuceneIndexService.SearchResult result = indexService.search(
                    request.effectiveQuery(),
                    effectiveFilters,
                    request.effectivePage(),
                    request.effectivePageSize(),
                    request.effectiveSortBy(),
                    request.effectiveSortOrder(),
                    LuceneIndexService.QueryMode.EXTENDED);
            final long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            queryRuntimeStats.recordQuery(durationMs, result.totalHits(),
                    result.facetTotalDurationMicros(), result.facetFieldDurationMicros());

            // Convert facets to DTO format
            final Map<String, List<SearchResponse.FacetValue>> facets = result.facets().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().stream()
                                    .map(fv -> new SearchResponse.FacetValue(fv.value(), fv.count()))
                                    .collect(Collectors.toList())
                    ));

            final SearchResponse response = SearchResponse.success(
                    result.documents(),
                    result.totalHits(),
                    result.page(),
                    result.pageSize(),
                    result.totalPages(),
                    result.hasNextPage(),
                    result.hasPreviousPage(),
                    facets,
                    result.activeFilters(),
                    durationMs
            );

            logger.info("Search completed in {}ms: {} total hits, returning page {} of {}, {} facet dimensions, {} active filters",
                    durationMs, result.totalHits(), result.page(), result.totalPages(),
                    result.facets().size(), result.activeFilters().size());

            return ToolResultHelper.createResult(response);

        } catch (final IllegalArgumentException e) {
            logger.warn("Invalid filter: {}", e.getMessage());
            return ToolResultHelper.createResult(SearchResponse.error("Invalid filter: " + e.getMessage()));
        } catch (final ParseException e) {
            logger.warn("Invalid query syntax: {}", e.getMessage());
            return ToolResultHelper.createResult(SearchResponse.error("Invalid query syntax: " + e.getMessage()));
        } catch (final IOException e) {
            logger.error("Search error", e);
            return ToolResultHelper.createResult(SearchResponse.error("Search error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult profileQuery(final Map<String, Object> args) {
        final ProfileQueryRequest request = ProfileQueryRequest.fromMap(args);

        logger.info("Profile query request: query='{}', analyzeFilterImpact={}, analyzeDocScoring={}, analyzeFacetCost={}",
                request.query(), request.effectiveAnalyzeFilterImpact(),
                request.effectiveAnalyzeDocumentScoring(), request.effectiveAnalyzeFacetCost());

        try {
            final ProfileQueryResponse response = indexService.profileQuery(request);
            return ToolResultHelper.createResult(response);
        } catch (final ParseException e) {
            logger.warn("Invalid query syntax: {}", e.getMessage());
            return ToolResultHelper.createResult(ProfileQueryResponse.error("Invalid query syntax: " + e.getMessage()));
        } catch (final IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ToolResultHelper.createResult(ProfileQueryResponse.error("Invalid request: " + e.getMessage()));
        } catch (final IOException e) {
            logger.error("Profile query error", e);
            return ToolResultHelper.createResult(ProfileQueryResponse.error("Profile query error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult suggestTerms(final Map<String, Object> args) {
        final SuggestTermsRequest request = SuggestTermsRequest.fromMap(args);

        logger.info("Suggest terms request: field='{}', prefix='{}', limit={}", request.field(), request.prefix(), request.effectiveLimit());

        // Validate field
        final String fieldError = indexService.validateTermField(request.field());
        if (fieldError != null) {
            logger.warn("Invalid field for suggestTerms: {}", fieldError);
            return ToolResultHelper.createResult(SuggestTermsResponse.error(fieldError));
        }

        // Validate prefix
        if (request.prefix() == null || request.prefix().isBlank()) {
            logger.warn("Prefix is required for suggestTerms");
            return ToolResultHelper.createResult(SuggestTermsResponse.error("Prefix is required"));
        }

        try {
            final LuceneIndexService.TermSuggestionResult result = indexService.suggestTerms(
                    request.field(), request.prefix(), request.effectiveLimit());

            final List<SuggestTermsResponse.TermFrequency> terms = result.terms().stream()
                    .map(e -> new SuggestTermsResponse.TermFrequency(e.getKey(), e.getValue()))
                    .toList();

            logger.info("Suggest terms completed: {} terms returned, {} total matched", terms.size(), result.totalMatched());

            return ToolResultHelper.createResult(SuggestTermsResponse.success(
                    request.field(), request.prefix(), terms, result.totalMatched()));

        } catch (final IOException e) {
            logger.error("Error suggesting terms", e);
            return ToolResultHelper.createResult(SuggestTermsResponse.error("Error suggesting terms: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getTopTerms(final Map<String, Object> args) {
        final GetTopTermsRequest request = GetTopTermsRequest.fromMap(args);

        logger.info("Get top terms request: field='{}', limit={}", request.field(), request.effectiveLimit());

        // Validate field
        final String fieldError = indexService.validateTermField(request.field());
        if (fieldError != null) {
            logger.warn("Invalid field for getTopTerms: {}", fieldError);
            return ToolResultHelper.createResult(GetTopTermsResponse.error(fieldError));
        }

        try {
            final LuceneIndexService.TopTermsResult result = indexService.getTopTerms(
                    request.field(), request.effectiveLimit());

            final List<GetTopTermsResponse.TermFrequency> terms = result.terms().stream()
                    .map(e -> new GetTopTermsResponse.TermFrequency(e.getKey(), e.getValue()))
                    .toList();

            logger.info("Get top terms completed: {} terms returned, {} unique terms in field", terms.size(), result.uniqueTermCount());

            return ToolResultHelper.createResult(GetTopTermsResponse.success(
                    request.field(), terms, result.uniqueTermCount(), result.warning()));

        } catch (final IOException e) {
            logger.error("Error getting top terms", e);
            return ToolResultHelper.createResult(GetTopTermsResponse.error("Error getting top terms: " + e.getMessage()));
        }
    }
}
