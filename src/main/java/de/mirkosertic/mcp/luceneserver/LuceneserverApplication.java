package de.mirkosertic.mcp.luceneserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.config.BuildInfo;
import de.mirkosertic.mcp.luceneserver.config.LoggingConfigurator;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.index.QueryRuntimeStats;
import de.mirkosertic.mcp.luceneserver.onnx.ONNXService;
import de.mirkosertic.mcp.luceneserver.util.NotificationService;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlExecutorService;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlStatisticsTracker;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlerConfigurationManager;
import de.mirkosertic.mcp.luceneserver.crawler.DirectoryWatcherService;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentCrawlerService;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.FileContentExtractor;
import de.mirkosertic.mcp.luceneserver.crawler.IndexReconciliationService;
import de.mirkosertic.mcp.luceneserver.mcp.LatestProtocolStdioServerTransportProvider;
import de.mirkosertic.mcp.luceneserver.transport.TransportFactory;
import de.mirkosertic.mcp.luceneserver.transport.TransportType;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.mirkosertic.mcp.luceneserver.tools.CrawlerTools;
import de.mirkosertic.mcp.luceneserver.tools.IndexAdminTools;
import de.mirkosertic.mcp.luceneserver.tools.IndexInfoTools;
import de.mirkosertic.mcp.luceneserver.tools.SearchTools;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

/**
 * Main entry point for the MCP Lucene Server.
 * Initializes all services and starts the MCP server using STDIO or HTTP transport.
 */
public class LuceneserverApplication {

    private static final Logger logger = LoggerFactory.getLogger(LuceneserverApplication.class);

    private final ApplicationConfig config;
    private final LuceneIndexService indexService;
    private final NotificationService notificationService;
    private final CrawlerConfigurationManager configManager;
    private final CrawlExecutorService crawlExecutor;
    private final DirectoryWatcherService watcherService;
    private final DocumentCrawlerService crawlerService;
    private final SearchTools searchTools;
    private final CrawlerTools crawlerTools;
    private final IndexInfoTools indexInfoTools;
    private final IndexAdminTools indexAdminTools;
    private final ONNXService onnxService;
    private McpSyncServer mcpSyncServer;
    private McpSyncServer mcpStreamableSyncServer;
    private TransportFactory.HttpTransportWrapper httpTransport;

    public LuceneserverApplication(final ApplicationConfig config) {
        this.config = config;

        // Initialize services in dependency order
        // DocumentIndexer must be created first as LuceneIndexService depends on it
        final DocumentIndexer documentIndexer = new DocumentIndexer();

        // Initialize ONNXService here so its hidden_size is available to LuceneIndexService
        // for embedding dimension checks during index initialization.
        if (config.isSemanticSearchEnabled()) {
            final String modelName = config.getVectorModel();
            logger.info("Semantic search enabled — loading ONNX model '{}'", modelName);
            try {
                onnxService = new ONNXService(modelName);
                logger.info("Semantic search active: model='{}' hidden_size={}", modelName, onnxService.getHiddenSize());
            } catch (final Exception e) {
                throw new RuntimeException("Failed to initialize ONNXService for semantic search", e);
            }
        } else {
            onnxService = null;
            logger.info("Semantic search not active (VECTOR_MODEL not configured or semantic tools not included)");
        }

        this.indexService = new LuceneIndexService(
                config,
                documentIndexer,
                onnxService
        );

        this.notificationService = new NotificationService();

        this.configManager = new CrawlerConfigurationManager();

        this.crawlExecutor = new CrawlExecutorService(config);

        final FileContentExtractor contentExtractor = new FileContentExtractor(config);

        this.watcherService = new DirectoryWatcherService(config);

        final CrawlStatisticsTracker statisticsTracker = new CrawlStatisticsTracker(config, notificationService);

        final IndexReconciliationService reconciliationService = new IndexReconciliationService(indexService);

        this.crawlerService = new DocumentCrawlerService(
                config,
                indexService,
                contentExtractor,
                documentIndexer,
                crawlExecutor,
                statisticsTracker,
                watcherService,
                reconciliationService,
                configManager
        );

        final QueryRuntimeStats queryRuntimeStats = new QueryRuntimeStats();

        this.searchTools = new SearchTools(indexService, queryRuntimeStats, config);
        this.crawlerTools = new CrawlerTools(crawlerService, configManager, config);
        this.indexInfoTools = new IndexInfoTools(indexService, queryRuntimeStats, config);
        this.indexAdminTools = new IndexAdminTools(indexService, crawlerService, config);
    }

