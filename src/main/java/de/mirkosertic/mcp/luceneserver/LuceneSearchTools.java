package de.mirkosertic.mcp.luceneserver;

import com.google.common.io.Resources;
import de.mirkosertic.mcp.luceneserver.config.BuildInfo;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlStatistics;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlerConfigurationManager;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentCrawlerService;
import de.mirkosertic.mcp.luceneserver.mcp.SchemaGenerator;
import de.mirkosertic.mcp.luceneserver.mcp.ToolResultHelper;
import de.mirkosertic.mcp.luceneserver.mcp.dto.*;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.lucene.queryparser.classic.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP tools for Lucene search operations.
 * Provides search, crawling, and index management functionality.
 */
public class LuceneSearchTools {

    private static final Logger logger = LoggerFactory.getLogger(LuceneSearchTools.class);

    private static final String SEARCH_DESCRIPTION =
            "Search documents using Lucene query syntax with automatic Snowball stemming for German and English. " +
            "Stemming finds morphological variants (e.g. 'Vertrag' matches 'Verträge'/'Vertrages', 'contract' matches 'contracts'/'contracting'). " +
            "Exact matches always rank highest. " +
            "Supports: wildcards (*, ?), boolean operators (AND/OR/NOT), phrase queries, fuzzy search, field-specific queries. " +
            "Use 'filters' array for metadata filtering (file type, date range, language, etc.). " +
            "Results can be sorted by relevance score (default), modified_date, created_date, or file_size. " +
            "IMPORTANT: For synonym expansion, use OR queries: '(contract OR agreement)'. " +
            "For German compound words, use *vertrag (leading wildcard). " +
            "Returns paginated results with highlighted passages, scores, facets. " +
            "See lucene://docs/query-syntax for complete syntax guide.";

    private static final String ADMIN_APP_RESOURCE_ID = "ui://indexadmin/index.html";

    private final LuceneIndexService indexService;
    private final DocumentCrawlerService crawlerService;
    private final CrawlerConfigurationManager configManager;
    private final QueryRuntimeStats queryRuntimeStats = new QueryRuntimeStats();

    public LuceneSearchTools(final LuceneIndexService indexService,
                             final DocumentCrawlerService crawlerService,
                             final CrawlerConfigurationManager configManager) {
        this.indexService = indexService;
        this.crawlerService = crawlerService;
        this.configManager = configManager;
    }

    public List<McpServerFeatures.SyncResourceSpecification> getResourceSpecifications() {
        final List<McpServerFeatures.SyncResourceSpecification> resources = new ArrayList<>();

        resources.add(new McpServerFeatures.SyncResourceSpecification(
                McpSchema.Resource.builder()
                        .uri(ADMIN_APP_RESOURCE_ID)
                        .mimeType("text/html;profile=mcp-app")
                        .name("Index Administration")
                        .meta(Map.of("ui", Map.of("border", "true")))
                        .build(),
                this::indexAdminResource
        ));

        // Query Syntax Guide
        resources.add(new McpServerFeatures.SyncResourceSpecification(
                McpSchema.Resource.builder()
                        .uri("lucene://docs/query-syntax")
                        .name("Lucene Query Syntax Guide")
                        .description("Complete guide to search query syntax, filters, wildcards, and best practices")
                        .mimeType("text/markdown")
                        .build(),
                this::querySyntaxGuide
        ));

        // Profiling Guide
        resources.add(new McpServerFeatures.SyncResourceSpecification(
                McpSchema.Resource.builder()
                        .uri("lucene://docs/profiling-guide")
                        .name("Query Profiling Guide")
                        .description("Detailed guide to query profiling, analysis levels, and optimization techniques")
                        .mimeType("text/markdown")
                        .build(),
                this::profilingGuide
        ));

        return resources;
    }

    private McpSchema.ReadResourceResult indexAdminResource(final McpSyncServerExchange exchange, final McpSchema.ReadResourceRequest request) {
        final URL url = Resources.getResource("indexadmin-app.html");
        try {
            final String htmlcpde = Resources.toString(url, StandardCharsets.UTF_8);

            return new McpSchema.ReadResourceResult(
                    List.of(new McpSchema.TextResourceContents(
                            ADMIN_APP_RESOURCE_ID,
                            "text/html;profile=mcp-app",
                            htmlcpde)));
        } catch (final Exception e) {
            logger.error("Error loading index admin app", e);
            return new McpSchema.ReadResourceResult(
                    List.of(new McpSchema.TextResourceContents(
                            ADMIN_APP_RESOURCE_ID,
                            "text/html;profile=mcp-app",
                            "")));
        }
    }

