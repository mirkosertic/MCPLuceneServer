package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.config.BuildInfo;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.mcp.dto.Passage;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchDocument;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsCollectorManager;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.facet.sortedset.DefaultSortedSetDocValuesReaderState;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesFacetCounts;
import org.apache.lucene.facet.sortedset.SortedSetDocValuesReaderState;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Core Lucene index service that manages IndexWriter and SearcherManager.
 * Provides NRT (Near Real-Time) search capabilities with configurable refresh intervals.
 */
public class LuceneIndexService {

    private static final Logger logger = LoggerFactory.getLogger(LuceneIndexService.class);
    private static final String WRITE_LOCK_FILE = "write.lock";

    /**
     * States for long-running admin operations.
     */
    public enum AdminOperationState {
        IDLE,
        OPTIMIZING,
        PURGING,
        COMPLETED,
        FAILED
    }

    private final ApplicationConfig config;
    private final String indexPath;
    private volatile long nrtRefreshIntervalMs;

    private Directory directory;
    private IndexWriter indexWriter;
    private SearcherManager searcherManager;
    private ScheduledExecutorService refreshScheduler;
    private final Analyzer analyzer;
    private final DocumentIndexer documentIndexer;
    private boolean schemaUpgradeRequired = false;

    // Lock object for admin operation state transitions
    private final Object adminStateLock = new Object();

    // Admin operation state (all access must be synchronized on adminStateLock)
    private AdminOperationState adminState = AdminOperationState.IDLE;
    private String currentOperationId;
    private int operationProgressPercent;
    private String operationProgressMessage;
    private long operationStartTime;
    private String lastOperationResult;

