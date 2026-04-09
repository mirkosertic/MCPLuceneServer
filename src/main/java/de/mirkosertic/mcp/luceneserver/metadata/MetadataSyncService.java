package de.mirkosertic.mcp.luceneserver.metadata;

import de.mirkosertic.mcp.luceneserver.crawler.DocumentCrawlerService;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Synchronizes metadata changes from the database into the Lucene index.
 * <p>
 * When database metadata changes but the underlying file on disk is unchanged,
 * this service detects those changes and re-indexes affected files with the
 * fresh metadata.
 * <p>
 * Concurrency note: this service is NOT thread-safe with ongoing crawls.
 * The sync scheduler runs every N minutes; if a crawl is active at the same time
 * a race condition may cause a file to be indexed twice.  For most use cases this
 * is harmless (idempotent re-index), but it should be addressed with explicit
 * synchronization if strict consistency is required.
 */
public class MetadataSyncService {

    private static final Logger logger = LoggerFactory.getLogger(MetadataSyncService.class);

    private static final String SYNC_STATE_FILE = "metadata-sync-state.yaml";

    private final JdbcMetadataConfig config;
    private final JdbcConnectionPool connectionPool;
    private final SqlTemplateParser templateParser;
    private final DocumentCrawlerService crawlerService;
    private final LuceneIndexService indexService;

    public record SyncResult(
            int totalChanges,
            int reindexed,
            int deleted,
            int errors,
            Instant lastSync
    ) {}

    private record SyncRecord(String filePath, Instant timestamp) {}

    public MetadataSyncService(
            final JdbcMetadataConfig config,
            final JdbcConnectionPool connectionPool,
            final SqlTemplateParser templateParser,
            final DocumentCrawlerService crawlerService,
            final LuceneIndexService indexService) {
        this.config = config;
        this.connectionPool = connectionPool;
        this.templateParser = templateParser;
        this.crawlerService = crawlerService;
        this.indexService = indexService;
    }

    /**
     * Synchronize metadata changes detected since the last run.
     *
     * <ol>
     *   <li>Load the last-sync timestamp from the state file.</li>
     *   <li>Query the DB for records changed after that timestamp.</li>
     *   <li>Re-index files that still exist on disk.</li>
     *   <li>Remove index entries for files that no longer exist.</li>
     *   <li>Commit and persist the new sync timestamp.</li>
     * </ol>
     *
     * @return statistics about this sync run
     */
    public SyncResult syncMetadata() {
        if (!config.sync().enabled()) {
            logger.debug("Metadata sync is disabled");
            return new SyncResult(0, 0, 0, 0, Instant.now());
        }

        final Instant lastSync = loadLastSyncTimestamp();
        logger.info("Starting metadata sync from {}", lastSync);

        final List<SyncRecord> changes = queryChangedMetadata(lastSync);
        logger.info("Found {} metadata changes since {}", changes.size(), lastSync);

        if (changes.isEmpty()) {
            return new SyncResult(0, 0, 0, 0, Instant.now());
        }

        int reindexed = 0;
        int deleted = 0;
        int errors = 0;

        for (final SyncRecord record : changes) {
            try {
                final Path file = Paths.get(record.filePath());
                if (!Files.exists(file)) {
                    logger.warn("File deleted but metadata exists: {} — removing from index", record.filePath());
                    indexService.deleteDocumentByPath(record.filePath());
                    deleted++;
                } else {
                    logger.debug("Re-indexing file with updated metadata: {}", record.filePath());
                    crawlerService.reindexSingleFile(file);
                    reindexed++;
                }
            } catch (final IOException e) {
                logger.error("Failed to sync metadata for {}: {}", record.filePath(), e.getMessage());
                errors++;
            }
        }

        try {
            indexService.commit();
        } catch (final IOException e) {
            logger.error("Failed to commit metadata sync changes: {}", e.getMessage());
            errors++;
        }

        final Instant now = Instant.now();
        saveLastSyncTimestamp(now);

        logger.info("Metadata sync complete: {} total, {} re-indexed, {} deleted, {} errors",
                changes.size(), reindexed, deleted, errors);

        return new SyncResult(changes.size(), reindexed, deleted, errors, now);
    }

    private List<SyncRecord> queryChangedMetadata(final Instant lastSync) {
        final String syncQuery = config.sync().query();
        if (syncQuery == null || syncQuery.isBlank()) {
            logger.warn("No sync.query configured — skipping metadata sync");
            return new ArrayList<>();
        }

        final SqlTemplateParser.ParsedTemplate template = templateParser.parse(syncQuery);
        final List<SyncRecord> results = new ArrayList<>();

        try (final Connection conn = connectionPool.getConnection();
             final PreparedStatement stmt = conn.prepareStatement(template.sql())) {

            stmt.setTimestamp(1, Timestamp.from(lastSync));
            if (config.queryTimeout() > 0) {
                stmt.setQueryTimeout(config.queryTimeout() / 1000);
            }

            try (final ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    final String filePathColumn = config.sync().filePathColumn();
                    final String filePath = filePathColumn != null
                            ? rs.getString(filePathColumn) : rs.getString(1);
                    final String timestampColumn = config.sync().timestampColumn();
                    final Timestamp ts = timestampColumn != null
                            ? rs.getTimestamp(timestampColumn) : null;

                    if (filePath == null) {
                        logger.warn("Sync query returned row with NULL file_path — skipping");
                        continue;
                    }

                    results.add(new SyncRecord(filePath, ts != null ? ts.toInstant() : Instant.now()));
                }
            }

        } catch (final SQLException e) {
            logger.error("Failed to query changed metadata: {}", e.getMessage());
            return new ArrayList<>();
        }

        return results;
    }

    private Instant loadLastSyncTimestamp() {
        final Path stateFile = getSyncStateFile();
        if (!Files.exists(stateFile)) {
            logger.info("No sync state found — starting from epoch (full sync)");
            return Instant.EPOCH;
        }

        try (final Reader reader = Files.newBufferedReader(stateFile)) {
            final Yaml yaml = new Yaml();
            final Map<String, Object> state = yaml.load(reader);
            if (state == null || !state.containsKey("lastSyncTimestamp")) {
                logger.warn("Invalid sync state file — starting from epoch");
                return Instant.EPOCH;
            }
            final Instant parsed = Instant.parse((String) state.get("lastSyncTimestamp"));
            logger.debug("Loaded last sync timestamp: {}", parsed);
            return parsed;
        } catch (final Exception e) {
            logger.error("Failed to load sync state from {}, starting from epoch: {}",
                    stateFile, e.getMessage());
            return Instant.EPOCH;
        }
    }

    private void saveLastSyncTimestamp(final Instant timestamp) {
        final Path stateFile = getSyncStateFile();
        try {
            Files.createDirectories(stateFile.getParent());
            final Map<String, Object> state = new HashMap<>();
            state.put("lastSyncTimestamp", timestamp.toString());

            final DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            final Yaml yaml = new Yaml(options);

            try (final Writer writer = Files.newBufferedWriter(stateFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                yaml.dump(state, writer);
            }
            logger.debug("Saved sync state: {}", timestamp);
        } catch (final IOException e) {
            logger.error("Failed to save sync state to {}: {}", stateFile, e.getMessage());
        }
    }

    private Path getSyncStateFile() {
        return Paths.get(System.getProperty("user.home")).resolve(".mcplucene").resolve(SYNC_STATE_FILE);
    }
}
