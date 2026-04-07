package de.mirkosertic.mcp.luceneserver.tools;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlStatistics;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlerConfigurationManager;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentCrawlerService;
import de.mirkosertic.mcp.luceneserver.mcp.SchemaGenerator;
import de.mirkosertic.mcp.luceneserver.mcp.ToolResultHelper;
import de.mirkosertic.mcp.luceneserver.mcp.dto.AddDirectoryRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.AddDirectoryResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.CrawlerStatsResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.CrawlerStatusResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ListDirectoriesResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.RemoveDirectoryRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.RemoveDirectoryResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SimpleMessageResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.StartCrawlRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.StartCrawlResponse;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for crawler operations: startCrawl, getCrawlerStats, getCrawlerStatus,
 * pauseCrawler, resumeCrawler, listCrawlableDirectories, addCrawlableDirectory, removeCrawlableDirectory.
 */
public class CrawlerTools implements McpToolProvider {

    private static final Logger logger = LoggerFactory.getLogger(CrawlerTools.class);

    private final DocumentCrawlerService crawlerService;
    private final CrawlerConfigurationManager configManager;
    private final ApplicationConfig config;

    public CrawlerTools(final DocumentCrawlerService crawlerService, final CrawlerConfigurationManager configManager,
            final ApplicationConfig config) {
        this.crawlerService = crawlerService;
        this.configManager = configManager;
        this.config = config;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        // Start crawl tool
        if (config.isToolActive("startCrawl")) {
            logger.info("Exposing startCrawl tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("startCrawl")
                            .description("Start crawling configured directories. Indexes Office docs, PDFs, emails, markdown. " +
                                    "Extracts metadata and detects language automatically. Use getCrawlerStats to monitor progress.")
                            .inputSchema(SchemaGenerator.generateSchema(StartCrawlRequest.class))
                            .build())
                    .callHandler((exchange, request) -> startCrawl(request.arguments()))
                    .build());
        }

        // Get crawler stats tool
        if (config.isToolActive("getCrawlerStats")) {
            logger.info("Exposing getCrawlerStats tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("getCrawlerStats")
                            .description("Get real-time statistics about the crawler progress, including files processed, throughput, and per-directory stats.")
                            .inputSchema(SchemaGenerator.emptySchema())
                            .build())
                    .callHandler((exchange, request) -> getCrawlerStats())
                    .build());
        }

        // Get crawler status tool
        if (config.isToolActive("getCrawlerStatus")) {
            logger.info("Exposing getCrawlerStatus tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("getCrawlerStatus")
                            .description("Get the current state of the crawler (IDLE, CRAWLING, PAUSED, or WATCHING).")
                            .inputSchema(SchemaGenerator.emptySchema())
                            .build())
                    .callHandler((exchange, request) -> getCrawlerStatus())
                    .build());
        }

        // Pause crawler tool
        if (config.isToolActive("pauseCrawler")) {
            logger.info("Exposing pauseCrawler tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("pauseCrawler")
                            .description("Pause an ongoing crawl operation. The crawler can be resumed later.")
                            .inputSchema(SchemaGenerator.emptySchema())
                            .build())
                    .callHandler((exchange, request) -> pauseCrawler())
                    .build());
        }

        // Resume crawler tool
        if (config.isToolActive("resumeCrawler")) {
            logger.info("Exposing resumeCrawler tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("resumeCrawler")
                            .description("Resume a paused crawl operation.")
                            .inputSchema(SchemaGenerator.emptySchema())
                            .build())
                    .callHandler((exchange, request) -> resumeCrawler())
                    .build());
        }

        // List crawlable directories tool
        if (config.isToolActive("listCrawlableDirectories")) {
            logger.info("Exposing listCrawlableDirectories tool");
            tools.add(McpServerFeatures.SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder()
                            .name("listCrawlableDirectories")
                            .description("List all configured crawlable directories. " +
                                    "Shows directories from ~/.mcplucene/config.yaml or environment variable override.")
                            .inputSchema(SchemaGenerator.emptySchema())
                            .build())
                    .callHandler((exchange, request) -> listCrawlableDirectories())
                    .build());
        }

        // Add crawlable directory tool
        if (config.isToolActive("addCrawlableDirectory")) {
            logger.info("Exposing addCrawlableDirectory tool");
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
        }

        // Remove crawlable directory tool
        if (config.isToolActive("removeCrawlableDirectory")) {
            logger.info("Exposing removeCrawlableDirectory tool");
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
        }

        return tools;
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
}