    // Single-threaded executor for admin operations (ensures only one runs at a time)
    private final ExecutorService adminExecutor = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "lucene-admin-ops");
        t.setDaemon(true);
        return t;
    });

    public LuceneIndexService(final ApplicationConfig config,
                              final DocumentIndexer documentIndexer) {
        this.config = config;
        final Analyzer defaultAnalyzer = new UnicodeNormalizingAnalyzer();
        this.analyzer = new PerFieldAnalyzerWrapper(defaultAnalyzer, Map.of(
                "content_reversed", new ReverseUnicodeNormalizingAnalyzer()
        ));
        this.indexPath = config.getIndexPath();
        this.nrtRefreshIntervalMs = config.getNrtRefreshIntervalMs();
        this.documentIndexer = documentIndexer;
    }

    /**
     * Initialize the Lucene index. Must be called before using the service.
     * Detects schema version changes and sets schemaUpgradeRequired flag if needed.
     */
    public void init() throws IOException {
        final Path path = Path.of(indexPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
            logger.info("Created index directory: {}", path.toAbsolutePath());
        }

        directory = FSDirectory.open(path);

        // Check if index exists and read schema version
        int storedSchemaVersion = -1;
        boolean indexExists = false;
        if (DirectoryReader.indexExists(directory)) {
            indexExists = true;
            try (final DirectoryReader reader = DirectoryReader.open(directory)) {
                final Map<String, String> userData = reader.getIndexCommit().getUserData();
                final String versionStr = userData.get("schema_version");
                if (versionStr != null) {
                    storedSchemaVersion = Integer.parseInt(versionStr);
                    logger.info("Existing index has schema version: {}", storedSchemaVersion);
                } else {
                    logger.warn("Existing index has no schema version (legacy index)");
                }
            }
        }

        // Open IndexWriter
        final IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        indexWriter = new IndexWriter(directory, config);

        // Set commit user data with current schema version and software version
        indexWriter.setLiveCommitData(Map.of(
                "schema_version", String.valueOf(DocumentIndexer.SCHEMA_VERSION),
                "software_version", BuildInfo.getVersion()
        ).entrySet());

        // Commit to ensure index files and metadata are created
        indexWriter.commit();

        // Determine if schema upgrade is required
        if (indexExists) {
            if (storedSchemaVersion == -1) {
                // Legacy index with no version - requires upgrade
                schemaUpgradeRequired = true;
                logger.warn("Schema upgrade required: legacy index detected (no version metadata)");
            } else if (storedSchemaVersion != DocumentIndexer.SCHEMA_VERSION) {
                schemaUpgradeRequired = true;
                logger.warn("Schema upgrade required: version mismatch (stored={}, current={})",
                        storedSchemaVersion, DocumentIndexer.SCHEMA_VERSION);
            } else {
                logger.info("Schema version matches: {}", storedSchemaVersion);
            }
        } else {
            logger.info("New index created with schema version: {}", DocumentIndexer.SCHEMA_VERSION);
        }

        // Initialize SearcherManager from IndexWriter for NRT support
        // This allows searchers to see uncommitted changes directly from writer's RAM buffer
        searcherManager = new SearcherManager(indexWriter, null);

        // Start background thread for periodic refresh
        // This avoids penalizing individual queries with refresh overhead
        refreshScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "lucene-nrt-refresh");
            t.setDaemon(true);
            return t;
        });
        refreshScheduler.scheduleAtFixedRate(this::maybeRefreshSearcher,
                nrtRefreshIntervalMs, nrtRefreshIntervalMs, TimeUnit.MILLISECONDS);

        logger.info("Lucene index initialized at: {} with NRT refresh interval {}ms",
                path.toAbsolutePath(), nrtRefreshIntervalMs);
    }

    private void maybeRefreshSearcher() {
        try {
            searcherManager.maybeRefresh();
        } catch (final IOException e) {
            logger.warn("Failed to refresh SearcherManager", e);
        }
    }

    /**
     * Close the index service and release all resources.
     */
    public void close() throws IOException {
        // Stop background refresh first
        if (refreshScheduler != null) {
            refreshScheduler.shutdown();
            try {
                if (!refreshScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    refreshScheduler.shutdownNow();
                }
            } catch (final InterruptedException e) {
                refreshScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Stop admin executor
        adminExecutor.shutdown();
        try {
            if (!adminExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                adminExecutor.shutdownNow();
            }
        } catch (final InterruptedException e) {
            adminExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close SearcherManager before IndexWriter
        if (searcherManager != null) {
            searcherManager.close();
        }

        if (indexWriter != null) {
            indexWriter.close();
        }
        if (directory != null) {
            directory.close();
        }
        logger.info("Lucene index closed");
    }

    public IndexWriter getIndexWriter() {
        return indexWriter;
    }

    public SearchResult search(final String queryString, final String filterField, final String filterValue,
                               final int page, final int pageSize) throws IOException, ParseException {

        if (queryString == null || queryString.isBlank()) {
            return new SearchResult(List.of(), 0, page, pageSize, Map.of());
        }

        // Acquire searcher from SearcherManager - this is thread-safe and reuses readers
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            // Build the main query (allow leading wildcards in syntax)
            final QueryParser parser = new QueryParser("content", analyzer);
            parser.setAllowLeadingWildcard(true);
            final Query mainQuery = parser.parse(queryString);

            // Rewrite leading wildcards to use the reversed-token field for efficiency
            final Query optimizedQuery = rewriteLeadingWildcards(mainQuery);

            // Apply filter if provided
            final Query finalQuery;
            if (filterField != null && !filterField.isBlank() &&
                filterValue != null && !filterValue.isBlank()) {
                final BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(optimizedQuery, BooleanClause.Occur.MUST);

                final QueryParser filterParser = new QueryParser(filterField, analyzer);
                final Query filterQuery = filterParser.parse(filterValue);
                builder.add(filterQuery, BooleanClause.Occur.FILTER);

                finalQuery = builder.build();
            } else {
                finalQuery = optimizedQuery;
            }

            // Calculate pagination
            final int startIndex = page * pageSize;
            final int maxResults = startIndex + pageSize;

            // Perform combined search and facet collection (Lucene 10+ API)
            final FacetsCollectorManager facetsCollectorManager = new FacetsCollectorManager();
            final FacetsCollectorManager.FacetsResult result = FacetsCollectorManager.search(
                    searcher, finalQuery, maxResults, facetsCollectorManager);

            final FacetsCollector facetsCollector = result.facetsCollector();
            final TopDocs topDocs = result.topDocs();
            final long totalHits = topDocs.totalHits.value();

            // Build a PassageAwareHighlighter for passage generation.
            // It uses IndividualPassageFormatter to return each passage as a separate
            // object (with its own Lucene score and character offsets) instead of
            // joining them into a single string.  The underlying highlightFieldsAsObjects
            // API reads term vectors (positions + offsets) stored in the index, which is
            // critical for correct <em> tagging when ICUFoldingFilter is in play.
            // We use mainQuery (before the filter clause) so that only the user's
            // actual search terms are highlighted, not filter values.
            // maxNoHighlightPassages=1 ensures a fallback passage is returned even if
            // no terms match within the highlighter's view of the document.
            final int maxPassages = config.getMaxPassages();
            final int maxPassageCharLength = config.getMaxPassageCharLength();
            final PassageAwareHighlighter highlighter = new PassageAwareHighlighter(
                    UnifiedHighlighter.builder(searcher, analyzer)
                            .withMaxLength(10_000) // read up to 10 000 chars of stored value to find more passages
                            .withFormatter(new IndividualPassageFormatter())
                            .withHandleMultiTermQuery(true)
                            .withBreakIterator(BreakIterator::getSentenceInstance)
                            .withMaxNoHighlightPassages(1)
            );

            // Extract query terms for termCoverage calculation.
            // Query.toString() produces a human-readable representation; we strip
            // field prefixes and punctuation to get the raw terms.
            final Set<String> queryTerms = extractQueryTerms(mainQuery);

            // Collect results for the requested page
            final List<SearchDocument> results = new ArrayList<>();
            final ScoreDoc[] scoreDocs = topDocs.scoreDocs;

            for (int i = startIndex; i < scoreDocs.length && i < maxResults; i++) {
                final Document doc = searcher.storedFields().document(scoreDocs[i].doc);

                // Generate structured passages using UnifiedHighlighter.highlight()
                // which reads term-vector offsets from the index for precise <em> tagging.
                logger.debug("Query for highlighting: {}", mainQuery);
                final String content = doc.get("content");
                final List<Passage> passages = createPassages(
                        content, mainQuery, highlighter, maxPassages, maxPassageCharLength, queryTerms, scoreDocs[i]);

                final SearchDocument searchDoc = SearchDocument.builder()
                        .score(scoreDocs[i].score)
                        .filePath(doc.get("file_path"))
                        .fileName(doc.get("file_name"))
                        .title(doc.get("title"))
                        .author(doc.get("author"))
                        .creator(doc.get("creator"))
                        .subject(doc.get("subject"))
                        .language(doc.get("language"))
                        .fileExtension(doc.get("file_extension"))
                        .fileType(doc.get("file_type"))
                        .fileSize(doc.get("file_size"))
                        .createdDate(doc.get("created_date"))
                        .modifiedDate(doc.get("modified_date"))
                        .indexedDate(doc.get("indexed_date"))
                        .passages(passages)
                        .build();

                results.add(searchDoc);
            }

            // Build facets from collected data
            final Map<String, List<FacetValue>> facets = buildFacets(searcher, facetsCollector);

            return new SearchResult(results, totalHits, page, pageSize, facets);
        } finally {
            // Always release the searcher back to the manager
            searcherManager.release(searcher);
        }
    }

    public long getDocumentCount() throws IOException {
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            searcherManager.release(searcher);
        }
    }

    public void setNrtRefreshInterval(final long intervalMs) {
        this.nrtRefreshIntervalMs = intervalMs;
        logger.info("NRT refresh interval changed to {}ms", intervalMs);
    }

    public void commit() throws IOException {
        indexWriter.commit();
    }

    /**
     * Force an immediate refresh of the searcher to see recently committed changes.
     * Primarily used for testing; in production the scheduled refresh is preferred.
     */
    public void refreshSearcher() throws IOException {
        // maybeRefreshBlocking guarantees the refresh happens before returning
        searcherManager.maybeRefreshBlocking();
    }

    public Set<String> getIndexedFields() throws IOException {
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            final Set<String> fields = new HashSet<>();
            for (final var leafReaderContext : searcher.getIndexReader().leaves()) {
                for (final var fieldInfo : leafReaderContext.reader().getFieldInfos()) {
                    fields.add(fieldInfo.name);
                }
            }
            return fields;
        } finally {
            searcherManager.release(searcher);
        }
    }

    public Map<String, Object> getDocumentByFilePath(final String filePath) throws IOException {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }

        final IndexSearcher searcher = searcherManager.acquire();
        try {
            // Create exact match query for file_path field
            final Query query = new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term("file_path", filePath));

            // Search for the document
            final TopDocs topDocs = searcher.search(query, 1);

            if (topDocs.totalHits.value() == 0) {
                return null; // Document not found
            }

            // Retrieve the document
            final Document doc = searcher.storedFields().document(topDocs.scoreDocs[0].doc);
            final Map<String, Object> result = new HashMap<>();

            // Add all stored fields
            addFieldIfPresent(doc, result, "file_path");
            addFieldIfPresent(doc, result, "file_name");
            addFieldIfPresent(doc, result, "file_extension");
            addFieldIfPresent(doc, result, "file_type");
            addFieldIfPresent(doc, result, "file_size");
            addFieldIfPresent(doc, result, "title");
            addFieldIfPresent(doc, result, "author");
            addFieldIfPresent(doc, result, "creator");
            addFieldIfPresent(doc, result, "subject");
            addFieldIfPresent(doc, result, "keywords");
            addFieldIfPresent(doc, result, "language");
            addFieldIfPresent(doc, result, "created_date");
            addFieldIfPresent(doc, result, "modified_date");
            addFieldIfPresent(doc, result, "indexed_date");
            addFieldIfPresent(doc, result, "content_hash");

            // Add content field with truncation to keep response size safe
            final String content = doc.get("content");
            if (content != null && !content.isEmpty()) {
                final int maxContentLength = 500_000; // 500KB limit
                if (content.length() > maxContentLength) {
                    result.put("content", content.substring(0, maxContentLength));
                    result.put("contentTruncated", true);
                    result.put("originalContentLength", content.length());
                } else {
                    result.put("content", content);
                    result.put("contentTruncated", false);
                }
            }

            return result;

        } finally {
            searcherManager.release(searcher);
        }
    }

    public String getIndexPath() {
        return indexPath;
    }

    /**
     * Check if a schema upgrade is required.
     * This flag is set during init() if the stored schema version differs from the current version.
     *
     * @return true if reindex is needed, false otherwise
     */
    public boolean isSchemaUpgradeRequired() {
        return schemaUpgradeRequired;
    }

    /**
     * Get the current schema version stored in the index commit metadata.
     *
     * @return schema version, or 0 if not found
     * @throws IOException if reading from index fails
     */
    public int getIndexSchemaVersion() throws IOException {
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            final DirectoryReader reader = (DirectoryReader) searcher.getIndexReader();
            final Map<String, String> userData = reader.getIndexCommit().getUserData();
            final String versionStr = userData.get("schema_version");
            return versionStr != null ? Integer.parseInt(versionStr) : 0;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Get the software version stored in the index commit metadata.
     *
     * @return software version, or empty string if not found
     * @throws IOException if reading from index fails
     */
    public String getIndexSoftwareVersion() throws IOException {
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            final DirectoryReader reader = (DirectoryReader) searcher.getIndexReader();
            final Map<String, String> userData = reader.getIndexCommit().getUserData();
            final String version = userData.get("software_version");
            return version != null ? version : "";
        } finally {
            searcherManager.release(searcher);
        }
    }

    // ==================== Reconciliation Support Methods ====================

    /**
     * Retrieve every indexed document's file path and its stored {@code modified_date}
     * (epoch millis).  Used by the reconciliation phase to build the "index snapshot"
     * that is diffed against the current filesystem state.
     *
     * @return map of file_path to modified_date; never null, may be empty
     * @throws IOException if the index cannot be read
     */
    public Map<String, Long> getAllIndexedDocuments() throws IOException {
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            // MatchAllDocsQuery returns every document in the index
            final TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), Integer.MAX_VALUE);
            final Map<String, Long> result = new HashMap<>(topDocs.scoreDocs.length * 2);

            for (final ScoreDoc scoreDoc : topDocs.scoreDocs) {
                final Document doc = searcher.storedFields().document(scoreDoc.doc);
                final String filePath = doc.get("file_path");
                if (filePath == null) {
                    continue;
                }

                // modified_date is stored as a long via StoredField
                final String modifiedDateStr = doc.get("modified_date");
                final long modifiedDate = modifiedDateStr != null ? Long.parseLong(modifiedDateStr) : 0L;
                result.put(filePath, modifiedDate);
            }

            logger.info("Retrieved {} indexed documents for reconciliation", result.size());
            return result;
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Efficiently delete a set of documents identified by their {@code file_path} values.
     * Builds a single {@link BooleanQuery} with one {@link TermQuery} per path so that
     * the IndexWriter can batch the deletions internally.
     *
     * @param filePaths the set of file paths to remove; must not be null
     * @throws IOException if the deletion or commit fails
     */
    public void bulkDeleteByFilePaths(final Set<String> filePaths) throws IOException {
        if (filePaths == null || filePaths.isEmpty()) {
            return;
        }

        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (final String filePath : filePaths) {
            builder.add(new TermQuery(new Term("file_path", filePath)), BooleanClause.Occur.SHOULD);
        }

        indexWriter.deleteDocuments(builder.build());
        indexWriter.commit();
        searcherManager.maybeRefresh();

        logger.info("Bulk-deleted {} documents from index", filePaths.size());
    }

    // ==================== Admin Operation Methods ====================

    /**
     * Check if the write.lock file exists.
     */
    public boolean isLockFilePresent() {
        final Path lockPath = Path.of(indexPath, WRITE_LOCK_FILE);
        return Files.exists(lockPath);
    }

    /**
     * Get the path to the lock file.
     */
    public Path getLockFilePath() {
        return Path.of(indexPath, WRITE_LOCK_FILE);
    }

    /**
     * Remove the write.lock file.
     * WARNING: Only use this if you are certain no other process is using the index.
     *
     * @return true if the lock file was deleted, false if it didn't exist
     * @throws IOException if deletion fails
     */
    public boolean removeLockFile() throws IOException {
        final Path lockPath = getLockFilePath();
        if (Files.exists(lockPath)) {
            Files.delete(lockPath);
            logger.warn("Removed lock file: {}", lockPath);
            return true;
        }
        return false;
    }

    /**
     * Get the current number of index segments.
     */
    public int getSegmentCount() throws IOException {
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().leaves().size();
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Get the current admin operation status.
     */
    public AdminOperationStatus getAdminStatus() {
        synchronized (adminStateLock) {
            if (adminState == AdminOperationState.IDLE) {
                return new AdminOperationStatus(adminState, null, null, null, null, lastOperationResult);
            }
            final long elapsed = System.currentTimeMillis() - operationStartTime;
            return new AdminOperationStatus(adminState, currentOperationId, operationProgressPercent,
                    operationProgressMessage, elapsed, lastOperationResult);
        }
    }

    /**
     * Check if an admin operation is currently running.
     */
    public boolean isAdminOperationRunning() {
        synchronized (adminStateLock) {
            return adminState == AdminOperationState.OPTIMIZING || adminState == AdminOperationState.PURGING;
        }
    }

    /**
     * Start an asynchronous index optimization (forceMerge).
     *
     * @param maxSegments the target number of segments (1 = maximum optimization)
     * @return the operation ID, or null if another operation is already running
     */
    public String startOptimization(final int maxSegments) {
        final String operationId;

        synchronized (adminStateLock) {
            if (adminState == AdminOperationState.OPTIMIZING || adminState == AdminOperationState.PURGING) {
                return null;
            }

            operationId = UUID.randomUUID().toString();
            this.currentOperationId = operationId;
            this.adminState = AdminOperationState.OPTIMIZING;
            this.operationProgressPercent = 0;
            this.operationProgressMessage = "Starting optimization...";
            this.operationStartTime = System.currentTimeMillis();
        }

        adminExecutor.submit(() -> {
            try {
                logger.info("Starting index optimization: operationId={}, maxSegments={}", operationId, maxSegments);

                synchronized (adminStateLock) {
                    operationProgressMessage = "Merging segments...";
                    operationProgressPercent = 10;
                }

                // forceMerge is a blocking operation that merges all segments
                indexWriter.forceMerge(maxSegments);

                synchronized (adminStateLock) {
                    operationProgressPercent = 80;
                    operationProgressMessage = "Committing changes...";
                }

                indexWriter.commit();

                synchronized (adminStateLock) {
                    operationProgressPercent = 90;
                    operationProgressMessage = "Refreshing searcher...";
                }

                searcherManager.maybeRefresh();

                synchronized (adminStateLock) {
                    operationProgressPercent = 100;
                    operationProgressMessage = "Optimization completed";
                    adminState = AdminOperationState.COMPLETED;
                    lastOperationResult = "Optimization completed successfully. Merged to " + maxSegments + " segment(s).";
                }

                logger.info("Index optimization completed: operationId={}", operationId);

            } catch (final Exception e) {
                logger.error("Index optimization failed: operationId={}", operationId, e);
                synchronized (adminStateLock) {
                    adminState = AdminOperationState.FAILED;
                    operationProgressMessage = "Optimization failed: " + e.getMessage();
                    lastOperationResult = "Optimization failed: " + e.getMessage();
                }
            } finally {
                // Reset to IDLE after a short delay so clients can see the final status
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                synchronized (adminStateLock) {
                    // Only reset if this is still our operation (prevents race with new operation)
                    if (operationId.equals(currentOperationId) &&
                            (adminState == AdminOperationState.COMPLETED || adminState == AdminOperationState.FAILED)) {
                        adminState = AdminOperationState.IDLE;
                    }
                }
            }
        });

        return operationId;
    }

    /**
     * Start an asynchronous index purge (delete all documents).
     *
     * @param fullPurge if true, also deletes index files and reinitializes
     * @return the result containing operation ID and document count
     */
    public PurgeResult startPurge(final boolean fullPurge) {
        final String operationId;
        final long documentCount;

        synchronized (adminStateLock) {
            if (adminState == AdminOperationState.OPTIMIZING || adminState == AdminOperationState.PURGING) {
                return null;
            }

            operationId = UUID.randomUUID().toString();
            this.currentOperationId = operationId;
            this.adminState = AdminOperationState.PURGING;
            this.operationProgressPercent = 0;
            this.operationProgressMessage = "Starting purge...";
            this.operationStartTime = System.currentTimeMillis();
        }

        // Get document count before purge (outside lock - getDocumentCount is safe)
        long docCount;
        try {
            docCount = getDocumentCount();
        } catch (final IOException e) {
            docCount = -1;
        }
        documentCount = docCount;

        adminExecutor.submit(() -> {
            try {
                logger.info("Starting index purge: operationId={}, fullPurge={}, documentsToDelete={}",
                        operationId, fullPurge, documentCount);

                synchronized (adminStateLock) {
                    operationProgressPercent = 10;
                    operationProgressMessage = "Deleting all documents...";
                }

                // Delete all documents
                indexWriter.deleteAll();

                synchronized (adminStateLock) {
                    operationProgressPercent = 40;
                    operationProgressMessage = "Committing deletion...";
                }

                indexWriter.commit();

                if (fullPurge) {
                    synchronized (adminStateLock) {
                        operationProgressPercent = 50;
                        operationProgressMessage = "Closing index for file deletion...";
                    }

                    // Close everything
                    searcherManager.close();
                    indexWriter.close();
                    directory.close();

                    synchronized (adminStateLock) {
                        operationProgressPercent = 60;
                        operationProgressMessage = "Deleting index files...";
                    }

                    // Delete all files in the index directory
                    final Path indexDir = Path.of(indexPath);
                    try (final var files = Files.list(indexDir)) {
                        files.forEach(file -> {
                            try {
                                Files.deleteIfExists(file);
                            } catch (final IOException e) {
                                logger.warn("Failed to delete index file: {}", file, e);
                            }
                        });
                    }

                    synchronized (adminStateLock) {
                        operationProgressPercent = 80;
                        operationProgressMessage = "Reinitializing index...";
                    }

                    // Reinitialize
                    reinitializeIndex();
                } else {
                    synchronized (adminStateLock) {
                        operationProgressPercent = 80;
                        operationProgressMessage = "Refreshing searcher...";
                    }

                    searcherManager.maybeRefresh();
                }

                synchronized (adminStateLock) {
                    operationProgressPercent = 100;
                    operationProgressMessage = "Purge completed";
                    adminState = AdminOperationState.COMPLETED;
                    lastOperationResult = "Purge completed. Deleted " + documentCount + " document(s)." +
                            (fullPurge ? " Index files deleted and reinitialized." : "");
                }

                logger.info("Index purge completed: operationId={}", operationId);

            } catch (final Exception e) {
                logger.error("Index purge failed: operationId={}", operationId, e);
                synchronized (adminStateLock) {
                    adminState = AdminOperationState.FAILED;
                    operationProgressMessage = "Purge failed: " + e.getMessage();
                    lastOperationResult = "Purge failed: " + e.getMessage();
                }
            } finally {
                // Reset to IDLE after a short delay so clients can see the final status
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                synchronized (adminStateLock) {
                    // Only reset if this is still our operation (prevents race with new operation)
                    if (operationId.equals(currentOperationId) &&
                            (adminState == AdminOperationState.COMPLETED || adminState == AdminOperationState.FAILED)) {
                        adminState = AdminOperationState.IDLE;
                    }
                }
            }
        });

        return new PurgeResult(operationId, documentCount);
    }

    /**
     * Reinitialize the index after a full purge.
     */
    private void reinitializeIndex() throws IOException {
        final Path path = Path.of(indexPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }

        directory = FSDirectory.open(path);
        final IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        indexWriter = new IndexWriter(directory, config);

        // Set commit user data with current schema version and software version
        indexWriter.setLiveCommitData(Map.of(
                "schema_version", String.valueOf(DocumentIndexer.SCHEMA_VERSION),
                "software_version", BuildInfo.getVersion()
        ).entrySet());

        indexWriter.commit();
        searcherManager = new SearcherManager(indexWriter, null);

        logger.info("Index reinitialized at: {}", path.toAbsolutePath());
    }

    /**
     * Result of a purge start operation.
     */
    public record PurgeResult(String operationId, long documentsDeleted) {
    }

    /**
     * Current admin operation status.
     */
    public record AdminOperationStatus(
            AdminOperationState state,
            String operationId,
            Integer progressPercent,
            String progressMessage,
            Long elapsedTimeMs,
            String lastOperationResult
    ) {
    }

    private void addFieldIfPresent(final Document doc, final Map<String, Object> map, final String fieldName) {
        final String value = doc.get(fieldName);
        if (value != null && !value.isEmpty()) {
            map.put(fieldName, value);
        }
    }

    /**
     * Produce a list of structured passages for a single document using Lucene's UnifiedHighlighter.
     *
     * <p>Uses {@link PassageAwareHighlighter} with {@link IndividualPassageFormatter} to
     * obtain each passage as a separate object with its own Lucene-computed BM25 score
     * and character offsets.  This avoids the bug where all passages were joined into a
     * single string by the default formatter, resulting in only one Passage DTO per document.</p>
     *
     * <p>The highlighter reads term vectors (positions + offsets) stored in the index so that
     * matched terms are located precisely without re-analysing the content.  This is
     * particularly important when ICUFoldingFilter is in the analyzer chain: the stored
     * offsets map directly to the original surface forms, ensuring {@code <em>} tags wrap
     * the correct character spans.</p>
     *
     * <p>Each passage is enriched with metadata: a normalised relevance score (derived from
     * Lucene's BM25 passage scoring), the set of query terms that matched within the
     * passage, the fraction of all query terms covered, and the position of the passage
     * in the original document (computed from the passage's start offset).</p>
     *
     * <p>If the content is null or empty an empty list is returned.  If highlighting
     * fails entirely a single fallback passage is synthesised from the beginning of the
     * content so the caller always receives at least one passage when content exists.</p>
     *
     * @param content              the stored content field value (may be null)
     * @param query                the user's search query (filter clauses excluded)
     * @param highlighter          the configured highlighter instance (shared across the result page)
     * @param maxPassages          maximum number of passages to return
     * @param maxPassageCharLength maximum character length per passage; longer passages are truncated
     *                             at a word boundary (preserving {@code <em>} tags) and "..." is appended
     * @param queryTerms           the set of query terms extracted from the query for coverage calculation
     * @param scoreDoc             the ScoreDoc for this document (doc ID used to read term vectors)
     * @return list of Passage records, each containing text, score, matchedTerms, termCoverage, position
     */
    private List<Passage> createPassages(final String content,
                                         final Query query,
                                         final PassageAwareHighlighter highlighter,
                                         final int maxPassages,
                                         final int maxPassageCharLength,
                                         final Set<String> queryTerms,
                                         final ScoreDoc scoreDoc) {
        // Null or empty content -- nothing to extract
        if (content == null || content.isEmpty()) {
            return List.of();
        }

        // Obtain individual highlighted passages via the lower-level highlightFieldsAsObjects API.
        // Each FormattedPassage carries its own Lucene BM25 score and character offsets.
        List<IndividualPassageFormatter.FormattedPassage> formattedPassages = null;
        try {
            formattedPassages = highlighter.highlightField("content", query, scoreDoc.doc, maxPassages);
            logger.debug("Highlighting query '{}' against doc {}, got {} passages",
                    query, scoreDoc.doc,
                    formattedPassages != null ? formattedPassages.size() : 0);
        } catch (final Exception e) {
            logger.warn("Highlighting failed for doc {}; synthesising fallback passage", scoreDoc.doc, e);
        }

        // Fallback: synthesise a single passage from the start of the content
        if (formattedPassages == null || formattedPassages.isEmpty()) {
            return List.of(createFallbackPassage(content));
        }

        // Find the maximum passage score for normalisation to [0, 1]
        float maxScore = 0f;
        for (final IndividualPassageFormatter.FormattedPassage fp : formattedPassages) {
            if (fp.score() > maxScore) {
                maxScore = fp.score();
            }
        }

        // Build Passage DTOs from individual formatted passages
        final List<Passage> passages = new ArrayList<>(formattedPassages.size());

        for (final IndividualPassageFormatter.FormattedPassage fp : formattedPassages) {
            // Clean passage text for display: collapse newlines and extra whitespace,
            // then truncate to the configured limit at a word boundary
            final String passageText = truncatePassage(cleanPassageText(fp.text()), maxPassageCharLength);
            if (passageText.isBlank()) {
                continue;
            }

            // --- score: normalise to 0-1 using the best passage's score as the maximum ---
            final double normalizedScore = maxScore > 0
                    ? Math.round((double) fp.score() / maxScore * 100.0) / 100.0
                    : 0.0;

            // --- matchedTerms: extract text between <em> tags, with fallback to query-term scanning ---
            final List<String> matchedTerms = extractMatchedTerms(passageText, queryTerms);

            // --- termCoverage: unique matched terms / total query terms ---
            final double termCoverage = calculateTermCoverage(matchedTerms, queryTerms);

            // --- position: derived directly from the passage's start offset ---
            // (content is guaranteed non-empty by the early return at the top of this method)
            final double position = Math.round((double) fp.startOffset() / content.length() * 100.0) / 100.0;

            passages.add(new Passage(passageText, normalizedScore, matchedTerms, termCoverage, position));
        }

        // If all passages were blank (unlikely but defensive), return a fallback
        if (passages.isEmpty()) {
            return List.of(createFallbackPassage(content));
        }

        return passages;
    }

    /**
     * Create a fallback passage from the beginning of the content when highlighting fails.
     */
    private static Passage createFallbackPassage(final String content) {
        final int fallbackLength = Math.min(300, content.length());
        final String fallbackText = cleanPassageText(
                content.substring(0, fallbackLength) +
                (content.length() > fallbackLength ? "..." : ""));
        return new Passage(fallbackText, 0.0, List.of(), 0.0, 0.0);
    }

    /**
     * Extract all terms wrapped in {@code <em>...</em>} tags from a highlighted passage.
     * Duplicates are removed (case-insensitive); order follows first appearance.
     *
     * <p>If no {@code <em>} tags are found but {@code queryTerms} is non-empty the method
     * falls back to scanning the passage for any query term that appears verbatim
     * (after NFKC + lowercase normalisation).  This handles the case where the
     * highlighter returns a relevant passage without wrapping the matched tokens.</p>
     *
     * @param highlightedText the passage text (may contain {@code <em>} markup)
     * @param queryTerms      the original query terms; used only for the fallback scan
     * @return list of matched terms in first-appearance order
     */
    private static List<String> extractMatchedTerms(final String highlightedText, final Set<String> queryTerms) {
        final List<String> terms = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        // First try to extract from <em> tags
        int searchFrom = 0;
        while (true) {
            final int emOpen = highlightedText.indexOf("<em>", searchFrom);
            if (emOpen < 0) {
                break;
            }
            final int emClose = highlightedText.indexOf("</em>", emOpen + 4);
            if (emClose < 0) {
                break;
            }
            final String term = highlightedText.substring(emOpen + 4, emClose);
            if (!term.isEmpty() && seen.add(term.toLowerCase())) {
                terms.add(term);
            }
            searchFrom = emClose + 5;
        }

        // Fallback: if no <em> tags found, check which query terms appear in the passage
        if (terms.isEmpty() && queryTerms != null && !queryTerms.isEmpty()) {
            final String normalizedPassage = normalizeForComparison(highlightedText);
            for (final String qt : queryTerms) {
                final String qtNorm = normalizeForComparison(qt);
                if (qtNorm.length() >= 2 && normalizedPassage.contains(qtNorm)) {
                    if (seen.add(qtNorm)) {
                        terms.add(qt); // Add original form
                    }
                }
            }
        }

        return terms;
    }

    /**
     * Calculate the term coverage: fraction of query terms that appear among the matched terms.
     *
     * @param matchedTerms terms found in this passage (from {@code <em>} tags or fallback scan)
     * @param queryTerms   the full set of query terms
     * @return coverage fraction in [0.0, 1.0], rounded to 2 decimal places
     */
    private static double calculateTermCoverage(final List<String> matchedTerms, final Set<String> queryTerms) {
        if (queryTerms.isEmpty()) {
            return matchedTerms.isEmpty() ? 0.0 : 1.0;
        }
        final Set<String> matchedNormalized = new HashSet<>();
        for (final String t : matchedTerms) {
            matchedNormalized.add(normalizeForComparison(t));
        }
        long coveredCount = 0;
        for (final String qt : queryTerms) {
            if (matchedNormalized.contains(normalizeForComparison(qt))) {
                coveredCount++;
            }
        }
        return Math.round((double) coveredCount / queryTerms.size() * 100.0) / 100.0;
    }

    /**
     * Clean a passage text for display: replace newlines with spaces, collapse
     * consecutive spaces into one, and trim leading/trailing whitespace.
     * Returns an empty string for null input.
     */
    private static String cleanPassageText(final String passageText) {
        if (passageText == null) {
            return "";
        }
        // Replace newlines with space, collapse multiple spaces, trim
        return passageText.replace('\n', ' ')
                          .replaceAll(" +", " ")
                          .trim();
    }

    /**
     * Truncate a passage to the given maximum character length, centred around the
     * highlighted terms.
     *
     * <p>If the text is already within the limit it is returned unchanged.
     * When truncation is needed, the method locates the span of {@code <em>} tags
     * and creates a window of {@code maxLength} characters centred on that span,
     * distributing available context evenly before and after the highlights.
     * Both ends are trimmed to word boundaries and "..." is prepended/appended
     * as needed.</p>
     *
     * <p>If no {@code <em>} tags are present (fallback passages), truncation
     * falls back to trimming from the end.</p>
     *
     * @param text      the cleaned passage text (may contain {@code <em>} markup)
     * @param maxLength the maximum character length; values &le; 0 disable truncation
     * @return the (possibly truncated) text
     */
    static String truncatePassage(final String text, final int maxLength) {
        if (maxLength <= 0 || text == null || text.length() <= maxLength) {
            return text;
        }

        // Locate the span of highlighted terms
        final int firstEmStart = text.indexOf("<em>");
        final int lastEmEndTag = text.lastIndexOf("</em>");
        final int lastEmEnd = lastEmEndTag >= 0 ? lastEmEndTag + 5 : -1; // past "</em>"

        // No highlights — simple tail truncation
        if (firstEmStart < 0) {
            return truncateFromEnd(text, maxLength);
        }

        final int highlightSpan = lastEmEnd - firstEmStart;

        // If the highlight span alone exceeds the limit, show from first highlight
        if (highlightSpan >= maxLength) {
            final boolean trimmedStart = firstEmStart > 0;
            final String window = text.substring(firstEmStart, Math.min(text.length(), firstEmStart + maxLength));
            return (trimmedStart ? "..." : "") + truncateFromEnd(window, maxLength);
        }

        // Distribute remaining budget evenly before and after the highlight span
        final int availableContext = maxLength - highlightSpan;
        int contextBefore = availableContext / 2;
        int contextAfter = availableContext - contextBefore;

        // Compute raw window boundaries
        int windowStart = firstEmStart - contextBefore;
        int windowEnd = lastEmEnd + contextAfter;

        // Clamp and redistribute if one side hits the text boundary
        if (windowStart < 0) {
            // Extra budget from the start side goes to the end side
            windowEnd = Math.min(text.length(), windowEnd + (-windowStart));
            windowStart = 0;
        }
        if (windowEnd > text.length()) {
            // Extra budget from the end side goes to the start side
            windowStart = Math.max(0, windowStart - (windowEnd - text.length()));
            windowEnd = text.length();
        }

        // Adjust to word boundaries (find nearest space)
        if (windowStart > 0) {
            final int space = text.indexOf(' ', windowStart);
            if (space >= 0 && space < firstEmStart) {
                windowStart = space + 1;
            }
        }
        if (windowEnd < text.length()) {
            final int space = text.lastIndexOf(' ', windowEnd);
            if (space > lastEmEnd) {
                windowEnd = space;
            }
        }

        // Build the result with ellipsis indicators
        final StringBuilder sb = new StringBuilder();
        if (windowStart > 0) {
            sb.append("...");
        }
        sb.append(text, windowStart, windowEnd);
        if (windowEnd < text.length()) {
            // Strip trailing space before appending ellipsis
            while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ' ') {
                sb.setLength(sb.length() - 1);
            }
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * Simple tail truncation: cut at a word boundary near {@code maxLength} and append "...".
     */
    private static String truncateFromEnd(final String text, final int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int cutPoint = maxLength;
        while (cutPoint > 0 && text.charAt(cutPoint) != ' ') {
            cutPoint--;
        }
        if (cutPoint == 0) {
            cutPoint = maxLength;
        }
        return text.substring(0, cutPoint).stripTrailing() + "...";
    }

    /**
     * Normalize a term for comparison: NFKC normalization + lowercase.
     * This approximates what ICUFoldingFilter does during indexing, allowing
     * matched-term detection to succeed even when the highlighter returns the
     * original (un-folded) surface form from the stored content.
     */
    private static String normalizeForComparison(final String term) {
        if (term == null || term.isEmpty()) {
            return "";
        }
        return java.text.Normalizer.normalize(term, java.text.Normalizer.Form.NFKC).toLowerCase();
    }

    /**
     * Extract the set of leaf query terms from a Lucene {@link Query} tree.
     * Walks {@link BooleanQuery} clauses recursively and pulls text from
     * {@link TermQuery} nodes.  For other query types (wildcards, phrases, etc.)
     * falls back to parsing {@link Query#toString()}.
     */
    private static Set<String> extractQueryTerms(final Query query) {
        final Set<String> terms = new HashSet<>();
        collectTerms(query, terms);
        if (terms.isEmpty()) {
            // Last-resort fallback: tokenise the string representation
            parseTermsFromString(query.toString(), terms);
        }
        return terms;
    }

    private static void collectTerms(final Query query, final Set<String> terms) {
        if (query instanceof TermQuery tq) {
            terms.add(tq.getTerm().text());
        } else if (query instanceof BooleanQuery bq) {
            // Lucene 10: getClauses() requires an Occur parameter.
            // Iterate over all Occur values to collect every sub-query.
            for (final BooleanClause.Occur occur : BooleanClause.Occur.values()) {
                for (final Query sub : bq.getClauses(occur)) {
                    collectTerms(sub, terms);
                }
            }
        } else {
            // For WildcardQuery, PrefixQuery, PhraseQuery, etc. -- extract from toString()
            parseTermsFromString(query.toString(), terms);
        }
    }

    /**
     * Parse terms out of a Query.toString() representation.
     * Strips field prefixes (e.g. "content:"), parentheses, boolean operators,
     * and wildcard characters to isolate the actual search tokens.
     */
    private static void parseTermsFromString(final String queryStr, final Set<String> terms) {
        if (queryStr == null || queryStr.isBlank()) {
            return;
        }
        // Split on whitespace and common delimiters
        final String[] tokens = queryStr.split("[\\s()\"]+");
        for (final String token : tokens) {
            String cleaned = token.trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            // Skip boolean operators
            if ("AND".equals(cleaned) || "OR".equals(cleaned) || "NOT".equals(cleaned) ||
                    "TO".equals(cleaned) || "+".equals(cleaned) || "-".equals(cleaned)) {
                continue;
            }
            // Strip field prefix (e.g. "content:" or "title:")
            final int colonIdx = cleaned.indexOf(':');
            if (colonIdx > 0 && colonIdx < cleaned.length() - 1) {
                cleaned = cleaned.substring(colonIdx + 1);
            }
            // Strip wildcard and range characters
            cleaned = cleaned.replace("*", "").replace("?", "")
                    .replace("[", "").replace("]", "")
                    .replace("{", "").replace("}", "");
            if (!cleaned.isEmpty()) {
                terms.add(cleaned);
            }
        }
    }

    /**
     * Rewrite leading wildcard queries to use the {@code content_reversed} field
     * for efficient execution.
     *
     * <p>Rewriting rules (only for queries on the {@code content} field):</p>
     * <ul>
     *   <li>{@code *vertrag} &rarr; {@code WildcardQuery("content_reversed", "gartrev*")}</li>
     *   <li>{@code *vertrag*} &rarr; {@code BooleanQuery(OR): content:*vertrag* OR content_reversed:gartrev*}</li>
     *   <li>{@code vertrag*} &rarr; no change (trailing wildcard is already efficient)</li>
     *   <li>{@code BooleanQuery} &rarr; recurse into sub-queries</li>
     *   <li>Everything else &rarr; no change</li>
     * </ul>
     */
    static Query rewriteLeadingWildcards(final Query query) {
        if (query instanceof WildcardQuery wq) {
            final String field = wq.getTerm().field();
            final String text = wq.getTerm().text();

            // Only rewrite queries on the "content" field
            if (!"content".equals(field)) {
                return query;
            }

            final boolean startsWithWildcard = text.startsWith("*") || text.startsWith("?");
            final boolean endsWithWildcard = text.endsWith("*") || text.endsWith("?");

            if (!startsWithWildcard) {
                // Trailing wildcard only (e.g. vertrag*) -- already efficient
                return query;
            }

            // Strip the leading wildcard character
            final String core = text.substring(1);

            if (endsWithWildcard) {
                // Infix wildcard: *vertrag*
                // Strip trailing wildcard to get the core word for reversal
                final String coreWithoutTrailing = core.substring(0, core.length() - 1);
                final String reversed = new StringBuilder(coreWithoutTrailing).reverse().toString();

                // OR: original query on content OR reversed trailing wildcard on content_reversed
                final BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(wq, BooleanClause.Occur.SHOULD);
                builder.add(new WildcardQuery(new Term("content_reversed", reversed + "*")),
                        BooleanClause.Occur.SHOULD);
                return builder.build();
            } else {
                // Pure leading wildcard: *vertrag
                // Reverse the core and make it a trailing wildcard on the reversed field
                final String reversed = new StringBuilder(core).reverse().toString();
                return new WildcardQuery(new Term("content_reversed", reversed + "*"));
            }

        } else if (query instanceof BooleanQuery bq) {
            // Recurse into sub-queries
            final BooleanQuery.Builder builder = new BooleanQuery.Builder();
            boolean changed = false;
            for (final BooleanClause.Occur occur : BooleanClause.Occur.values()) {
                for (final Query sub : bq.getClauses(occur)) {
                    final Query rewritten = rewriteLeadingWildcards(sub);
                    builder.add(rewritten, occur);
                    if (rewritten != sub) {
                        changed = true;
                    }
                }
            }
            return changed ? builder.build() : query;
        }

        // All other query types: no change
        return query;
    }

    private Map<String, List<FacetValue>> buildFacets(final IndexSearcher searcher, final FacetsCollector facetsCollector) {
        final Map<String, List<FacetValue>> facets = new HashMap<>();

        try {
            // Create facets state from the index using the same FacetsConfig as indexing
            final SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(
                    searcher.getIndexReader(),
                    documentIndexer.getFacetsConfig()
            );

            // Create facet counts
            final Facets facetsResult = new SortedSetDocValuesFacetCounts(state, facetsCollector);

            // Define facet dimensions to retrieve
            final String[] facetDimensions = {
                    "language",
                    "file_extension",
                    "file_type",
                    "author",
                    "creator",
                    "subject"
            };

            // Retrieve top facets for each dimension
            for (final String dimension : facetDimensions) {
                try {
                    final FacetResult facetResult = facetsResult.getTopChildren(100, dimension);
                    if (facetResult != null && facetResult.labelValues.length > 0) {
                        final List<FacetValue> values = new ArrayList<>();
                        for (final LabelAndValue lv : facetResult.labelValues) {
                            values.add(new FacetValue(lv.label, lv.value.intValue()));
                        }
                        facets.put(dimension, values);
                    }
                } catch (final IllegalArgumentException e) {
                    // Dimension doesn't exist in index - skip it
                    logger.debug("Facet dimension {} not found in index", dimension);
                }
            }
        } catch (final Exception e) {
            logger.warn("Error building facets", e);
        }

        return facets;
    }

    public record FacetValue(String value, int count) {
    }

    public record SearchResult(
            List<SearchDocument> documents,
            long totalHits,
            int page,
            int pageSize,
            Map<String, List<FacetValue>> facets
    ) {
        public int totalPages() {
            return (int) Math.ceil((double) totalHits / pageSize);
        }

        public boolean hasNextPage() {
            return page < totalPages() - 1;
        }

        public boolean hasPreviousPage() {
            return page > 0;
        }
    }

    /**
     * Thin subclass of {@link UnifiedHighlighter} that exposes the {@code protected}
     * {@link #highlightFieldsAsObjects} method.  This allows callers to receive the
     * raw {@link Object} returned by the {@link IndividualPassageFormatter} (a
     * {@code List<FormattedPassage>}) instead of having it cast to {@link String}.
     */
    static class PassageAwareHighlighter extends UnifiedHighlighter {

        PassageAwareHighlighter(final Builder builder) {
            super(builder);
        }

        /**
         * Highlight a single field for a single document and return the individual
         * passages produced by {@link IndividualPassageFormatter}.
         *
         * @param field       the field to highlight (must have stored term vectors)
         * @param query       the query whose terms should be highlighted
         * @param docId       the Lucene internal document ID
         * @param maxPassages maximum number of passages to extract
         * @return list of formatted passages, or empty list if nothing matched
         * @throws IOException if reading from the index fails
         */
        @SuppressWarnings("unchecked")
        List<IndividualPassageFormatter.FormattedPassage> highlightField(
                final String field, final Query query, final int docId, final int maxPassages) throws IOException {
            final Map<String, Object[]> result = highlightFieldsAsObjects(
                    new String[]{field}, query, new int[]{docId}, new int[]{maxPassages});
            final Object[] fieldResults = result.get(field);
            if (fieldResults == null || fieldResults.length == 0 || fieldResults[0] == null) {
                return List.of();
            }
            return (List<IndividualPassageFormatter.FormattedPassage>) fieldResults[0];
        }
    }
}
