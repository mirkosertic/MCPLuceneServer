package de.mirkosertic.mcp.luceneserver.crawler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages persistent storage of crawler configuration in ~/.mcplucene/config.yaml
 * <p>
 * Handles loading/saving crawlable directories with thread-safe operations and
 * graceful error handling. Supports environment variable override via LUCENE_CRAWLER_DIRECTORIES.
 */
public class CrawlerConfigurationManager {

    private static final Logger logger = LoggerFactory.getLogger(CrawlerConfigurationManager.class);
    private static final String ENV_DIRECTORIES = "LUCENE_CRAWLER_DIRECTORIES";
    private static final String CONFIG_DIR = ".mcplucene";
    private static final String CONFIG_FILE = "config.yaml";
    private static final String CRAWL_STATE_FILE = "crawl-state.yaml";

    private final Yaml yaml;

    public CrawlerConfigurationManager() {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        this.yaml = new Yaml(options);
    }

    /**
     * Initialize the configuration manager. Ensures config file exists.
     */
    public void init() {
        ensureConfigFileExists();
    }

    /**
     * Load directories from configuration with priority:
     * 1. Environment variable LUCENE_CRAWLER_DIRECTORIES
     * 2. ~/.mcplucene/config.yaml
     * 3. Empty list
     *
     * @return List of directory paths, never null
     */
    public synchronized List<String> loadDirectories() {
        // Priority 1: Check environment variable
        final String envDirs = System.getenv(ENV_DIRECTORIES);
        if (envDirs != null && !envDirs.trim().isEmpty()) {
            logger.info("Loading directories from environment variable {}", ENV_DIRECTORIES);
            final List<String> dirs = new ArrayList<>();
            for (final String dir : envDirs.split(",")) {
                final String trimmed = dir.trim();
                if (!trimmed.isEmpty()) {
                    dirs.add(trimmed);
                }
            }
            return dirs;
        }

        // Priority 2: Load from config file
        final Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            logger.debug("Config file does not exist: {}", configPath);
            return new ArrayList<>();
        }

