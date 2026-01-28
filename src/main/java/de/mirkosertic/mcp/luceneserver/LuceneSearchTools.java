package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.crawler.CrawlStatistics;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlerConfigurationManager;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentCrawlerService;
import org.apache.lucene.queryparser.classic.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class LuceneSearchTools {

    private static final Logger logger = LoggerFactory.getLogger(LuceneSearchTools.class);

    private final LuceneIndexService indexService;
    private final DocumentCrawlerService crawlerService;
    private final CrawlerConfigurationManager configManager;

    public LuceneSearchTools(final LuceneIndexService indexService,
                             final DocumentCrawlerService crawlerService, final CrawlerConfigurationManager configManager) {
        this.indexService = indexService;
        this.crawlerService = crawlerService;
        this.configManager = configManager;
    }

    @McpTool(name = "search", description = "Search the Lucene fulltext index using LEXICAL matching (exact word forms only). " +
            "IMPORTANT: No automatic synonym expansion, phonetic matching, or stemming is applied. " +
            "To find synonyms/variants, YOU MUST explicitly include them using OR: '(contract OR agreement OR deal)'. " +
            "Use wildcards for variations: 'contract*' matches 'contracts', 'contracting'. " +
            "Supports Lucene query syntax: " +
            "- Simple terms: 'hello world' (implicit AND) " +
            "- Phrases: '\"exact phrase\"' " +
            "- Boolean: 'term1 AND term2', 'term1 OR term2', 'NOT term' " +
            "- Wildcards: 'test*' (suffix), 'te?t' (single char), '*test' (prefix, slow!) " +
            "- Field search: 'title:hello content:world' " +
            "- Grouping: '(contract OR agreement) AND signed' " +
            "Best practices: Combine related terms with OR, use wildcards for inflections, leverage facets for drill-down. " +
            "Returns: paginated results with snippets (highlighted matches), relevance scores, facets (with counts), and searchTimeMs.")
    public Map<String, Object> search(
            @McpToolParam(description = "The search query using Lucene query syntax", required = true) final
            String query,
            @McpToolParam(description = "Optional field name to filter results. Use together with filterValue.") final
            String filterField,
            @McpToolParam(description = "Optional value for the filter field. Use together with filterField.") final
            String filterValue,
            @McpToolParam(description = "Page number (0-based). Default is 0.") final
            Integer page,
            @McpToolParam(description = "Number of results per page. Default is 10, maximum is 100.") final
            Integer pageSize
    ) {
        logger.info("Search request: query='{}', filterField='{}', filterValue='{}', page={}, pageSize={}",
                query, filterField, filterValue, page, pageSize);

        final Map<String, Object> response = new HashMap<>();

        try {
            // Apply defaults and constraints
            final int effectivePage = (page != null && page >= 0) ? page : 0;
            final int effectivePageSize = (pageSize != null && pageSize > 0) ? Math.min(pageSize, 100) : 10;

            // Measure search execution time
            final long startTime = System.nanoTime();
            final LuceneIndexService.SearchResult result = indexService.search(
                    query, filterField, filterValue, effectivePage, effectivePageSize);
            final long endTime = System.nanoTime();
            final long durationMs = (endTime - startTime) / 1_000_000;

            response.put("success", true);
            response.put("documents", result.documents());
            response.put("totalHits", result.totalHits());
            response.put("page", result.page());
            response.put("pageSize", result.pageSize());
            response.put("totalPages", result.totalPages());
            response.put("hasNextPage", result.hasNextPage());
            response.put("hasPreviousPage", result.hasPreviousPage());
            response.put("facets", result.facets());
            response.put("searchTimeMs", durationMs);

            logger.info("Search completed in {}ms: {} total hits, returning page {} of {}, {} facet dimensions available",
                    durationMs, result.totalHits(), result.page(), result.totalPages(), result.facets().size());

        } catch (final ParseException e) {
            logger.warn("Invalid query syntax: {}", e.getMessage());
            response.put("success", false);
            response.put("error", "Invalid query syntax: " + e.getMessage());
        } catch (final IOException e) {
            logger.error("Search error", e);
            response.put("success", false);
            response.put("error", "Search error: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "getIndexStats", description = "Get statistics about the Lucene index, including the total number of indexed documents.")
    public Map<String, Object> getIndexStats() {
        logger.info("Index stats request");

        final Map<String, Object> response = new HashMap<>();

        try {
            final long documentCount = indexService.getDocumentCount();
            response.put("success", true);
            response.put("documentCount", documentCount);
            response.put("indexPath", System.getProperty("lucene.index.path", "configured in application.yaml"));

            logger.info("Index stats: {} documents", documentCount);

        } catch (final IOException e) {
            logger.error("Error getting index stats", e);
            response.put("success", false);
            response.put("error", "Error getting index stats: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "startCrawl", description = "Start crawling configured directories to index documents. " +
            "Supports MSOffice, OpenOffice, and PDF files. Automatically detects document language and extracts metadata.")
    public Map<String, Object> startCrawl(
            @McpToolParam(description = "If true, clears the index before crawling. Default is false.") final
            Boolean fullReindex
    ) {
        logger.info("Start crawl request: fullReindex={}", fullReindex);

        final Map<String, Object> response = new HashMap<>();

        try {
            final boolean doFullReindex = fullReindex != null && fullReindex;
            crawlerService.startCrawl(doFullReindex);

            response.put("success", true);
            response.put("message", "Crawl started");
            response.put("fullReindex", doFullReindex);

            logger.info("Crawl started with fullReindex={}", doFullReindex);

        } catch (final IOException e) {
            logger.error("Error starting crawl", e);
            response.put("success", false);
            response.put("error", "Error starting crawl: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "getCrawlerStats", description = "Get real-time statistics about the crawler progress, including files processed, throughput, and per-directory stats.")
    public Map<String, Object> getCrawlerStats() {
        logger.info("Crawler stats request");

        final Map<String, Object> response = new HashMap<>();

        try {
            final CrawlStatistics stats = crawlerService.getStatistics();

            response.put("success", true);
            response.put("filesFound", stats.filesFound());
            response.put("filesProcessed", stats.filesProcessed());
            response.put("filesIndexed", stats.filesIndexed());
            response.put("filesFailed", stats.filesFailed());
            response.put("bytesProcessed", stats.bytesProcessed());
            response.put("filesPerSecond", stats.filesPerSecond());
            response.put("megabytesPerSecond", stats.megabytesPerSecond());
            response.put("elapsedTimeMs", stats.elapsedTimeMs());
            response.put("perDirectoryStats", stats.perDirectoryStats());

            logger.info("Crawler stats: processed={}, indexed={}, failed={}",
                    stats.filesProcessed(), stats.filesIndexed(), stats.filesFailed());

        } catch (final Exception e) {
            logger.error("Error getting crawler stats", e);
            response.put("success", false);
            response.put("error", "Error getting crawler stats: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "listIndexedFields", description = "List all field names present in the Lucene index. Useful for understanding the index schema and building queries.")
    public Map<String, Object> listIndexedFields() {
        logger.info("List indexed fields request");

        final Map<String, Object> response = new HashMap<>();

        try {
            final Set<String> fields = indexService.getIndexedFields();

            response.put("success", true);
            response.put("fields", fields);

            logger.info("Listed {} indexed fields", fields.size());

        } catch (final IOException e) {
            logger.error("Error listing indexed fields", e);
            response.put("success", false);
            response.put("error", "Error listing indexed fields: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "pauseCrawler", description = "Pause an ongoing crawl operation. The crawler can be resumed later.")
    public Map<String, Object> pauseCrawler() {
        logger.info("Pause crawler request");

        final Map<String, Object> response = new HashMap<>();

        try {
            crawlerService.pauseCrawler();

            response.put("success", true);
            response.put("message", "Crawler paused");

            logger.info("Crawler paused");

        } catch (final Exception e) {
            logger.error("Error pausing crawler", e);
            response.put("success", false);
            response.put("error", "Error pausing crawler: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "resumeCrawler", description = "Resume a paused crawl operation.")
    public Map<String, Object> resumeCrawler() {
        logger.info("Resume crawler request");

        final Map<String, Object> response = new HashMap<>();

        try {
            crawlerService.resumeCrawler();

            response.put("success", true);
            response.put("message", "Crawler resumed");

            logger.info("Crawler resumed");

        } catch (final Exception e) {
            logger.error("Error resuming crawler", e);
            response.put("success", false);
            response.put("error", "Error resuming crawler: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "getCrawlerStatus", description = "Get the current state of the crawler (IDLE, CRAWLING, PAUSED, or WATCHING).")
    public Map<String, Object> getCrawlerStatus() {
        logger.info("Crawler status request");

        final Map<String, Object> response = new HashMap<>();

        try {
            final DocumentCrawlerService.CrawlerState state = crawlerService.getState();

            response.put("success", true);
            response.put("state", state.name());

            logger.info("Crawler state: {}", state);

        } catch (final Exception e) {
            logger.error("Error getting crawler status", e);
            response.put("success", false);
            response.put("error", "Error getting crawler status: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "listCrawlableDirectories", description = "List all configured crawlable directories. " +
            "Shows directories from ~/.mcplucene/config.yaml or environment variable override.")
    public Map<String, Object> listCrawlableDirectories() {
        logger.info("List crawlable directories request");

        final Map<String, Object> response = new HashMap<>();

        try {
            final List<String> directories = configManager.loadDirectories();
            final boolean envOverride = configManager.isEnvironmentOverrideActive();

            response.put("success", true);
            response.put("directories", directories);
            response.put("totalDirectories", directories.size());
            response.put("configPath", configManager.getConfigPath().toString());
            response.put("environmentOverride", envOverride);

            logger.info("Listed {} directories (envOverride={})", directories.size(), envOverride);

        } catch (final Exception e) {
            logger.error("Error listing crawlable directories", e);
            response.put("success", false);
            response.put("error", "Error listing crawlable directories: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "addCrawlableDirectory", description = "Add a directory to the crawler configuration. " +
            "The directory will be persisted to ~/.mcplucene/config.yaml and " +
            "automatically crawled on next startup. If the crawler is currently " +
            "watching directories, this directory will be added to the watch list.")
    public Map<String, Object> addCrawlableDirectory(
            @McpToolParam(description = "Absolute path to the directory to crawl", required = true) final String path,
            @McpToolParam(description = "If true, immediately starts crawling the new directory. Default is false.") final Boolean crawlNow
    ) {
        logger.info("Add crawlable directory request: path='{}', crawlNow={}", path, crawlNow);

        final Map<String, Object> response = new HashMap<>();

        try {
            // Check if environment variable override is active
            if (configManager.isEnvironmentOverrideActive()) {
                response.put("success", false);
                response.put("error", "Cannot modify configuration when LUCENE_CRAWLER_DIRECTORIES environment variable is set");
                logger.warn("Cannot add directory - environment variable override is active");
                return response;
            }

            // Validate directory exists and is a directory
            final Path dirPath = Paths.get(path);
            if (!Files.exists(dirPath)) {
                response.put("success", false);
                response.put("error", "Directory does not exist: " + path);
                logger.warn("Directory does not exist: {}", path);
                return response;
            }

            if (!Files.isDirectory(dirPath)) {
                response.put("success", false);
                response.put("error", "Path is not a directory: " + path);
                logger.warn("Path is not a directory: {}", path);
                return response;
            }

            // Get current directories to check for duplicates
            final List<String> currentDirectories = configManager.loadDirectories();
            if (currentDirectories.contains(path)) {
                response.put("success", true);
                response.put("message", "Directory already configured: " + path);
                response.put("totalDirectories", currentDirectories.size());
                response.put("directories", currentDirectories);
                logger.info("Directory already configured: {}", path);
                return response;
            }

            // Add to configuration
            configManager.addDirectory(path);

            // Update crawler service
            final List<String> updatedDirectories = configManager.loadDirectories();
            crawlerService.updateDirectories(updatedDirectories);

            response.put("success", true);
            response.put("message", "Directory added successfully: " + path);
            response.put("totalDirectories", updatedDirectories.size());
            response.put("directories", updatedDirectories);

            logger.info("Added directory: {} (total: {})", path, updatedDirectories.size());

            // Optionally trigger immediate crawl
            if (crawlNow != null && crawlNow) {
                logger.info("Starting immediate crawl for new directory");
                crawlerService.startCrawl(false);
                response.put("crawlStarted", true);
            }

        } catch (final IOException e) {
            logger.error("Error adding crawlable directory", e);
            response.put("success", false);
            response.put("error", "Error adding directory: " + e.getMessage());
        } catch (final Exception e) {
            logger.error("Unexpected error adding crawlable directory", e);
            response.put("success", false);
            response.put("error", "Unexpected error: " + e.getMessage());
        }

        return response;
    }

    @McpTool(name = "removeCrawlableDirectory", description = "Remove a directory from the crawler configuration. " +
            "The change will be persisted to ~/.mcplucene/config.yaml. " +
            "Note: This does not remove already indexed documents from the directory.")
    public Map<String, Object> removeCrawlableDirectory(
            @McpToolParam(description = "Absolute path to the directory to remove", required = true) final String path
    ) {
        logger.info("Remove crawlable directory request: path='{}'", path);

        final Map<String, Object> response = new HashMap<>();

        try {
            // Check if environment variable override is active
            if (configManager.isEnvironmentOverrideActive()) {
                response.put("success", false);
                response.put("error", "Cannot modify configuration when LUCENE_CRAWLER_DIRECTORIES environment variable is set");
                logger.warn("Cannot remove directory - environment variable override is active");
                return response;
            }

            // Check if directory exists in configuration
            final List<String> currentDirectories = configManager.loadDirectories();
            if (!currentDirectories.contains(path)) {
                response.put("success", false);
                response.put("error", "Directory not found in configuration: " + path);
                response.put("totalDirectories", currentDirectories.size());
                response.put("directories", currentDirectories);
                logger.warn("Directory not found in configuration: {}", path);
                return response;
            }

            // Remove from configuration
            configManager.removeDirectory(path);

            // Update crawler service
            final List<String> updatedDirectories = configManager.loadDirectories();
            crawlerService.updateDirectories(updatedDirectories);

            response.put("success", true);
            response.put("message", "Directory removed successfully: " + path);
            response.put("totalDirectories", updatedDirectories.size());
            response.put("directories", updatedDirectories);

            logger.info("Removed directory: {} (remaining: {})", path, updatedDirectories.size());

        } catch (final IOException e) {
            logger.error("Error removing crawlable directory", e);
            response.put("success", false);
            response.put("error", "Error removing directory: " + e.getMessage());
        } catch (final Exception e) {
            logger.error("Unexpected error removing crawlable directory", e);
            response.put("success", false);
            response.put("error", "Unexpected error: " + e.getMessage());
        }

        return response;
    }
}