    /**
     * Initialize all services.
     */
    public void init() throws IOException {
        logger.info("Initializing MCP Lucene Server...");

        // Initialize services
        indexService.init();
        configManager.init();

        // Update crawler service with configured directories
        final List<String> directories = configManager.loadDirectories();
        if (!directories.isEmpty()) {
            config.setDirectories(directories);
        }

        // Initialize crawler
        crawlerService.init();

        // Check if schema upgrade is required and trigger automatic reindex
        if (indexService.isSchemaUpgradeRequired()) {
            logger.warn("Schema version changed — triggering full reindex");
            crawlerService.startCrawl(true);
        }

        logger.info("All services initialized successfully");
    }

    /**
     * Start the MCP server with the configured transport (STDIO or HTTP).
     */
    public void start() {
        final TransportType transportType = TransportFactory.getTransportType();
        logger.info("Starting MCP server with {} transport...", transportType);

        // Create server capabilities using builder
        final McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
                .tools(true) // tools with listChanged
                .resources(true, true)
                .build();

        // Create server info
        final McpSchema.Implementation serverInfo = new McpSchema.Implementation(
                "MCP Lucene Server",
                BuildInfo.getVersion()
        );

        // Create JSON mapper for MCP protocol
        final JacksonMcpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

        try {
            if (transportType == TransportType.HTTP) {
                startHttpTransport(serverInfo, capabilities, jsonMapper);
            } else {
                startStdioTransport(serverInfo, capabilities, jsonMapper);
            }

            logger.info("MCP server started successfully");
            notificationService.notify("MCP Lucene Server started", "MCP Lucene Server is now running.");

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));

            // Block main thread
            waitForShutdown(transportType);

