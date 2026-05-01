package de.mirkosertic.mcp.luceneserver.tools;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.index.QueryRuntimeStats;
import de.mirkosertic.mcp.luceneserver.index.SemanticSearchResult;
import de.mirkosertic.mcp.luceneserver.mcp.SchemaGenerator;
import de.mirkosertic.mcp.luceneserver.mcp.ToolResultHelper;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ExtendedSearchRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.GetTopTermsRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.GetTopTermsResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileSemanticSearchRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileSemanticSearchResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SemanticSearchRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SimpleSearchRequest;
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
 * MCP tools for search operations: simpleSearch, extendedSearch, semanticSearch,
 * profileSemanticSearch, profileQuery, suggestTerms, getTopTerms.
 */
public class SearchTools implements McpToolProvider {

    private static final Logger logger = LoggerFactory.getLogger(SearchTools.class);

    private static final String SIMPLE_SEARCH_DESCRIPTION =
            """
                    Search documents using plain text keywords. Special characters are treated as literals (no Lucene syntax). \
                    Supports filters, pagination, and sorting. Uses BM25 with stemming for German and English.
                    
                    FILTERS: use 'filters' array with operators: eq, in, not, not_in, range
                    - faceted fields: language, file_extension, file_type, author
                    - date fields (ISO-8601): created_date, modified_date, indexed_date
                    - numeric: file_size
                    
                    SORT: sortBy=_score (default), modified_date, created_date, file_size; sortOrder=desc/asc""";

    private static final String EXTENDED_SEARCH_DESCRIPTION =
            """
                    Search documents using full Lucene query syntax. Supports Boolean operators (AND, OR, NOT), \
                    wildcards (*word, word*, *word*), fuzzy (~), proximity ("phrase"~5), field-specific queries, \
                    and phrase matching. Uses BM25 with stemming.
                    
                    SYNTAX: AND/OR/NOT, grouping (), phrases "...", wildcards *, ?, fuzzy~, proximity""~N, field:value
                    
                    FILTERS and SORT: same as simpleSearch""";

    private static final String SEMANTIC_SEARCH_DESCRIPTION =
            """
                    Search documents using natural language semantic similarity (pure KNN embedding search). \
                    Finds documents that are semantically related to the query even when exact keywords don't match. \
                    Results are ordered by cosine similarity — no BM25, no sort options.
                    
                    Requires VECTOR_MODEL to be configured.
                    
                    similarityThreshold (0.0–1.0, default 0.70): lower values return more results with broader semantic match; \
                    higher values return only closely matching documents.""";

    private static final String PROFILE_SEMANTIC_SEARCH_DESCRIPTION =
            """
                    Debug and profile semantic search queries. Shows embedding time, cosine scores, matched chunk text, \
                    and how many candidates passed the similarity threshold. Use this to tune similarityThreshold and \
                    understand why certain documents are or are not returned.
                    
                    Returns: embeddingDurationMs, rawCandidateCount, filteredCandidateCount, cosineCutoff, \
                    topCandidates (with cosineScore and matchedChunkText), and the actual search results.""";

    private final LuceneIndexService indexService;
    private final QueryRuntimeStats queryRuntimeStats;
    private final ApplicationConfig config;

    public SearchTools(final LuceneIndexService indexService, final QueryRuntimeStats queryRuntimeStats,
            final ApplicationConfig config) {
        this.indexService = indexService;
        this.queryRuntimeStats = queryRuntimeStats;
        this.config = config;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        // Simple search tool
        if (config.isToolActive("simpleSearch")) {
            logger.info("Exposing simpleSearch tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("simpleSearch")
                            .description(SIMPLE_SEARCH_DESCRIPTION)
                            .inputSchema(SchemaGenerator.generateSchema(SimpleSearchRequest.class))
                            .build())
                    .callHandler((exchange, request) -> simpleSearch(request.arguments()))
                    .build());
        }

