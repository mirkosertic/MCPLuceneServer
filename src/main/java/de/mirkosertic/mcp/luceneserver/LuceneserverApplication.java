package de.mirkosertic.mcp.luceneserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.config.BuildInfo;
import de.mirkosertic.mcp.luceneserver.config.LoggingConfigurator;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlExecutorService;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlStatisticsTracker;
import de.mirkosertic.mcp.luceneserver.crawler.CrawlerConfigurationManager;
import de.mirkosertic.mcp.luceneserver.crawler.DirectoryWatcherService;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentCrawlerService;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.crawler.FileContentExtractor;
import de.mirkosertic.mcp.luceneserver.crawler.IndexReconciliationService;
import de.mirkosertic.mcp.luceneserver.mcp.LatestProtocolStdioServerTransportProvider;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Main entry point for the MCP Lucene Server.
 * Initializes all services and starts the MCP server using STDIO transport.
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
    private final LuceneSearchTools searchTools;
    private McpSyncServer mcpServer;

    public LuceneserverApplication(final ApplicationConfig config) {
        this.config = config;

        // Initialize services in dependency order
        // DocumentIndexer must be created first as LuceneIndexService depends on it
        final DocumentIndexer documentIndexer = new DocumentIndexer();

        this.indexService = new LuceneIndexService(
                config,
                documentIndexer
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

        this.searchTools = new LuceneSearchTools(
                indexService,
                crawlerService,
                configManager
        );
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
     * Start the MCP server.
     */
    public void start() {
        logger.info("Starting MCP server with STDIO transport...");

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

        // Create STDIO transport provider with latest protocol version support
        final LatestProtocolStdioServerTransportProvider transportProvider =
                new LatestProtocolStdioServerTransportProvider(jsonMapper);

        // Build and start the MCP server
        mcpServer = McpServer.sync(transportProvider)
                .serverInfo(serverInfo)
                .capabilities(capabilities)
                .tools(searchTools.getToolSpecifications())
                .resources(searchTools.getResourceSpecifications())
                .build();

        logger.info("MCP server started successfully");

        notificationService.notify("MCP Lucene Server started", "MCP Lucene Server is now running.");

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));

        // Block main thread - the STDIO transport handles communication
        try {
            // Keep the application running
            Thread.currentThread().join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("Main thread interrupted, shutting down...");
        }

        logger.info("Main thread finished, shutting down...");
    }

    /**
     * Shutdown all services gracefully.
     */
    public void shutdown() {
        logger.info("Shutting down MCP Lucene Server...");

        // Shutdown in reverse order of initialization
        try {
            if (mcpServer != null) {
                mcpServer.close();
            }
        } catch (final Exception e) {
            logger.error("Error closing MCP server", e);
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
            indexService.close();
        } catch (final Exception e) {
            logger.error("Error closing index service", e);
        }

        logger.info("MCP Lucene Server shutdown complete");
    }

    public static void main(final String[] args) {
        try {
            // Configure logging FIRST, before any other code that might log
            // Check system property directly to avoid logging during config load
            final boolean deployedMode = "deployed".equals(System.getProperty("spring.profiles.active"));
            LoggingConfigurator.configure(deployedMode);

            // Now load configuration (its logging will go to the right place)
            final ApplicationConfig config = ApplicationConfig.load();

            if (!deployedMode) {
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