        try (final Reader reader = Files.newBufferedReader(configPath)) {
            final Map<String, Object> config = yaml.load(reader);
            if (config == null) {
                logger.debug("Config file is empty: {}", configPath);
                return new ArrayList<>();
            }

            final List<String> directories = extractDirectories(config);
            logger.info("Loaded {} directories from {}", directories.size(), configPath);
            return directories;
        } catch (final IOException e) {
            logger.error("Failed to load config file: {}", configPath, e);
            return new ArrayList<>();
        } catch (final Exception e) {
            logger.error("Failed to parse config file: {}", configPath, e);
            return new ArrayList<>();
        }
    }

    /**
     * Save directories to ~/.mcplucene/config.yaml
     *
     * @param directories List of directory paths to persist
     * @throws IOException if save operation fails
     */
    public synchronized void saveDirectories(final List<String> directories) throws IOException {
        final Path configPath = getConfigPath();
        ensureConfigDirectoryExists();

        final Map<String, Object> config = new HashMap<>();
        final Map<String, Object> luceneConfig = new HashMap<>();
        final Map<String, Object> crawlerConfig = new HashMap<>();

        crawlerConfig.put("directories", new ArrayList<>(directories));
        luceneConfig.put("crawler", crawlerConfig);
        config.put("lucene", luceneConfig);

        try (final Writer writer = Files.newBufferedWriter(configPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            yaml.dump(config, writer);
            logger.info("Saved {} directories to {}", directories.size(), configPath);
        }
    }

    /**
     * Add a directory to the configuration
     *
     * @param path Directory path to add
     * @throws IOException if save operation fails
     */
    public synchronized void addDirectory(final String path) throws IOException {
        final List<String> directories = loadDirectories();

        // Avoid duplicates
        if (!directories.contains(path)) {
            directories.add(path);
            saveDirectories(directories);
            logger.info("Added directory to config: {}", path);
        } else {
            logger.debug("Directory already in config: {}", path);
        }
    }

    /**
     * Remove a directory from the configuration
     *
     * @param path Directory path to remove
     * @throws IOException if save operation fails
     */
    public synchronized void removeDirectory(final String path) throws IOException {
        final List<String> directories = loadDirectories();

        if (directories.remove(path)) {
            saveDirectories(directories);
            logger.info("Removed directory from config: {}", path);
        } else {
            logger.debug("Directory not found in config: {}", path);
        }
    }

    /**
     * Check if environment variable override is active
     *
     * @return true if LUCENE_CRAWLER_DIRECTORIES is set
     */
    public boolean isEnvironmentOverrideActive() {
        final String envDirs = System.getenv(ENV_DIRECTORIES);
        return envDirs != null && !envDirs.trim().isEmpty();
    }

    /**
     * Get the path to the configuration file
     *
     * @return Path to ~/.mcplucene/config.yaml
     */
    public Path getConfigPath() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE);
    }

    /**
     * Ensure config file exists, creating it with defaults if needed
     */
    private void ensureConfigFileExists() {
        final Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            try {
                ensureConfigDirectoryExists();
                saveDirectories(new ArrayList<>());
                logger.info("Created default config file: {}", configPath);
            } catch (final IOException e) {
                logger.warn("Failed to create default config file: {}", configPath, e);
            }
        }
    }

    /**
     * Ensure config directory exists
     */
    private void ensureConfigDirectoryExists() throws IOException {
        final Path configDir = getConfigPath().getParent();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
            logger.debug("Created config directory: {}", configDir);
        }
    }

    // ==================== Crawl State Persistence ====================

    /**
     * Load the persisted crawl state from {@code ~/.mcplucene/crawl-state.yaml}.
     *
     * @return the last saved {@link CrawlState}, or {@code null} if the file does not exist
     *         or cannot be parsed
     */
    @SuppressWarnings("unchecked")
    public synchronized CrawlState loadCrawlState() {
        final Path statePath = getCrawlStatePath();
        if (!Files.exists(statePath)) {
            logger.debug("Crawl state file does not exist: {}", statePath);
            return null;
        }

        try (final Reader reader = Files.newBufferedReader(statePath)) {
            final Map<String, Object> stateMap = yaml.load(reader);
            if (stateMap == null) {
                logger.debug("Crawl state file is empty: {}", statePath);
                return null;
            }

            final long lastCompletionTimeMs = ((Number) stateMap.getOrDefault("lastCompletionTimeMs", 0L)).longValue();
            final long lastDocumentCount = ((Number) stateMap.getOrDefault("lastDocumentCount", 0L)).longValue();
            final String lastCrawlMode = (String) stateMap.getOrDefault("lastCrawlMode", "full");

            logger.info("Loaded crawl state: completionTime={}, docs={}, mode={}",
                    lastCompletionTimeMs, lastDocumentCount, lastCrawlMode);
            return new CrawlState(lastCompletionTimeMs, lastDocumentCount, lastCrawlMode);

        } catch (final IOException e) {
            logger.error("Failed to load crawl state file: {}", statePath, e);
            return null;
        } catch (final ClassCastException e) {
            logger.error("Invalid crawl state structure in: {}", statePath, e);
            return null;
        }
    }

    /**
     * Persist crawl state to {@code ~/.mcplucene/crawl-state.yaml}.
     * Called only after a crawl completes successfully.
     *
     * @param crawlState the state to persist; must not be null
     * @throws IOException if the write fails
     */
    public synchronized void saveCrawlState(final CrawlState crawlState) throws IOException {
        ensureConfigDirectoryExists();
        final Path statePath = getCrawlStatePath();

        final Map<String, Object> stateMap = new HashMap<>();
        stateMap.put("lastCompletionTimeMs", crawlState.lastCompletionTimeMs());
        stateMap.put("lastDocumentCount", crawlState.lastDocumentCount());
        stateMap.put("lastCrawlMode", crawlState.lastCrawlMode());

        try (final Writer writer = Files.newBufferedWriter(statePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            yaml.dump(stateMap, writer);
            logger.info("Saved crawl state: completionTime={}, docs={}, mode={}",
                    crawlState.lastCompletionTimeMs(), crawlState.lastDocumentCount(), crawlState.lastCrawlMode());
        }
    }

    /**
     * Get the path to the crawl state file.
     *
     * @return path to {@code ~/.mcplucene/crawl-state.yaml}
     */
    public Path getCrawlStatePath() {
        return Paths.get(System.getProperty("user.home"), CONFIG_DIR, CRAWL_STATE_FILE);
    }

    /**
     * Extract directories list from parsed YAML config
     */
    @SuppressWarnings("unchecked")
    private List<String> extractDirectories(final Map<String, Object> config) {
        try {
            final Map<String, Object> luceneConfig = (Map<String, Object>) config.get("lucene");
            if (luceneConfig == null) {
                return new ArrayList<>();
            }

            final Map<String, Object> crawlerConfig = (Map<String, Object>) luceneConfig.get("crawler");
            if (crawlerConfig == null) {
                return new ArrayList<>();
            }

            final Object directoriesObj = crawlerConfig.get("directories");
            if (directoriesObj instanceof List) {
                return new ArrayList<>((List<String>) directoriesObj);
            }

            return new ArrayList<>();
        } catch (final ClassCastException e) {
            logger.error("Invalid config structure", e);
            return new ArrayList<>();
        }
    }
}