            logger.info("Main thread finished, shutting down...");

        } catch (final Exception e) {
            logger.error("Failed to start MCP server", e);
            throw new RuntimeException("Failed to start MCP server: " + e.getMessage(), e);
        }
    }

    /**
     * Start the MCP server with STDIO transport.
     */
    private void startStdioTransport(
            final McpSchema.Implementation serverInfo,
            final McpSchema.ServerCapabilities capabilities,
            final JacksonMcpJsonMapper jsonMapper) {

        final LatestProtocolStdioServerTransportProvider transportProvider =
                TransportFactory.createStdioTransport(jsonMapper);

        final List<McpServerFeatures.SyncToolSpecification> allTools = Stream.of(
                        searchTools, crawlerTools, indexInfoTools, indexAdminTools)
                .flatMap(p -> p.getToolSpecifications().stream()).toList();

        // Build and start the MCP server
        mcpSyncServer = McpServer.sync(transportProvider)
                .serverInfo(serverInfo)
                .capabilities(capabilities)
                .tools(allTools)
                .resources(indexAdminTools.getResourceSpecifications())
                .build();
    }

    /**
     * Start the MCP server with HTTP transport using streamable sync mode.
     * The MCP SDK's HttpServletStreamableServerTransportProvider handles the
     * Streamable HTTP protocol (Spec 2025-03-26) internally.
     */
    private void startHttpTransport(
            final McpSchema.Implementation serverInfo,
            final McpSchema.ServerCapabilities capabilities,
            final JacksonMcpJsonMapper jsonMapper) throws Exception {

        // Create HTTP transport with embedded Jetty server
        httpTransport = TransportFactory.createHttpTransport(jsonMapper);

        final List<McpServerFeatures.SyncToolSpecification> allTools = Stream.of(
                        searchTools, crawlerTools, indexInfoTools, indexAdminTools)
                .flatMap(p -> p.getToolSpecifications().stream()).toList();

        // Build sync MCP server for HTTP using streamable transport
        // The transport provider handles async HTTP internally while we use sync handlers
        mcpStreamableSyncServer = McpServer.sync(httpTransport.transportProvider())
                .serverInfo(serverInfo)
                .capabilities(capabilities)
                .tools(allTools)
                .resources(indexAdminTools.getResourceSpecifications())
                .build();

        // Start the HTTP server
        httpTransport.start();

        logger.info("HTTP endpoint available at: {}", httpTransport.config().getUrl());
    }

    /**
     * Block the main thread until shutdown.
     */
    private void waitForShutdown(final TransportType transportType) throws InterruptedException {
        if (transportType == TransportType.HTTP) {
            // For HTTP, join the Jetty server thread
            httpTransport.join();
        } else {
            // For STDIO, keep the application running
            Thread.currentThread().join();
        }
    }

    /**
     * Shutdown all services gracefully.
     */
    public void shutdown() {
        logger.info("Shutting down MCP Lucene Server...");

        // Shutdown in reverse order of initialization
        try {
            if (httpTransport != null) {
                httpTransport.stop();
            }
        } catch (final Exception e) {
            logger.error("Error stopping HTTP transport", e);
        }

        try {
            if (mcpSyncServer != null) {
                mcpSyncServer.close();
            }
        } catch (final Exception e) {
            logger.error("Error closing MCP sync server", e);
        }

        try {
            if (mcpStreamableSyncServer != null) {
                mcpStreamableSyncServer.close();
            }
        } catch (final Exception e) {
            logger.error("Error closing MCP streamable sync server", e);
        }

        try {
            crawlerService.shutdown();
        } catch (final Exception e) {
            logger.error("Error shutting down crawler service", e);
        }

        try {
            watcherService.shutdown();
        } catch (final Exception e) {
            logger.error("Error shutting down watcher service", e);
        }

        try {
            crawlExecutor.shutdown();
        } catch (final Exception e) {
            logger.error("Error shutting down crawl executor", e);
        }

        try {
            if (onnxService != null) {
                onnxService.close();
            }
        } catch (final Exception e) {
            logger.error("Error closing ONNX service", e);
        }

        try {
            indexService.close();
        } catch (final Exception e) {
            logger.error("Error closing index service", e);
        }

        logger.info("MCP Lucene Server shutdown complete");
    }

    public static void main(final String[] args) {
        try {
            // Load configuration first so that profile parsing (deployed, vectorsearch, ...)
            // is done in one place via ApplicationConfig.determineProfile().
            // Note: ApplicationConfig.load() may emit a few log lines before the logging
            // configurator runs; those go to the SLF4J default destination which is
            // acceptable because they are informational only.
            final ApplicationConfig config = ApplicationConfig.load();

            // Configure logging based on the already-parsed profile flags.
            // Using config.isDeployedMode() correctly handles comma-separated profiles
            // such as "deployed,vectorsearch" without a second manual parse.
            LoggingConfigurator.configure(config.isDeployedMode());

            if (!config.isDeployedMode()) {
                logger.info("Running in development mode (console logging enabled)");
                logger.info("Index path: {}", config.getIndexPath());
                logger.info("Configured directories: {}", config.getDirectories());
            }

            // Create and initialize application
            final LuceneserverApplication app = new LuceneserverApplication(config);
            app.init();
            app.start();

            logger.info("MCP Lucene Server finished.");

        } catch (final Exception e) {
            // In deployed mode, we can't log to console, so write to stderr
            System.err.println("Failed to start MCP Lucene Server: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