        // Extended search tool
        if (config.isToolActive("extendedSearch")) {
            logger.info("Exposing extendedSearch tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("extendedSearch")
                            .description(EXTENDED_SEARCH_DESCRIPTION)
                            .inputSchema(SchemaGenerator.generateSchema(ExtendedSearchRequest.class))
                            .build())
                    .callHandler((exchange, request) -> extendedSearch(request.arguments()))
                    .build());
        }

        // Semantic search tool
        if (config.isToolActive("semanticSearch")) {
            logger.info("Exposing semanticSearch tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("semanticSearch")
                            .description(SEMANTIC_SEARCH_DESCRIPTION)
                            .inputSchema(SchemaGenerator.generateSchema(SemanticSearchRequest.class))
                            .build())
                    .callHandler((exchange, request) -> semanticSearch(request.arguments()))
                    .build());
        }

        // Profile semantic search tool
        if (config.isToolActive("profileSemanticSearch")) {
            logger.info("Exposing profileSemanticSearch tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("profileSemanticSearch")
                            .description(PROFILE_SEMANTIC_SEARCH_DESCRIPTION)
                            .inputSchema(SchemaGenerator.generateSchema(ProfileSemanticSearchRequest.class))
                            .build())
                    .callHandler((exchange, request) -> profileSemanticSearch(request.arguments()))
                    .build());
        }

        // Profile query tool
        if (config.isToolActive("profileQuery")) {
            logger.info("Exposing profileQuery tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("profileQuery")
                            .description("""
                                    Debug and optimize search queries. Analyzes query structure, scoring, filter impact, and performance.
                                    
                                    ANALYSIS LEVELS (cumulative):
                                    - Level 1 (always, ~5-10ms): query structure, rewrites (e.g. *vertrag→reversed field), term stats (df/IDF/rarity), search metrics, cost estimates per clause
                                    - Level 2 (analyzeFilterImpact=true, ~50-200ms): per-filter selectivity (low/medium/high/very high) and timing; costs N+1 queries
                                    - Level 3 (analyzeDocumentScoring=true, ~100-300ms): BM25 breakdown per top doc, term contribution%; maxDocExplanations default=5 (max 10)
                                    - Level 4 (analyzeFacetCost=true, ~20-50ms): facet computation overhead per dimension; costs 2 queries
                                    - All levels combined: ~200-500ms
                                    
                                    Returns structured output with actionable recommendations. \
                                    Start with Level 1 (fast, often sufficient). Enable deeper levels only for specific debugging: Level 2 for filter tuning, Level 3 for ranking issues.
                                    
                                    LIMITATIONS: explains only matched documents; wildcard internals opaque; no passage-level scoring""")
                            .inputSchema(SchemaGenerator.generateSchema(ProfileQueryRequest.class))
                            .build())
                    .callHandler((exchange, request) -> profileQuery(request.arguments()))
                    .build());
        }

        // Suggest terms tool
        if (config.isToolActive("suggestTerms")) {
            logger.info("Exposing suggestTerms tool");
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
        }

        // Get top terms tool
        if (config.isToolActive("getTopTerms")) {
            logger.info("Exposing getTopTerms tool");
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
        }

        return tools;
    }

    private McpSchema.CallToolResult simpleSearch(final Map<String, Object> args) {
        final SimpleSearchRequest request = SimpleSearchRequest.fromMap(args);
        final List<SearchFilter> effectiveFilters = request.effectiveFilters();

        // Validate sort parameters
        String sortError = SimpleSearchRequest.validateSortBy(request.sortBy());
        if (sortError != null) {
            logger.warn("Invalid sortBy parameter: {}", sortError);
            return ToolResultHelper.createResult(SearchResponse.error(sortError));
        }
        sortError = SimpleSearchRequest.validateSortOrder(request.sortOrder());
        if (sortError != null) {
            logger.warn("Invalid sortOrder parameter: {}", sortError);
            return ToolResultHelper.createResult(SearchResponse.error(sortError));
        }

        logger.info("SimpleSearch request: query='{}', filters={}, page={}, pageSize={}, sortBy={}, sortOrder={}",
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
                    LuceneIndexService.QueryMode.SIMPLE);
            final long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            queryRuntimeStats.recordQuery(durationMs, result.totalHits(),
                    result.facetTotalDurationMicros(), result.facetFieldDurationMicros());

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
                    durationMs,
                    null,
                    null
            );

            logger.info("SimpleSearch completed in {}ms: {} total hits, returning page {} of {}, {} facet dimensions, {} active filters",
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

    private McpSchema.CallToolResult extendedSearch(final Map<String, Object> args) {
        final ExtendedSearchRequest request = ExtendedSearchRequest.fromMap(args);
        final List<SearchFilter> effectiveFilters = request.effectiveFilters();

        // Validate sort parameters
        String sortError = ExtendedSearchRequest.validateSortBy(request.sortBy());
        if (sortError != null) {
            logger.warn("Invalid sortBy parameter: {}", sortError);
            return ToolResultHelper.createResult(SearchResponse.error(sortError));
        }
        sortError = ExtendedSearchRequest.validateSortOrder(request.sortOrder());
        if (sortError != null) {
            logger.warn("Invalid sortOrder parameter: {}", sortError);
            return ToolResultHelper.createResult(SearchResponse.error(sortError));
        }

        logger.info("ExtendedSearch request: query='{}', filters={}, page={}, pageSize={}, sortBy={}, sortOrder={}",
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
                    durationMs,
                    null,
                    null
            );

            logger.info("ExtendedSearch completed in {}ms: {} total hits, returning page {} of {}, {} facet dimensions, {} active filters",
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

    private McpSchema.CallToolResult semanticSearch(final Map<String, Object> args) {
        final SemanticSearchRequest request = SemanticSearchRequest.fromMap(args);
        final String thresholdError = request.validateSimilarityThreshold();
        if (thresholdError != null) {
            logger.warn("Invalid similarityThreshold: {}", thresholdError);
            return ToolResultHelper.createResult(SearchResponse.error(thresholdError));
        }

        logger.info("SemanticSearch request: query='{}', filters={}, page={}, pageSize={}, threshold={}",
                request.query(), request.effectiveFilters().size(), request.effectivePage(),
                request.effectivePageSize(), request.effectiveSimilarityThreshold());

        try {
            final SemanticSearchResult result = indexService.semanticSearch(
                    request.effectiveQuery() != null ? request.effectiveQuery() : "",
                    request.effectiveFilters(),
                    request.effectivePage(),
                    request.effectivePageSize(),
                    request.effectiveSimilarityThreshold());

            final SearchResponse response = SearchResponse.success(
                    result.documents(),
                    result.totalHits(),
                    result.page(),
                    result.pageSize(),
                    result.totalPages(),
                    result.hasNextPage(),
                    result.hasPreviousPage(),
                    Map.of(),
                    List.of(),
                    result.embeddingDurationMs(),
                    null,
                    null
            );

            logger.info("SemanticSearch completed in {}ms: {} total hits (above threshold={})",
                    result.embeddingDurationMs(), result.totalHits(), request.effectiveSimilarityThreshold());

            return ToolResultHelper.createResult(response);

        } catch (final IllegalStateException e) {
            logger.warn("Semantic search not available: {}", e.getMessage());
            return ToolResultHelper.createResult(SearchResponse.error(e.getMessage()));
        } catch (final IOException e) {
            logger.error("Semantic search error", e);
            return ToolResultHelper.createResult(SearchResponse.error("Semantic search failed: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult profileSemanticSearch(final Map<String, Object> args) {
        final ProfileSemanticSearchRequest request = ProfileSemanticSearchRequest.fromMap(args);

        logger.info("ProfileSemanticSearch request: query='{}', filters={}, threshold={}",
                request.query(), request.effectiveFilters().size(), request.effectiveSimilarityThreshold());

        try {
            final LuceneIndexService.SemanticSearchWithDebugResult debugResult =
                    indexService.semanticSearchWithDebug(
                            request.query() != null ? request.query() : "",
                            request.effectiveFilters(),
                            0,
                            10,
                            request.effectiveSimilarityThreshold());

            final ProfileSemanticSearchResponse response = ProfileSemanticSearchResponse.success(debugResult);

            logger.info("ProfileSemanticSearch completed in {}ms: rawCandidates={}, filtered={}",
                    debugResult.searchResult().embeddingDurationMs(),
                    debugResult.searchResult().rawCandidateCount(),
                    (int) debugResult.searchResult().totalHits());

            return ToolResultHelper.createResult(response);

        } catch (final IllegalStateException e) {
            logger.warn("Semantic search not available: {}", e.getMessage());
            return ToolResultHelper.createResult(ProfileSemanticSearchResponse.error(e.getMessage()));
        } catch (final IOException e) {
            logger.error("Profile semantic search error", e);
            return ToolResultHelper.createResult(ProfileSemanticSearchResponse.error("Profile semantic search failed: " + e.getMessage()));
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