    private McpSchema.ReadResourceResult querySyntaxGuide(
            final McpSyncServerExchange exchange,
            final McpSchema.ReadResourceRequest request) {

        final String markdown = """
            # Lucene Query Syntax - Complete Guide

            ## Overview

            The search tool uses **lexical matching with automatic stemming** for German and English. Stemming finds morphological variants (e.g. "Vertrag" also matches "Verträge"/"Vertrages"). Exact matches always rank highest. No automatic synonym expansion.

            ## Basic Syntax

            ### Simple Terms
            ```
            hello world
            ```
            Finds documents containing both "hello" AND "world" (implicit AND).

            ### Phrases
            ```
            "exact phrase"
            ```
            Finds the exact phrase in that order.

            ### Boolean Operators
            ```
            contract AND signed
            contract OR agreement
            NOT confidential
            (contract OR agreement) AND signed
            ```

            ## Wildcards

            | Pattern | Matches | Example |
            |---------|---------|---------|
            | `*` | Any characters | `contract*` → contracts, contracting, contracted |
            | `?` | Single character | `te?t` → test, text |
            | `*word` | Leading wildcard | `*vertrag` → Arbeitsvertrag, Mietvertrag |
            | `*word*` | Contains | `*vertrag*` → Arbeitsvertrag, Vertragsbedingungen |

            **German Compound Words:** Use leading wildcards `*vertrag` to find:
            - Arbeitsvertrag, Mietvertrag, Kaufvertrag, etc.

            ## Advanced Features

            ### Fuzzy Search
            ```
            contract~2
            ```
            Finds terms within Levenshtein distance 2: contract, contracts, contrct

            ### Proximity Search
            ```
            "signed contract"~5
            ```
            Finds "signed" and "contract" within 5 words of each other.

            ### Field-Specific Search
            ```
            title:report
            content:quarterly AND author:Smith
            ```

            ## Structured Filters

            Use the `filters` array for precise metadata filtering.

            ### Operators

            | Operator | Description | Example |
            |----------|-------------|---------|
            | `eq` | Exact match (default) | `{field: "language", value: "en"}` |
            | `in` | Match any value | `{field: "file_extension", operator: "in", values: ["pdf", "docx"]}` |
            | `not` | Exclude | `{field: "language", operator: "not", value: "unknown"}` |
            | `not_in` | Exclude multiple | `{field: "language", operator: "not_in", values: ["unknown", ""]}` |
            | `range` | Numeric/date range | `{field: "modified_date", operator: "range", from: "2024-01-01", to: "2024-12-31"}` |

            ### Filterable Fields

            **Faceted (DrillSideways):**
            - `language`, `file_extension`, `file_type`
            - `author`

            **String (exact match):**
            - `file_path`, `content_hash`

            **Numeric/Date (range support):**
            - `file_size`, `created_date`, `modified_date`, `indexed_date`

            ### Date Format

            ISO-8601 format:
            - `2024-01-15` (date only)
            - `2024-01-15T10:30:00` (date + time, UTC assumed)
            - `2024-01-15T10:30:00Z` (date + time + timezone)

            ### Filter Logic

            - Filters on **different fields**: AND logic
            - Filters on **same faceted field**: OR logic (DrillSideways)
            - `not`/`not_in` filters: MUST_NOT clauses

            ## Best Practices

            ### 1. Synonym Expansion

            ❌ Don't expect automatic synonyms:
            ```
            contract
            ```

            ✅ Use OR to combine synonyms:
            ```
            (contract OR agreement OR deal)
            ```

            ### 2. Inflections & Word Forms

            ✅ Automatic stemming handles most inflections for German and English:
            ```
            contract
            ```
            Matches "contracts", "contracted", "contracting" automatically.

            ⚠️ Irregular forms may not be unified (e.g. "ran" ≠ "run"). Use OR for irregulars:
            ```
            (run OR running OR ran)
            ```

            ### 3. Multilingual Search

            ❌ Don't search in just one language:
            ```
            contract
            ```

            ✅ Combine terms from multiple languages:
            ```
            (contract OR Vertrag OR contrat)
            ```

            ### 4. German Compound Words

            ❌ Won't find compounds:
            ```
            vertrag
            ```

            ✅ Use leading wildcard:
            ```
            *vertrag
            ```
            Finds: Arbeitsvertrag, Mietvertrag, Kaufvertrag

            ### 5. Combine Query + Filters

            ✅ Use query for content, filters for metadata:
            ```json
            {
              "query": "(contract OR agreement) AND signed",
              "filters": [
                {"field": "language", "value": "en"},
                {"field": "file_extension", "operator": "in", "values": ["pdf", "docx"]},
                {"field": "modified_date", "operator": "range", "from": "2024-01-01"}
              ]
            }
            ```

            ## Unicode Normalization

            The analyzer applies ICU folding:
            - ✅ Diacritics folded: `Müller` matches `Muller`, `café` matches `cafe`
            - ✅ Full-width characters normalized
            - ✅ Ligatures expanded: PDF "fi" ligature → "fi"

            ## What's NOT Supported

            - ❌ Stemming for languages other than German and English
            - ❌ Irregular verb unification (ran ≠ run — use OR queries)
            - ❌ Automatic synonyms (contract ≠ agreement)
            - ❌ Phonetic matching (Smith ≠ Smyth)
            - ❌ Semantic search (use explicit OR queries)

            ## Examples

            ### Find contracts signed in 2024
            ```json
            {
              "query": "(contract OR agreement) AND signed",
              "filters": [
                {"field": "modified_date", "operator": "range", "from": "2024-01-01", "to": "2024-12-31"}
              ]
            }
            ```

            ### Find German documents about "Vertrag"
            ```json
            {
              "query": "*vertrag*",
              "filters": [
                {"field": "language", "value": "de"}
              ]
            }
            ```

            ### Find PDFs or Word docs, exclude drafts
            ```json
            {
              "query": "report NOT draft",
              "filters": [
                {"field": "file_extension", "operator": "in", "values": ["pdf", "docx"]}
              ]
            }
            ```

            ### Filter-only search (all PDFs)
            ```json
            {
              "query": null,
              "filters": [
                {"field": "file_extension", "value": "pdf"}
              ]
            }
            ```

            ## Sorting Results

            Results can be sorted by relevance score (default) or metadata fields.

            ### Available Sort Fields

            | Sort Field | Description | Default Order |
            |------------|-------------|---------------|
            | `_score` | Relevance score (default) | Descending (best match first) |
            | `modified_date` | Last modified date | Descending (most recent first) |
            | `created_date` | Creation date | Descending (most recent first) |
            | `file_size` | File size in bytes | Descending (largest first) |

            ### Sort Orders

            - `desc` - Descending (default for all fields)
            - `asc` - Ascending

            ### Sort Examples

            **Most recently modified documents:**
            ```json
            {
              "query": "contract",
              "sortBy": "modified_date",
              "sortOrder": "desc"
            }
            ```

            **Oldest documents first:**
            ```json
            {
              "query": "contract",
              "sortBy": "created_date",
              "sortOrder": "asc"
            }
            ```

            **Smallest files (for quick review):**
            ```json
            {
              "query": "summary",
              "sortBy": "file_size",
              "sortOrder": "asc"
            }
            ```

            **Combine sorting with filters:**
            ```json
            {
              "query": "*",
              "sortBy": "modified_date",
              "sortOrder": "desc",
              "filters": [
                {"field": "file_extension", "value": "pdf"},
                {"field": "modified_date", "operator": "range", "from": "2024-01-01"}
              ]
            }
            ```

            **Note:** When sorting by metadata fields, relevance scores are still computed and used as a secondary sort criterion for tie-breaking.
            """;

        return new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(
                        "lucene://docs/query-syntax",
                        "text/markdown",
                        markdown
                ))
        );
    }

    private McpSchema.ReadResourceResult profilingGuide(
            final McpSyncServerExchange exchange,
            final McpSchema.ReadResourceRequest request) {

        final String markdown = """
            # Query Profiling Guide

            ## Overview

            The `profileQuery` tool helps you understand and optimize search queries by providing detailed analysis of:
            - Query structure and rewrites
            - Term statistics and discriminative power
            - Filter impact and selectivity
            - Document scoring breakdown (BM25)
            - Performance characteristics

            ## Analysis Levels

            ### Level 1: Fast Analysis (Always Included, ~5-10ms)

            **Automatically provided:**
            - Query structure breakdown (BooleanQuery, TermQuery, WildcardQuery, etc.)
            - Query rewrites (e.g., `*vertrag` → `content_reversed:gartrev*`)
            - Term statistics (document frequency, IDF, rarity)
            - Search metrics (total hits, filter reduction)
            - Cost estimates per query component

            ### Level 2: Filter Impact Analysis (Opt-in, ~50-200ms)

            **Enable with:** `analyzeFilterImpact: true`

            **Provides:**
            - How each filter affects result count
            - Selectivity classification (low/medium/high/very high)
            - Execution time per filter

            **Performance cost:** Requires N+1 queries (N = number of filters)

            ### Level 3: Document Scoring Explanations (Opt-in, ~100-300ms)

            **Enable with:** `analyzeDocumentScoring: true`

            **Provides:**
            - Detailed BM25 score breakdown for top-ranked documents
            - Term contribution percentages
            - Human-readable scoring summaries

            **Limit:** Use `maxDocExplanations: 5` (default) or up to 10

            ### Level 4: Facet Cost Analysis (Opt-in, ~20-50ms)

            **Enable with:** `analyzeFacetCost: true`

            **Provides:**
            - Faceting computation overhead
            - Cost per facet dimension

            ## Performance Guidelines

            | Analysis Level | Time | Queries | When to Use |
            |---------------|------|---------|-------------|
            | Level 1 (default) | 5-10ms | 1 | Always - minimal overhead |
            | + Filter Impact | 50-200ms | N+1 | Debugging filter issues |
            | + Doc Scoring | 100-300ms | 1 | Understanding ranking |
            | + Facet Cost | 20-50ms | 2 | Performance tuning |
            | **All levels** | **200-500ms** | **N+4** | Deep debugging only |

            ## Example Usage

            ### Basic: Quick performance check
            ```json
            {
              "query": "contract AND signed",
              "filters": [{"field": "language", "value": "en"}]
            }
            ```

            ### Debug: Filter effectiveness
            ```json
            {
              "query": "contract",
              "filters": [
                {"field": "language", "value": "en"},
                {"field": "file_type", "value": "pdf"}
              ],
              "analyzeFilterImpact": true
            }
            ```

            ### Debug: Ranking issues
            ```json
            {
              "query": "(contract OR agreement) AND signed",
              "analyzeDocumentScoring": true,
              "maxDocExplanations": 3
            }
            ```

            ### Full analysis
            ```json
            {
              "query": "*vertrag* OR *contract*",
              "filters": [...],
              "analyzeFilterImpact": true,
              "analyzeDocumentScoring": true,
              "analyzeFacetCost": true,
              "maxDocExplanations": 5
            }
            ```

            ## Known Limitations

            1. **Cannot explain non-matches:** Only explains documents that matched
            2. **Automaton internals opaque:** Can't trace exact wildcard substring matches
            3. **No passage-level scoring:** Shows document scores but not passage selection

            ## Tips

            1. Start with Level 1 - it's fast and often sufficient
            2. Use Level 2 for filter tuning with many filters
            3. Use Level 3 for ranking issues
            4. Don't enable all levels by default - use only when debugging
            5. Read recommendations - the tool provides actionable suggestions
            """;

        return new McpSchema.ReadResourceResult(
                List.of(new McpSchema.TextResourceContents(
                        "lucene://docs/profiling-guide",
                        "text/markdown",
                        markdown
                ))
        );
    }

    /**
     * Returns all MCP tool specifications for registration with the MCP server.
     */
    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        // Index admin MCP App (https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("indexAdmin")
                        .description("MCP App for Lucene index administrative tasks and maintenance")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .meta(Map.of("ui", Map.of("resourceUri", ADMIN_APP_RESOURCE_ID),
                                "ui/resourceUri", ADMIN_APP_RESOURCE_ID))
                        .build())
                .callHandler((exchange, request) -> indexAdmin(request.arguments()))
                .build());

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
                        .description("Debug and optimize search queries. Analyzes query structure, scoring, filter impact, and performance. " +
                                "Fast basic analysis shows term statistics and cost estimates. " +
                                "Opt-in deep analysis (analyzeFilterImpact, analyzeDocumentScoring, analyzeFacetCost) provides detailed breakdowns but is expensive. " +
                                "Returns LLM-optimized structured output with actionable recommendations. " +
                                "See lucene://docs/profiling-guide for analysis level details.")
                        .inputSchema(SchemaGenerator.generateSchema(ProfileQueryRequest.class))
                        .build())
                .callHandler((exchange, request) -> profileQuery(request.arguments()))
                .build());

        // Get index stats tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getIndexStats")
                        .description("Get index statistics: document count, schema version, date field ranges, " +
                                "lemmatizer cache performance (hit rate, size, evictions per language), " +
                                "and query runtime metrics (avg/min/max duration, p50-p99 percentiles, " +
                                "per-field facet computation timing). " +
                                "Note: Query metrics are available after the first search; stats update on next search.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getIndexStats())
                .build());

        // Start crawl tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("startCrawl")
                        .description("Start crawling configured directories. Indexes Office docs, PDFs, emails, markdown. " +
                                "Extracts metadata and detects language automatically. Use getCrawlerStats to monitor progress.")
                        .inputSchema(SchemaGenerator.generateSchema(StartCrawlRequest.class))
                        .build())
                .callHandler((exchange, request) -> startCrawl(request.arguments()))
                .build());

        // Get crawler stats tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getCrawlerStats")
                        .description("Get real-time statistics about the crawler progress, including files processed, throughput, and per-directory stats.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getCrawlerStats())
                .build());

        // List indexed fields tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("listIndexedFields")
                        .description("List all field names present in the Lucene index. Useful for understanding the index schema and building queries.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> listIndexedFields())
                .build());

        // Pause crawler tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("pauseCrawler")
                        .description("Pause an ongoing crawl operation. The crawler can be resumed later.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> pauseCrawler())
                .build());

        // Resume crawler tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("resumeCrawler")
                        .description("Resume a paused crawl operation.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> resumeCrawler())
                .build());

        // Get crawler status tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getCrawlerStatus")
                        .description("Get the current state of the crawler (IDLE, CRAWLING, PAUSED, or WATCHING).")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getCrawlerStatus())
                .build());

        // List crawlable directories tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("listCrawlableDirectories")
                        .description("List all configured crawlable directories. " +
                                "Shows directories from ~/.mcplucene/config.yaml or environment variable override.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> listCrawlableDirectories())
                .build());

        // Add crawlable directory tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("addCrawlableDirectory")
                        .description("Add a directory to the crawler configuration. " +
                                "The directory will be persisted to ~/.mcplucene/config.yaml and " +
                                "automatically crawled on next startup. If the crawler is currently " +
                                "watching directories, this directory will be added to the watch list.")
                        .inputSchema(SchemaGenerator.generateSchema(AddDirectoryRequest.class))
                        .build())
                .callHandler((exchange, request) -> addCrawlableDirectory(request.arguments()))
                .build());

        // Remove crawlable directory tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("removeCrawlableDirectory")
                        .description("Remove a directory from the crawler configuration. " +
                                "The change will be persisted to ~/.mcplucene/config.yaml. " +
                                "Note: This does not remove already indexed documents from the directory.")
                        .inputSchema(SchemaGenerator.generateSchema(RemoveDirectoryRequest.class))
                        .build())
                .callHandler((exchange, request) -> removeCrawlableDirectory(request.arguments()))
                .build());

        // Get document details tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getDocumentDetails")
                        .description("Retrieve all stored fields and content of a document from the Lucene index by its file path. " +
                                "This tool retrieves document details and extracted content directly from the index without requiring filesystem access. " +
                                "Returns all metadata fields (file_path, file_name, file_extension, file_type, file_size, title, author, creator, subject, keywords, language, " +
                                "created_date, modified_date, indexed_date, content_hash) and the full document content. " +
                                "Content is limited to 500,000 characters (500KB) to keep response size safe - use the contentTruncated field to check if content was truncated.")
                        .inputSchema(SchemaGenerator.generateSchema(GetDocumentDetailsRequest.class))
                        .build())
                .callHandler((exchange, request) -> getDocumentDetails(request.arguments()))
                .build());

        // Unlock index tool (dangerous recovery operation)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("unlockIndex")
                        .description("Remove the write.lock file from the Lucene index directory. " +
                                "WARNING: This is a dangerous recovery operation. Only use if you are CERTAIN no other process is using the index. " +
                                "Unlocking an index that is actively being written to can cause data corruption. " +
                                "Requires explicit confirmation (confirm=true) to proceed.")
                        .inputSchema(SchemaGenerator.generateSchema(UnlockIndexRequest.class))
                        .build())
                .callHandler((exchange, request) -> unlockIndex(request.arguments()))
                .build());

        // Optimize index tool (long-running)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("optimizeIndex")
                        .description("Optimize the Lucene index by merging segments. This is a long-running operation that runs in the background. " +
                                "Use getIndexAdminStatus to poll for progress. " +
                                "Optimization improves search performance but temporarily increases disk usage during the merge. " +
                                "Cannot run while the crawler is active.")
                        .inputSchema(SchemaGenerator.generateSchema(OptimizeIndexRequest.class))
                        .build())
                .callHandler((exchange, request) -> optimizeIndex(request.arguments()))
                .build());

        // Purge index tool (destructive, long-running)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("purgeIndex")
                        .description("Delete all documents from the Lucene index. This is a destructive operation that runs in the background. " +
                                "Use getIndexAdminStatus to poll for progress. " +
                                "Requires explicit confirmation (confirm=true) to proceed. " +
                                "Set fullPurge=true to also delete index files and reinitialize (reclaims disk space immediately).")
                        .inputSchema(SchemaGenerator.generateSchema(PurgeIndexRequest.class))
                        .build())
                .callHandler((exchange, request) -> purgeIndex(request.arguments()))
                .build());

        // Get index admin status tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getIndexAdminStatus")
                        .description("Get the status of long-running index administration operations (optimize, purge). " +
                                "Returns the current state (IDLE, OPTIMIZING, PURGING, COMPLETED, FAILED), progress percentage, " +
                                "progress message, elapsed time, and the result of the last completed operation.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getIndexAdminStatus())
                .build());

        return tools;
    }

    // Tool implementation methods
    private McpSchema.CallToolResult indexAdmin(final Map<String, Object> args) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(ToolResultHelper.toJson(new HashMap<>()))))
                .build();
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
                    request.effectiveSortOrder());
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

    private McpSchema.CallToolResult getIndexStats() {
        logger.info("Index stats request");

        try {
            final long documentCount = indexService.getDocumentCount();
            final String indexPath = indexService.getIndexPath();
            final int schemaVersion = indexService.getIndexSchemaVersion();
            final String softwareVersion = BuildInfo.getVersion();
            final String buildTimestamp = BuildInfo.getBuildTimestamp();

            // Get date field ranges and convert to ISO-8601 strings
            final Map<String, long[]> ranges = indexService.getDateFieldRanges();
            final Map<String, IndexStatsResponse.DateFieldHint> dateFieldHints = new HashMap<>();
            for (final var entry : ranges.entrySet()) {
                final String minDate = java.time.Instant.ofEpochMilli(entry.getValue()[0]).toString();
                final String maxDate = java.time.Instant.ofEpochMilli(entry.getValue()[1]).toString();
                dateFieldHints.put(entry.getKey(), new IndexStatsResponse.DateFieldHint(minDate, maxDate));
            }

            // Get lemmatizer cache statistics
            final Map<String, LemmatizerCacheStats> cacheStatsMap = indexService.getLemmatizerCacheStats();
            final Map<String, IndexStatsResponse.LemmatizerCacheMetrics> lemmatizerMetrics = new HashMap<>();
            for (final var entry : cacheStatsMap.entrySet()) {
                final String language = entry.getKey();
                final LemmatizerCacheStats stats = entry.getValue();
                lemmatizerMetrics.put(language, new IndexStatsResponse.LemmatizerCacheMetrics(
                        language,
                        String.format("%.1f%%", stats.getHitRate()),
                        stats.getCacheHits(),
                        stats.getCacheMisses(),
                        stats.getCurrentSize(),
                        stats.getEvictions()
                ));
            }

            // Build query runtime metrics
            final IndexStatsResponse.QueryRuntimeMetrics queryRuntimeMetrics;
            if (queryRuntimeStats.getTotalQueries() > 0) {
                final QueryRuntimeStats.Percentiles percentiles = queryRuntimeStats.getPercentiles();
                final String averageFacetDurationMs = String.format("%.3f",
                        queryRuntimeStats.getAverageFacetDurationMicros() / 1000.0);
                final Map<String, String> perFieldAvgFacetMs = new HashMap<>();
                final Map<String, Long> perFieldCumulative = queryRuntimeStats.getPerFieldFacetDurationMicros();
                final long totalQueriesForFacets = queryRuntimeStats.getTotalQueries();
                for (final var entry : perFieldCumulative.entrySet()) {
                    perFieldAvgFacetMs.put(entry.getKey(),
                            String.format("%.3f", (double) entry.getValue() / totalQueriesForFacets / 1000.0));
                }
                queryRuntimeMetrics = new IndexStatsResponse.QueryRuntimeMetrics(
                        queryRuntimeStats.getTotalQueries(),
                        String.format("%.1f", queryRuntimeStats.getAverageDurationMs()),
                        queryRuntimeStats.getMinDurationMs(),
                        queryRuntimeStats.getMaxDurationMs(),
                        String.format("%.1f", queryRuntimeStats.getAverageHitCount()),
                        percentiles != null ? percentiles.p50() : null,
                        percentiles != null ? percentiles.p75() : null,
                        percentiles != null ? percentiles.p90() : null,
                        percentiles != null ? percentiles.p95() : null,
                        percentiles != null ? percentiles.p99() : null,
                        averageFacetDurationMs,
                        perFieldAvgFacetMs
                );
            } else {
                queryRuntimeMetrics = null;
            }

            logger.info("Index stats: {} documents, schema v{}", documentCount, schemaVersion);

            return ToolResultHelper.createResult(IndexStatsResponse.success(
                    documentCount, indexPath, schemaVersion, softwareVersion, buildTimestamp, dateFieldHints, lemmatizerMetrics, queryRuntimeMetrics));

        } catch (final IOException e) {
            logger.error("Error getting index stats", e);
            return ToolResultHelper.createResult(IndexStatsResponse.error("Error getting index stats: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult startCrawl(final Map<String, Object> args) {
        final StartCrawlRequest request = StartCrawlRequest.fromMap(args);

        logger.info("Start crawl request: fullReindex={}", request.fullReindex());

        try {
            crawlerService.startCrawl(request.effectiveFullReindex());
            logger.info("Crawl started with fullReindex={}", request.effectiveFullReindex());

            return ToolResultHelper.createResult(StartCrawlResponse.success(request.effectiveFullReindex()));

        } catch (final IOException e) {
            logger.error("Error starting crawl", e);
            return ToolResultHelper.createResult(StartCrawlResponse.error("Error starting crawl: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getCrawlerStats() {
        logger.info("Crawler stats request");

        try {
            final CrawlStatistics stats = crawlerService.getStatistics();
            final var lastCrawlState = configManager.loadCrawlState();

            logger.info("Crawler stats: processed={}, indexed={}, failed={}",
                    stats.filesProcessed(), stats.filesIndexed(), stats.filesFailed());

            return ToolResultHelper.createResult(CrawlerStatsResponse.success(stats, lastCrawlState));

        } catch (final Exception e) {
            logger.error("Error getting crawler stats", e);
            return ToolResultHelper.createResult(CrawlerStatsResponse.error("Error getting crawler stats: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult listIndexedFields() {
        logger.info("List indexed fields request");

        try {
            final Set<String> fields = indexService.getIndexedFields();

            logger.info("Listed {} indexed fields", fields.size());

            return ToolResultHelper.createResult(IndexedFieldsResponse.success(fields));

        } catch (final IOException e) {
            logger.error("Error listing indexed fields", e);
            return ToolResultHelper.createResult(IndexedFieldsResponse.error("Error listing indexed fields: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult pauseCrawler() {
        logger.info("Pause crawler request");

        try {
            crawlerService.pauseCrawler();
            logger.info("Crawler paused");

            return ToolResultHelper.createResult(SimpleMessageResponse.success("Crawler paused"));

        } catch (final Exception e) {
            logger.error("Error pausing crawler", e);
            return ToolResultHelper.createResult(SimpleMessageResponse.error("Error pausing crawler: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult resumeCrawler() {
        logger.info("Resume crawler request");

        try {
            crawlerService.resumeCrawler();
            logger.info("Crawler resumed");

            return ToolResultHelper.createResult(SimpleMessageResponse.success("Crawler resumed"));

        } catch (final Exception e) {
            logger.error("Error resuming crawler", e);
            return ToolResultHelper.createResult(SimpleMessageResponse.error("Error resuming crawler: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getCrawlerStatus() {
        logger.info("Crawler status request");

        try {
            final DocumentCrawlerService.CrawlerState state = crawlerService.getState();

            logger.info("Crawler state: {}", state);

            return ToolResultHelper.createResult(CrawlerStatusResponse.success(state.name()));

        } catch (final Exception e) {
            logger.error("Error getting crawler status", e);
            return ToolResultHelper.createResult(CrawlerStatusResponse.error("Error getting crawler status: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult listCrawlableDirectories() {
        logger.info("List crawlable directories request");

        try {
            final List<String> directories = configManager.loadDirectories();
            final boolean envOverride = configManager.isEnvironmentOverrideActive();
            final String configPath = configManager.getConfigPath().toString();

            logger.info("Listed {} directories (envOverride={})", directories.size(), envOverride);

            return ToolResultHelper.createResult(ListDirectoriesResponse.success(directories, configPath, envOverride));

        } catch (final Exception e) {
            logger.error("Error listing crawlable directories", e);
            return ToolResultHelper.createResult(ListDirectoriesResponse.error("Error listing crawlable directories: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult addCrawlableDirectory(final Map<String, Object> args) {
        final AddDirectoryRequest request = AddDirectoryRequest.fromMap(args);

        logger.info("Add crawlable directory request: path='{}', crawlNow={}", request.path(), request.crawlNow());

        try {
            // Check if environment variable override is active
            if (configManager.isEnvironmentOverrideActive()) {
                logger.warn("Cannot add directory - environment variable override is active");
                return ToolResultHelper.createResult(AddDirectoryResponse.error(
                        "Cannot modify configuration when LUCENE_CRAWLER_DIRECTORIES environment variable is set"));
            }

            // Validate directory exists and is a directory
            final Path dirPath = Paths.get(request.path());
            if (!Files.exists(dirPath)) {
                logger.warn("Directory does not exist: {}", request.path());
                return ToolResultHelper.createResult(AddDirectoryResponse.error("Directory does not exist: " + request.path()));
            }

            if (!Files.isDirectory(dirPath)) {
                logger.warn("Path is not a directory: {}", request.path());
                return ToolResultHelper.createResult(AddDirectoryResponse.error("Path is not a directory: " + request.path()));
            }

            // Get current directories to check for duplicates
            final List<String> currentDirectories = configManager.loadDirectories();
            if (currentDirectories.contains(request.path())) {
                logger.info("Directory already configured: {}", request.path());
                return ToolResultHelper.createResult(AddDirectoryResponse.success(
                        "Directory already configured: " + request.path(), currentDirectories, false));
            }

            // Add to configuration
            configManager.addDirectory(request.path());

            // Update crawler service
            final List<String> updatedDirectories = configManager.loadDirectories();
            crawlerService.updateDirectories(updatedDirectories);

            logger.info("Added directory: {} (total: {})", request.path(), updatedDirectories.size());

            // Optionally trigger immediate crawl
            boolean crawlStarted = false;
            if (request.effectiveCrawlNow()) {
                logger.info("Starting immediate crawl for new directory");
                crawlerService.startCrawl(false);
                crawlStarted = true;
            }

            return ToolResultHelper.createResult(AddDirectoryResponse.success(
                    "Directory added successfully: " + request.path(), updatedDirectories, crawlStarted));

        } catch (final IOException e) {
            logger.error("Error adding crawlable directory", e);
            return ToolResultHelper.createResult(AddDirectoryResponse.error("Error adding directory: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error adding crawlable directory", e);
            return ToolResultHelper.createResult(AddDirectoryResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult removeCrawlableDirectory(final Map<String, Object> args) {
        final RemoveDirectoryRequest request = RemoveDirectoryRequest.fromMap(args);

        logger.info("Remove crawlable directory request: path='{}'", request.path());

        try {
            // Check if environment variable override is active
            if (configManager.isEnvironmentOverrideActive()) {
                logger.warn("Cannot remove directory - environment variable override is active");
                return ToolResultHelper.createResult(RemoveDirectoryResponse.error(
                        "Cannot modify configuration when LUCENE_CRAWLER_DIRECTORIES environment variable is set"));
            }

            // Check if directory exists in configuration
            final List<String> currentDirectories = configManager.loadDirectories();
            if (!currentDirectories.contains(request.path())) {
                logger.warn("Directory not found in configuration: {}", request.path());
                return ToolResultHelper.createResult(RemoveDirectoryResponse.notFound(request.path(), currentDirectories));
            }

            // Remove from configuration
            configManager.removeDirectory(request.path());

            // Update crawler service
            final List<String> updatedDirectories = configManager.loadDirectories();
            crawlerService.updateDirectories(updatedDirectories);

            logger.info("Removed directory: {} (remaining: {})", request.path(), updatedDirectories.size());

            return ToolResultHelper.createResult(RemoveDirectoryResponse.success(
                    "Directory removed successfully: " + request.path(), updatedDirectories));

        } catch (final IOException e) {
            logger.error("Error removing crawlable directory", e);
            return ToolResultHelper.createResult(RemoveDirectoryResponse.error("Error removing directory: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error removing crawlable directory", e);
            return ToolResultHelper.createResult(RemoveDirectoryResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getDocumentDetails(final Map<String, Object> args) {
        final GetDocumentDetailsRequest request = GetDocumentDetailsRequest.fromMap(args);

        logger.info("Get document details request: filePath='{}'", request.filePath());

        try {
            final Map<String, Object> document = indexService.getDocumentByFilePath(request.filePath());

            if (document == null) {
                logger.warn("Document not found in index: {}", request.filePath());
                return ToolResultHelper.createResult(GetDocumentDetailsResponse.error(
                        "Document not found in index: " + request.filePath()));
            }

            logger.info("Retrieved document details for: {} (content length: {}, truncated: {})",
                    request.filePath(),
                    document.containsKey("content") ? ((String) document.get("content")).length() : 0,
                    document.getOrDefault("contentTruncated", false));

            return ToolResultHelper.createResult(GetDocumentDetailsResponse.success(document));

        } catch (final IOException e) {
            logger.error("Error retrieving document details", e);
            return ToolResultHelper.createResult(GetDocumentDetailsResponse.error("Error retrieving document: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error retrieving document details", e);
            return ToolResultHelper.createResult(GetDocumentDetailsResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    // ==================== Index Administration Tools ====================

    private McpSchema.CallToolResult unlockIndex(final Map<String, Object> args) {
        final UnlockIndexRequest request = UnlockIndexRequest.fromMap(args);

        logger.info("Unlock index request: confirm={}", request.confirm());

        // Require explicit confirmation
        if (!request.isConfirmed()) {
            logger.warn("Unlock index request not confirmed");
            return ToolResultHelper.createResult(UnlockIndexResponse.notConfirmed());
        }

        try {
            final boolean lockFileExisted = indexService.isLockFilePresent();
            final String lockFilePath = indexService.getLockFilePath().toString();

            if (!lockFileExisted) {
                logger.info("No lock file present at: {}", lockFilePath);
                return ToolResultHelper.createResult(UnlockIndexResponse.success(
                        "No lock file present. Index is not locked.", false, lockFilePath));
            }

            final boolean removed = indexService.removeLockFile();

            if (removed) {
                logger.warn("Lock file removed: {}", lockFilePath);
                return ToolResultHelper.createResult(UnlockIndexResponse.success(
                        "Lock file removed successfully. WARNING: Ensure no other process was using the index.",
                        true, lockFilePath));
            } else {
                return ToolResultHelper.createResult(UnlockIndexResponse.error(
                        "Failed to remove lock file. It may have been removed by another process."));
            }

        } catch (final IOException e) {
            logger.error("Error unlocking index", e);
            return ToolResultHelper.createResult(UnlockIndexResponse.error("Error unlocking index: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error unlocking index", e);
            return ToolResultHelper.createResult(UnlockIndexResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult optimizeIndex(final Map<String, Object> args) {
        final OptimizeIndexRequest request = OptimizeIndexRequest.fromMap(args);

        logger.info("Optimize index request: maxSegments={}", request.maxSegments());

        try {
            // Check if crawler is active
            final DocumentCrawlerService.CrawlerState crawlerState = crawlerService.getState();
            if (crawlerState == DocumentCrawlerService.CrawlerState.CRAWLING) {
                logger.warn("Cannot optimize while crawler is active");
                return ToolResultHelper.createResult(OptimizeIndexResponse.crawlerActive());
            }

            // Check if another admin operation is running
            if (indexService.isAdminOperationRunning()) {
                final LuceneIndexService.AdminOperationStatus status = indexService.getAdminStatus();
                logger.warn("Another admin operation is running: {}, cannot optimize the index", status.state());
                return ToolResultHelper.createResult(OptimizeIndexResponse.alreadyRunning(status.operationId()));
            }

            // Get current segment count
            final int currentSegments = indexService.getSegmentCount();
            final int targetSegments = request.effectiveMaxSegments();

            // Start optimization
            final String operationId = indexService.startOptimization(targetSegments);

            if (operationId == null) {
                logger.warn("Failed to start optimization - another operation may have started");
                return ToolResultHelper.createResult(OptimizeIndexResponse.error(
                        "Failed to start optimization. Another operation may have started."));
            }

            logger.info("Optimization started: operationId={}, currentSegments={}, targetSegments={}",
                    operationId, currentSegments, targetSegments);

            return ToolResultHelper.createResult(OptimizeIndexResponse.started(
                    operationId, targetSegments, currentSegments));

        } catch (final IOException e) {
            logger.error("Error starting optimization", e);
            return ToolResultHelper.createResult(OptimizeIndexResponse.error("Error starting optimization: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error starting optimization", e);
            return ToolResultHelper.createResult(OptimizeIndexResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult purgeIndex(final Map<String, Object> args) {
        final PurgeIndexRequest request = PurgeIndexRequest.fromMap(args);

        logger.info("Purge index request: confirm={}, fullPurge={}", request.confirm(), request.fullPurge());

        // Require explicit confirmation
        if (!request.isConfirmed()) {
            logger.warn("Purge index request not confirmed");
            return ToolResultHelper.createResult(PurgeIndexResponse.notConfirmed());
        }

        try {
            // Check if another admin operation is running
            if (indexService.isAdminOperationRunning()) {
                final LuceneIndexService.AdminOperationStatus status = indexService.getAdminStatus();
                logger.warn("Another admin operation is running: {}, cannot purge the index", status.state());
                return ToolResultHelper.createResult(PurgeIndexResponse.alreadyRunning(status.operationId()));
            }

            // Start purge
            final LuceneIndexService.PurgeResult result = indexService.startPurge(request.effectiveFullPurge());

            if (result == null) {
                logger.warn("Failed to start purge - another operation may have started");
                return ToolResultHelper.createResult(PurgeIndexResponse.error(
                        "Failed to start purge. Another operation may have started."));
            }

            logger.info("Purge started: operationId={}, documentsToDelete={}, fullPurge={}",
                    result.operationId(), result.documentsDeleted(), request.effectiveFullPurge());

            return ToolResultHelper.createResult(PurgeIndexResponse.started(
                    result.operationId(), result.documentsDeleted(), request.effectiveFullPurge()));

        } catch (final Exception e) {
            logger.error("Error starting purge", e);
            return ToolResultHelper.createResult(PurgeIndexResponse.error("Error starting purge: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getIndexAdminStatus() {
        logger.info("Get index admin status request");

        try {
            final LuceneIndexService.AdminOperationStatus status = indexService.getAdminStatus();

            logger.info("Index admin status: state={}, operationId={}, progress={}%",
                    status.state(), status.operationId(), status.progressPercent());

            if (status.state() == LuceneIndexService.AdminOperationState.IDLE) {
                return ToolResultHelper.createResult(IndexAdminStatusResponse.idle(status.lastOperationResult()));
            }

            return ToolResultHelper.createResult(IndexAdminStatusResponse.success(
                    status.state().name(),
                    status.operationId(),
                    status.progressPercent(),
                    status.progressMessage(),
                    status.elapsedTimeMs(),
                    status.lastOperationResult()
            ));

        } catch (final Exception e) {
            logger.error("Error getting index admin status", e);
            return ToolResultHelper.createResult(IndexAdminStatusResponse.error("Error getting status: " + e.getMessage()));
        }
    }
}
