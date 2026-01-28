package de.mirkosertic.mcp.luceneserver.crawler;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "lucene.crawler")
public class CrawlerProperties {

    private static final Logger logger = LoggerFactory.getLogger(CrawlerProperties.class);

    private final CrawlerConfigurationManager configManager;
    private List<String> directories = new ArrayList<>();
    private List<String> includePatterns = List.of(
            "*.pdf",
            "*.doc",
            "*.docx",
            "*.odt",
            "*.ppt",
            "*.pptx",
            "*.xls",
            "*.xlsx",
            "*.ods",
            "*.txt"
    );
    private List<String> excludePatterns = List.of(
            "**/node_modules/**",
            "**/.git/**",
            "**/target/**",
            "**/build/**"
    );
    private int threadPoolSize = 4;
    private int batchSize = 100;
    private long batchTimeoutMs = 5000;
    private boolean watchEnabled = true;
    private long watchPollIntervalMs = 2000;
    private int bulkIndexThreshold = 1000;
    private long slowNrtRefreshIntervalMs = 5000;
    private long maxContentLength = -1; // -1 = unlimited, otherwise limit in characters
    private boolean extractMetadata = true;
    private boolean detectLanguage = true;
    private boolean crawlOnStartup = true;
    private int progressNotificationFiles = 100;
    private long progressNotificationIntervalMs = 30000;

    public CrawlerProperties(final CrawlerConfigurationManager configManager) {
        this.configManager = configManager;
    }

    @PostConstruct
    public void initializeFromExternalConfig() {
        // Only load from config file if directories not already set
        // (env var or application.yaml take precedence via Spring's binding)
        if (directories.isEmpty()) {
            final List<String> configDirs = configManager.loadDirectories();
            if (!configDirs.isEmpty()) {
                this.directories = new ArrayList<>(configDirs);
                logger.info("Loaded {} directories from {}",
                    directories.size(), configManager.getConfigPath());
            }
        }
    }

    public List<String> getDirectories() {
        return directories;
    }

    public void setDirectories(final List<String> directories) {
        this.directories = directories;
    }

    public List<String> getIncludePatterns() {
        return includePatterns;
    }

    public void setIncludePatterns(final List<String> includePatterns) {
        this.includePatterns = includePatterns;
    }

    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    public void setExcludePatterns(final List<String> excludePatterns) {
        this.excludePatterns = excludePatterns;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public void setThreadPoolSize(final int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(final int batchSize) {
        this.batchSize = batchSize;
    }

    public long getBatchTimeoutMs() {
        return batchTimeoutMs;
    }

    public void setBatchTimeoutMs(final long batchTimeoutMs) {
        this.batchTimeoutMs = batchTimeoutMs;
    }

    public boolean isWatchEnabled() {
        return watchEnabled;
    }

    public void setWatchEnabled(final boolean watchEnabled) {
        this.watchEnabled = watchEnabled;
    }

    public long getWatchPollIntervalMs() {
        return watchPollIntervalMs;
    }

    public void setWatchPollIntervalMs(final long watchPollIntervalMs) {
        this.watchPollIntervalMs = watchPollIntervalMs;
    }

    public int getBulkIndexThreshold() {
        return bulkIndexThreshold;
    }

    public void setBulkIndexThreshold(final int bulkIndexThreshold) {
        this.bulkIndexThreshold = bulkIndexThreshold;
    }

    public long getSlowNrtRefreshIntervalMs() {
        return slowNrtRefreshIntervalMs;
    }

    public void setSlowNrtRefreshIntervalMs(final long slowNrtRefreshIntervalMs) {
        this.slowNrtRefreshIntervalMs = slowNrtRefreshIntervalMs;
    }

    public long getMaxContentLength() {
        return maxContentLength;
    }

    public void setMaxContentLength(final long maxContentLength) {
        this.maxContentLength = maxContentLength;
    }

    public boolean isExtractMetadata() {
        return extractMetadata;
    }

    public void setExtractMetadata(final boolean extractMetadata) {
        this.extractMetadata = extractMetadata;
    }

    public boolean isDetectLanguage() {
        return detectLanguage;
    }

    public void setDetectLanguage(final boolean detectLanguage) {
        this.detectLanguage = detectLanguage;
    }

    public boolean isCrawlOnStartup() {
        return crawlOnStartup;
    }

    public void setCrawlOnStartup(final boolean crawlOnStartup) {
        this.crawlOnStartup = crawlOnStartup;
    }

    public int getProgressNotificationFiles() {
        return progressNotificationFiles;
    }

    public void setProgressNotificationFiles(final int progressNotificationFiles) {
        this.progressNotificationFiles = progressNotificationFiles;
    }

    public long getProgressNotificationIntervalMs() {
        return progressNotificationIntervalMs;
    }

    public void setProgressNotificationIntervalMs(final long progressNotificationIntervalMs) {
        this.progressNotificationIntervalMs = progressNotificationIntervalMs;
    }
}
