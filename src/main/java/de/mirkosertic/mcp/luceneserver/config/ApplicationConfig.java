package de.mirkosertic.mcp.luceneserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Central configuration for the MCP Lucene Server application.
 * Loads configuration from YAML files and environment variables.
 * <p>
 * Configuration priority (highest to lowest):
 * 1. Environment variables
 * 2. System properties
 * 3. User config file (~/.mcplucene/config.yaml)
 * 4. Application defaults (application.yaml in classpath)
 */
public class ApplicationConfig {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationConfig.class);

    private static final String ENV_INDEX_PATH = "LUCENE_INDEX_PATH";
    private static final String ENV_CRAWLER_DIRECTORIES = "LUCENE_CRAWLER_DIRECTORIES";
    private static final String PROP_PROFILES_ACTIVE = "spring.profiles.active";
    private static final String CONFIG_DIR = ".mcplucene";
    private static final String USER_CONFIG_FILE = "config.yaml";
    private static final String DEFAULT_CONFIG_FILE = "application.yaml";

    // Lucene index settings
    private String indexPath;
    private final long nrtRefreshIntervalMs = 100;

    // Crawler settings
    private List<String> directories = new ArrayList<>();
    private List<String> includePatterns = List.of(
            "*.pdf", "*.doc", "*.docx", "*.odt",
            "*.ppt", "*.pptx", "*.xls", "*.xlsx",
            "*.ods", "*.txt"
    );
    private List<String> excludePatterns = List.of(
            "**/node_modules/**", "**/.git/**",
            "**/target/**", "**/build/**"
    );
    private int threadPoolSize = 4;
    private int batchSize = 100;
    private long batchTimeoutMs = 5000;
    private boolean watchEnabled = true;
    private long watchPollIntervalMs = 2000;
    private int bulkIndexThreshold = 1000;
    private long slowNrtRefreshIntervalMs = 5000;
    private long maxContentLength = -1;
    private boolean extractMetadata = true;
    private boolean detectLanguage = true;
    private boolean crawlOnStartup = true;
    private int progressNotificationFiles = 100;
    private long progressNotificationIntervalMs = 30000;
    private boolean reconciliationEnabled = true;

    // Profile settings
    private boolean deployedMode = false;

    private ApplicationConfig() {
    }

    /**
     * Load configuration from all sources with proper priority.
     */
    public static ApplicationConfig load() {
        final ApplicationConfig config = new ApplicationConfig();

        // Step 1: Load application defaults from classpath
        config.loadFromClasspath();

        // Step 2: Load user config file (may override some settings)
        config.loadFromUserConfig();

        // Step 3: Apply environment variables and system properties (highest priority)
        config.applyEnvironmentOverrides();

        // Step 4: Determine profile/mode
        config.determineProfile();

        logger.info("Configuration loaded: indexPath={}, directories={}, deployedMode={}",
                config.indexPath, config.directories.size(), config.deployedMode);

        return config;
    }

    private void loadFromClasspath() {
        try (final InputStream is = getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIG_FILE)) {
            if (is != null) {
                final Yaml yaml = new Yaml();
                final Map<String, Object> config = yaml.load(is);
                if (config != null) {
                    applyYamlConfig(config);
                    logger.debug("Loaded defaults from classpath: {}", DEFAULT_CONFIG_FILE);
                }
            }
        } catch (final IOException e) {
            logger.warn("Failed to load default config from classpath", e);
        }
    }

    private void loadFromUserConfig() {
        final Path userConfigPath = getUserConfigPath();
        if (Files.exists(userConfigPath)) {
            try (final InputStream is = Files.newInputStream(userConfigPath)) {
                final Yaml yaml = new Yaml();
                final Map<String, Object> config = yaml.load(is);
                if (config != null) {
                    applyYamlConfig(config);
                    logger.debug("Loaded user config from: {}", userConfigPath);
                }
            } catch (final IOException e) {
                logger.warn("Failed to load user config from: {}", userConfigPath, e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyYamlConfig(final Map<String, Object> config) {
        // Navigate to lucene section
        final Map<String, Object> luceneConfig = (Map<String, Object>) config.get("lucene");
        if (luceneConfig == null) {
            return;
        }

        // Index settings
        final Map<String, Object> indexConfig = (Map<String, Object>) luceneConfig.get("index");
        if (indexConfig != null) {
            final Object path = indexConfig.get("path");
            if (path != null) {
                this.indexPath = resolveVariables(path.toString());
            }
        }

        // Crawler settings
        final Map<String, Object> crawlerConfig = (Map<String, Object>) luceneConfig.get("crawler");
        if (crawlerConfig != null) {
            applyCrawlerConfig(crawlerConfig);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyCrawlerConfig(final Map<String, Object> crawlerConfig) {
        if (crawlerConfig.containsKey("directories")) {
            final Object dirs = crawlerConfig.get("directories");
            if (dirs instanceof List) {
                this.directories = new ArrayList<>((List<String>) dirs);
            }
        }
        if (crawlerConfig.containsKey("include-patterns")) {
            final Object patterns = crawlerConfig.get("include-patterns");
            if (patterns instanceof List) {
                this.includePatterns = new ArrayList<>((List<String>) patterns);
            }
        }
        if (crawlerConfig.containsKey("exclude-patterns")) {
            final Object patterns = crawlerConfig.get("exclude-patterns");
            if (patterns instanceof List) {
                this.excludePatterns = new ArrayList<>((List<String>) patterns);
            }
        }
        if (crawlerConfig.containsKey("thread-pool-size")) {
            this.threadPoolSize = ((Number) crawlerConfig.get("thread-pool-size")).intValue();
        }
        if (crawlerConfig.containsKey("batch-size")) {
            this.batchSize = ((Number) crawlerConfig.get("batch-size")).intValue();
        }
        if (crawlerConfig.containsKey("batch-timeout-ms")) {
            this.batchTimeoutMs = ((Number) crawlerConfig.get("batch-timeout-ms")).longValue();
        }
        if (crawlerConfig.containsKey("watch-enabled")) {
            this.watchEnabled = (Boolean) crawlerConfig.get("watch-enabled");
        }
        if (crawlerConfig.containsKey("watch-poll-interval-ms")) {
            this.watchPollIntervalMs = ((Number) crawlerConfig.get("watch-poll-interval-ms")).longValue();
        }
        if (crawlerConfig.containsKey("bulk-index-threshold")) {
            this.bulkIndexThreshold = ((Number) crawlerConfig.get("bulk-index-threshold")).intValue();
        }
        if (crawlerConfig.containsKey("slow-nrt-refresh-interval-ms")) {
            this.slowNrtRefreshIntervalMs = ((Number) crawlerConfig.get("slow-nrt-refresh-interval-ms")).longValue();
        }
        if (crawlerConfig.containsKey("max-content-length")) {
            this.maxContentLength = ((Number) crawlerConfig.get("max-content-length")).longValue();
        }
        if (crawlerConfig.containsKey("extract-metadata")) {
            this.extractMetadata = (Boolean) crawlerConfig.get("extract-metadata");
        }
        if (crawlerConfig.containsKey("detect-language")) {
            this.detectLanguage = (Boolean) crawlerConfig.get("detect-language");
        }
        if (crawlerConfig.containsKey("crawl-on-startup")) {
            this.crawlOnStartup = (Boolean) crawlerConfig.get("crawl-on-startup");
        }
        if (crawlerConfig.containsKey("progress-notification-files")) {
            this.progressNotificationFiles = ((Number) crawlerConfig.get("progress-notification-files")).intValue();
        }
        if (crawlerConfig.containsKey("progress-notification-interval-ms")) {
            this.progressNotificationIntervalMs = ((Number) crawlerConfig.get("progress-notification-interval-ms")).longValue();
        }
        if (crawlerConfig.containsKey("reconciliation-enabled")) {
            this.reconciliationEnabled = (Boolean) crawlerConfig.get("reconciliation-enabled");
        }
    }

    private void applyEnvironmentOverrides() {
        // Index path from environment
        final String envIndexPath = System.getenv(ENV_INDEX_PATH);
        if (envIndexPath != null && !envIndexPath.trim().isEmpty()) {
            this.indexPath = envIndexPath.trim();
            logger.info("Index path from environment: {}", this.indexPath);
        }

        // Default index path if not set
        if (this.indexPath == null || this.indexPath.isEmpty()) {
            this.indexPath = Paths.get(System.getProperty("user.home"), CONFIG_DIR, "luceneindex").toString();
        }

        // Crawler directories from environment (overrides all other sources)
        final String envDirs = System.getenv(ENV_CRAWLER_DIRECTORIES);
        if (envDirs != null && !envDirs.trim().isEmpty()) {
            this.directories = new ArrayList<>();
            for (final String dir : envDirs.split(",")) {
                final String trimmed = dir.trim();
                if (!trimmed.isEmpty()) {
                    this.directories.add(trimmed);
                }
            }
            logger.info("Crawler directories from environment: {}", this.directories);
        }

        // System property for index path
        final String propIndexPath = System.getProperty("lucene.index.path");
        if (propIndexPath != null && !propIndexPath.isEmpty()) {
            this.indexPath = propIndexPath;
        }
    }

    private void determineProfile() {
        // Check system property for profile (supports both old Spring-style and new style)
        final String profile = System.getProperty(PROP_PROFILES_ACTIVE,
                System.getProperty("profile", "default"));
        this.deployedMode = "deployed".equalsIgnoreCase(profile);
    }

    /**
     * Resolve variables in strings like ${VAR:default}
     */
    private String resolveVariables(final String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }

        String result = value;
        int start;
        while ((start = result.indexOf("${")) >= 0) {
            final int end = result.indexOf("}", start);
            if (end < 0) {
                break;
            }

            final String varExpr = result.substring(start + 2, end);
            final String[] parts = varExpr.split(":", 2);
            final String varName = parts[0];
            final String defaultValue = parts.length > 1 ? parts[1] : "";

            // Check environment first, then system properties
            String replacement = System.getenv(varName);
            if (replacement == null || replacement.isEmpty()) {
                replacement = System.getProperty(varName, defaultValue);
            }

            // Handle nested ${user.home} type variables
            if (replacement.contains("${")) {
                replacement = resolveVariables(replacement);
            }

            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }

        return result;
    }

    public static Path getUserConfigPath() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR, USER_CONFIG_FILE);
    }

    public static Path getConfigDirectory() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR);
    }

    public boolean isEnvironmentOverrideActive() {
        final String envDirs = System.getenv(ENV_CRAWLER_DIRECTORIES);
        return envDirs != null && !envDirs.trim().isEmpty();
    }

    // Getters
    public String getIndexPath() {
        return indexPath;
    }

    public long getNrtRefreshIntervalMs() {
        return nrtRefreshIntervalMs;
    }

    public List<String> getDirectories() {
        return directories;
    }

    public void setDirectories(final List<String> directories) {
        this.directories = new ArrayList<>(directories);
    }

    public List<String> getIncludePatterns() {
        return includePatterns;
    }

    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public long getBatchTimeoutMs() {
        return batchTimeoutMs;
    }

    public boolean isWatchEnabled() {
        return watchEnabled;
    }

    public long getWatchPollIntervalMs() {
        return watchPollIntervalMs;
    }

    public int getBulkIndexThreshold() {
        return bulkIndexThreshold;
    }

    public long getSlowNrtRefreshIntervalMs() {
        return slowNrtRefreshIntervalMs;
    }

    public long getMaxContentLength() {
        return maxContentLength;
    }

    public boolean isExtractMetadata() {
        return extractMetadata;
    }

    public boolean isDetectLanguage() {
        return detectLanguage;
    }

    public boolean isCrawlOnStartup() {
        return crawlOnStartup;
    }

    public int getProgressNotificationFiles() {
        return progressNotificationFiles;
    }

    public long getProgressNotificationIntervalMs() {
        return progressNotificationIntervalMs;
    }

    public boolean isDeployedMode() {
        return deployedMode;
    }

    public boolean isReconciliationEnabled() {
        return reconciliationEnabled;
    }
}
