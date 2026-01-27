package de.mirkosertic.mcp.luceneserver.crawler;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
@Component
public class CrawlerConfigurationManager {

    private static final Logger logger = LoggerFactory.getLogger(CrawlerConfigurationManager.class);
    private static final String ENV_DIRECTORIES = "LUCENE_CRAWLER_DIRECTORIES";
    private static final String CONFIG_DIR = ".mcplucene";
    private static final String CONFIG_FILE = "config.yaml";

    private final Yaml yaml;

    public CrawlerConfigurationManager() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        this.yaml = new Yaml(options);
    }

    @PostConstruct
    void init() {
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
        String envDirs = System.getenv(ENV_DIRECTORIES);
        if (envDirs != null && !envDirs.trim().isEmpty()) {
            logger.info("Loading directories from environment variable {}", ENV_DIRECTORIES);
            List<String> dirs = new ArrayList<>();
            for (String dir : envDirs.split(",")) {
                String trimmed = dir.trim();
                if (!trimmed.isEmpty()) {
                    dirs.add(trimmed);
                }
            }
            return dirs;
        }

        // Priority 2: Load from config file
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            logger.debug("Config file does not exist: {}", configPath);
            return new ArrayList<>();
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            Map<String, Object> config = yaml.load(reader);
            if (config == null) {
                logger.debug("Config file is empty: {}", configPath);
                return new ArrayList<>();
            }

            List<String> directories = extractDirectories(config);
            logger.info("Loaded {} directories from {}", directories.size(), configPath);
            return directories;
        } catch (IOException e) {
            logger.error("Failed to load config file: {}", configPath, e);
            return new ArrayList<>();
        } catch (Exception e) {
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
    public synchronized void saveDirectories(List<String> directories) throws IOException {
        Path configPath = getConfigPath();
        ensureConfigDirectoryExists();

        Map<String, Object> config = new HashMap<>();
        Map<String, Object> luceneConfig = new HashMap<>();
        Map<String, Object> crawlerConfig = new HashMap<>();

        crawlerConfig.put("directories", new ArrayList<>(directories));
        luceneConfig.put("crawler", crawlerConfig);
        config.put("lucene", luceneConfig);

        try (Writer writer = Files.newBufferedWriter(configPath,
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
    public synchronized void addDirectory(String path) throws IOException {
        List<String> directories = loadDirectories();

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
    public synchronized void removeDirectory(String path) throws IOException {
        List<String> directories = loadDirectories();

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
        String envDirs = System.getenv(ENV_DIRECTORIES);
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
        Path configPath = getConfigPath();
        if (!Files.exists(configPath)) {
            try {
                ensureConfigDirectoryExists();
                saveDirectories(new ArrayList<>());
                logger.info("Created default config file: {}", configPath);
            } catch (IOException e) {
                logger.warn("Failed to create default config file: {}", configPath, e);
            }
        }
    }

    /**
     * Ensure config directory exists
     */
    private void ensureConfigDirectoryExists() throws IOException {
        Path configDir = getConfigPath().getParent();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
            logger.debug("Created config directory: {}", configDir);
        }
    }

    /**
     * Extract directories list from parsed YAML config
     */
    @SuppressWarnings("unchecked")
    private List<String> extractDirectories(Map<String, Object> config) {
        try {
            Map<String, Object> luceneConfig = (Map<String, Object>) config.get("lucene");
            if (luceneConfig == null) {
                return new ArrayList<>();
            }

            Map<String, Object> crawlerConfig = (Map<String, Object>) luceneConfig.get("crawler");
            if (crawlerConfig == null) {
                return new ArrayList<>();
            }

            Object directoriesObj = crawlerConfig.get("directories");
            if (directoriesObj instanceof List) {
                return new ArrayList<>((List<String>) directoriesObj);
            }

            return new ArrayList<>();
        } catch (ClassCastException e) {
            logger.error("Invalid config structure", e);
            return new ArrayList<>();
        }
    }
}
