package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
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
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
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
     */
    public void init() throws IOException {
        final Path path = Path.of(indexPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
            logger.info("Created index directory: {}", path.toAbsolutePath());
        }

        directory = FSDirectory.open(path);
        final IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        indexWriter = new IndexWriter(directory, config);

        // Commit to ensure index files are created
        indexWriter.commit();

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

            // Build a UnifiedHighlighter for passage generation.
            // highlight() is called with a single-doc TopDocs per result so that the
            // highlighter reads term vectors (positions + offsets) stored in the index
            // rather than re-analysing the content text.  This is critical for correct
            // <em> tagging when ICUFoldingFilter is in play: the stored offsets map
            // directly to the original surface forms in the stored value.
            // The formatter wraps matched terms in <em>...</em> tags.
            // We use mainQuery (before the filter clause) so that only the user's
            // actual search terms are highlighted, not filter values.
            // maxNoHighlightPassages=1 ensures a fallback passage is returned even if
            // no terms match within the highlighter's view of the document.
            final int maxPassages = config.getMaxPassages();
            final UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                    .withMaxLength(10_000) // read up to 10 000 chars of stored value to find more passages
                    .withFormatter(new DefaultPassageFormatter("<em>", "</em>", "...", false))  // false = no HTML escaping (LLM output, not browser)
                    .withHandleMultiTermQuery(true)
                    .withBreakIterator(BreakIterator::getSentenceInstance)
                    .withMaxNoHighlightPassages(1)
                    .build();

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
                        content, mainQuery, highlighter, maxPassages, queryTerms, scoreDocs[i]);

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
     * <p>The highlighter reads term vectors (positions + offsets) stored in the index so that
     * matched terms are located precisely without re-analysing the content.  This is
     * particularly important when ICUFoldingFilter is in the analyzer chain: the stored
     * offsets map directly to the original surface forms, ensuring {@code <em>} tags wrap
     * the correct character spans.</p>
     *
     * <p>The highlighter returns passages in relevance order (best first). Each passage is
     * enriched with metadata: a normalised relevance score, the set of query terms that
     * matched within the passage, the fraction of all query terms covered, and the
     * approximate position of the passage in the original document.</p>
     *
     * <p>If the content is null or empty an empty list is returned.  If highlighting
     * fails entirely a single fallback passage is synthesised from the beginning of the
     * content so the caller always receives at least one passage when content exists.</p>
     *
     * @param content     the stored content field value (may be null)
     * @param query       the user's search query (filter clauses excluded)
     * @param highlighter the configured highlighter instance (shared across the result page)
     * @param maxPassages maximum number of passages to return
     * @param queryTerms  the set of query terms extracted from the query for coverage calculation
     * @param scoreDoc    the ScoreDoc for this document, used to build the single-doc TopDocs
     *                    that highlight() requires to read term vectors from the index
     * @return list of Passage records, each containing text, score, matchedTerms, termCoverage, position
     */
    private List<Passage> createPassages(final String content,
                                         final Query query,
                                         final UnifiedHighlighter highlighter,
                                         final int maxPassages,
                                         final Set<String> queryTerms,
                                         final ScoreDoc scoreDoc) {
        // Null or empty content -- nothing to extract
        if (content == null || content.isEmpty()) {
            return List.of();
        }

        // Attempt to obtain highlighted passages from the UnifiedHighlighter.
        // A single-doc TopDocs is constructed so that highlight() can read the term
        // vectors (positions + offsets) that were stored at index time.  This avoids
        // the re-analysis path of highlightWithoutSearcher() which does not use term
        // vectors and therefore cannot produce correct <em> tags when the analyzer
        // applies folding or normalization filters.
        String[] highlightedPassages = null;
        try {
            final TopDocs singleDocTopDocs = new TopDocs(
                    new TotalHits(1, TotalHits.Relation.EQUAL_TO),
                    new ScoreDoc[]{scoreDoc}
            );
            final String[] highlightResults = highlighter.highlight("content", query, singleDocTopDocs, maxPassages);
            logger.debug("Highlighting query '{}' against doc {}, result: {}",
                    query, scoreDoc.doc,
                    highlightResults == null ? "null" : "String[" + highlightResults.length + "]");
            if (highlightResults != null && highlightResults.length > 0 && highlightResults[0] != null && !highlightResults[0].isBlank()) {
                // highlight() returns one joined string per document (passages separated by
                // the formatter's separator "...").  Wrap in array for uniform handling below.
                highlightedPassages = new String[]{highlightResults[0]};
            }
        } catch (final Exception e) {
            logger.warn("UnifiedHighlighter.highlight() failed for doc {}; synthesising fallback passage", scoreDoc.doc, e);
        }

        // Fallback: synthesise a single passage from the start of the content
        if (highlightedPassages == null || highlightedPassages.length == 0) {
            final int fallbackLength = Math.min(300, content.length());
            final String fallbackText = cleanPassageText(
                    content.substring(0, fallbackLength) +
                    (content.length() > fallbackLength ? "..." : ""));
            return List.of(new Passage(fallbackText, 0.0, List.of(), 0.0, 0.0));
        }

        // Build Passage records from the highlighted strings
        final List<Passage> passages = new ArrayList<>(highlightedPassages.length);
        final int totalPassages = highlightedPassages.length;

        for (int idx = 0; idx < totalPassages; idx++) {
            final String rawPassageText = highlightedPassages[idx];
            if (rawPassageText == null || rawPassageText.isBlank()) {
                continue;
            }

            // Clean passage text for display: collapse newlines and extra whitespace
            final String passageText = cleanPassageText(rawPassageText);

            // --- score: normalised 0-1 using linear decay from position in the array ---
            // The highlighter returns passages in relevance order; first is best.
            final double score = totalPassages == 1 ? 1.0 :
                    1.0 - ((double) idx / (totalPassages - 1)) * 0.5;
            // Clamp to [0.0, 1.0] and round to 2 decimal places for clean output
            final double roundedScore = Math.round(score * 100.0) / 100.0;

            // --- matchedTerms: extract text between <em> tags, with fallback to direct query-term matching ---
            final List<String> matchedTerms = extractMatchedTerms(passageText, queryTerms);

            // --- termCoverage: unique matched terms / total query terms ---
            final double termCoverage;
            if (queryTerms.isEmpty()) {
                termCoverage = matchedTerms.isEmpty() ? 0.0 : 1.0;
            } else {
                // Normalize both sides with NFKC + lowercase for robust comparison
                final Set<String> matchedNormalized = new HashSet<>();
                for (final String t : matchedTerms) {
                    matchedNormalized.add(normalizeForComparison(t));
                }

                long coveredCount = 0;
                for (final String qt : queryTerms) {
                    final String qtNormalized = normalizeForComparison(qt);
                    if (matchedNormalized.contains(qtNormalized)) {
                        coveredCount++;
                    }
                }
                termCoverage = Math.round((double) coveredCount / queryTerms.size() * 100.0) / 100.0;
            }

            // --- position: find where the plain (un-tagged) passage text first appears in content ---
            final double position = calculatePosition(passageText, content);

            passages.add(new Passage(passageText, roundedScore, matchedTerms, termCoverage, position));
        }

        // If all passages were blank (unlikely but defensive), return a fallback
        if (passages.isEmpty()) {
            final int fallbackLength = Math.min(300, content.length());
            final String fallbackText = cleanPassageText(
                    content.substring(0, fallbackLength) +
                    (content.length() > fallbackLength ? "..." : ""));
            return List.of(new Passage(fallbackText, 0.0, List.of(), 0.0, 0.0));
        }

        return passages;
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
     * Calculate the approximate position (0.0-1.0) of a highlighted passage within
     * the original content.  The method strips {@code <em>} tags and the leading
     * ellipsis from the passage, then searches for the first substantial fragment
     * in the original text.
     *
     * @return position fraction, rounded to 2 decimal places; 0.0 if not found
     */
    private static double calculatePosition(final String highlightedPassage, final String content) {
        // Strip all <em> and </em> tags to get plain text
        String plain = highlightedPassage.replace("<em>", "").replace("</em>", "");

        // The DefaultPassageFormatter prepends "..." to passages that are not at
        // the very start of the text.  Strip leading/trailing ellipsis and whitespace.
        plain = plain.strip();
        if (plain.startsWith("...")) {
            plain = plain.substring(3).strip();
        }
        if (plain.endsWith("...")) {
            plain = plain.substring(0, plain.length() - 3).strip();
        }

        if (plain.isEmpty() || content.isEmpty()) {
            return 0.0;
        }

        // Use the first 80 characters of the cleaned passage as the search needle;
        // this avoids expensive searches on very long strings while being long enough
        // to locate uniquely in almost all documents.
        final int needleLen = Math.min(80, plain.length());
        final String needle = plain.substring(0, needleLen);

        final int idx = content.indexOf(needle);
        if (idx < 0) {
            // Needle not found (possible if content was normalised differently).
            // Return 0.0 rather than crashing.
            return 0.0;
        }

        return Math.round((double) idx / content.length() * 100.0) / 100.0;
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
}
