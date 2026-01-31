package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
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
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private final String indexPath;
    private volatile long nrtRefreshIntervalMs;

    private Directory directory;
    private IndexWriter indexWriter;
    private SearcherManager searcherManager;
    private ScheduledExecutorService refreshScheduler;
    private final StandardAnalyzer analyzer;
    private final DocumentIndexer documentIndexer;

    // Admin operation state (volatile for thread-safe reads)
    private volatile AdminOperationState adminState = AdminOperationState.IDLE;
    private volatile String currentOperationId;
    private volatile int operationProgressPercent;
    private volatile String operationProgressMessage;
    private volatile long operationStartTime;
    private volatile String lastOperationResult;

    // Single-threaded executor for admin operations (ensures only one runs at a time)
    private final ExecutorService adminExecutor = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "lucene-admin-ops");
        t.setDaemon(true);
        return t;
    });

    public LuceneIndexService(final String indexPath, final long nrtRefreshIntervalMs,
                              final DocumentIndexer documentIndexer) {
        this.analyzer = new StandardAnalyzer();
        this.indexPath = indexPath;
        this.nrtRefreshIntervalMs = nrtRefreshIntervalMs;
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
            // Build the main query
            final QueryParser parser = new QueryParser("content", analyzer);
            final Query mainQuery = parser.parse(queryString);

            // Apply filter if provided
            final Query finalQuery;
            if (filterField != null && !filterField.isBlank() &&
                filterValue != null && !filterValue.isBlank()) {
                final BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(mainQuery, BooleanClause.Occur.MUST);

                final QueryParser filterParser = new QueryParser(filterField, analyzer);
                final Query filterQuery = filterParser.parse(filterValue);
                builder.add(filterQuery, BooleanClause.Occur.FILTER);

                finalQuery = builder.build();
            } else {
                finalQuery = mainQuery;
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

            // Collect results for the requested page
            final List<Map<String, Object>> results = new ArrayList<>();
            final ScoreDoc[] scoreDocs = topDocs.scoreDocs;

            for (int i = startIndex; i < scoreDocs.length && i < maxResults; i++) {
                final Document doc = searcher.storedFields().document(scoreDocs[i].doc);
                final Map<String, Object> docMap = new HashMap<>();
                docMap.put("score", scoreDocs[i].score);

                // Add only essential fields (NOT the full content to keep response size under 1MB)
                addFieldIfPresent(doc, docMap, "file_path");
                addFieldIfPresent(doc, docMap, "file_name");
                addFieldIfPresent(doc, docMap, "title");
                addFieldIfPresent(doc, docMap, "author");
                addFieldIfPresent(doc, docMap, "creator");
                addFieldIfPresent(doc, docMap, "subject");
                addFieldIfPresent(doc, docMap, "language");
                addFieldIfPresent(doc, docMap, "file_extension");
                addFieldIfPresent(doc, docMap, "file_type");
                addFieldIfPresent(doc, docMap, "file_size");
                addFieldIfPresent(doc, docMap, "created_date");
                addFieldIfPresent(doc, docMap, "modified_date");
                addFieldIfPresent(doc, docMap, "indexed_date");

                // Create snippet from content (max 300 chars)
                final String content = doc.get("content");
                if (content != null) {
                    final String snippet = createSnippet(content, queryString, 300);
                    docMap.put("snippet", snippet);
                }

                results.add(docMap);
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
        final AdminOperationState state = this.adminState;
        if (state == AdminOperationState.IDLE) {
            return new AdminOperationStatus(state, null, null, null, null, lastOperationResult);
        }
        final long elapsed = System.currentTimeMillis() - operationStartTime;
        return new AdminOperationStatus(state, currentOperationId, operationProgressPercent,
                operationProgressMessage, elapsed, lastOperationResult);
    }

    /**
     * Check if an admin operation is currently running.
     */
    public boolean isAdminOperationRunning() {
        final AdminOperationState state = this.adminState;
        return state == AdminOperationState.OPTIMIZING || state == AdminOperationState.PURGING;
    }

    /**
     * Start an asynchronous index optimization (forceMerge).
     *
     * @param maxSegments the target number of segments (1 = maximum optimization)
     * @return the operation ID, or null if another operation is already running
     */
    public synchronized String startOptimization(final int maxSegments) {
        if (isAdminOperationRunning()) {
            return null;
        }

        final String operationId = UUID.randomUUID().toString();
        this.currentOperationId = operationId;
        this.adminState = AdminOperationState.OPTIMIZING;
        this.operationProgressPercent = 0;
        this.operationProgressMessage = "Starting optimization...";
        this.operationStartTime = System.currentTimeMillis();

        adminExecutor.submit(() -> {
            try {
                logger.info("Starting index optimization: operationId={}, maxSegments={}", operationId, maxSegments);
                operationProgressMessage = "Merging segments...";
                operationProgressPercent = 10;

                // forceMerge is a blocking operation that merges all segments
                indexWriter.forceMerge(maxSegments);

                operationProgressPercent = 80;
                operationProgressMessage = "Committing changes...";

                indexWriter.commit();

                operationProgressPercent = 90;
                operationProgressMessage = "Refreshing searcher...";

                searcherManager.maybeRefresh();

                operationProgressPercent = 100;
                operationProgressMessage = "Optimization completed";
                adminState = AdminOperationState.COMPLETED;
                lastOperationResult = "Optimization completed successfully. Merged to " + maxSegments + " segment(s).";

                logger.info("Index optimization completed: operationId={}", operationId);

            } catch (final Exception e) {
                logger.error("Index optimization failed: operationId={}", operationId, e);
                adminState = AdminOperationState.FAILED;
                operationProgressMessage = "Optimization failed: " + e.getMessage();
                lastOperationResult = "Optimization failed: " + e.getMessage();
            } finally {
                // Reset to IDLE after a short delay so clients can see the final status
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (adminState == AdminOperationState.COMPLETED || adminState == AdminOperationState.FAILED) {
                    adminState = AdminOperationState.IDLE;
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
    public synchronized PurgeResult startPurge(final boolean fullPurge) {
        if (isAdminOperationRunning()) {
            return null;
        }

        final String operationId = UUID.randomUUID().toString();
        this.currentOperationId = operationId;
        this.adminState = AdminOperationState.PURGING;
        this.operationProgressPercent = 0;
        this.operationProgressMessage = "Starting purge...";
        this.operationStartTime = System.currentTimeMillis();

        // Get document count before purge
        long documentCount;
        try {
            documentCount = getDocumentCount();
        } catch (final IOException e) {
            documentCount = -1;
        }
        final long finalDocumentCount = documentCount;

        adminExecutor.submit(() -> {
            try {
                logger.info("Starting index purge: operationId={}, fullPurge={}, documentsToDelete={}",
                        operationId, fullPurge, finalDocumentCount);

                operationProgressPercent = 10;
                operationProgressMessage = "Deleting all documents...";

                // Delete all documents
                indexWriter.deleteAll();

                operationProgressPercent = 40;
                operationProgressMessage = "Committing deletion...";

                indexWriter.commit();

                if (fullPurge) {
                    operationProgressPercent = 50;
                    operationProgressMessage = "Closing index for file deletion...";

                    // Close everything
                    searcherManager.close();
                    indexWriter.close();
                    directory.close();

                    operationProgressPercent = 60;
                    operationProgressMessage = "Deleting index files...";

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

                    operationProgressPercent = 80;
                    operationProgressMessage = "Reinitializing index...";

                    // Reinitialize
                    reinitializeIndex();
                } else {
                    operationProgressPercent = 80;
                    operationProgressMessage = "Refreshing searcher...";

                    searcherManager.maybeRefresh();
                }

                operationProgressPercent = 100;
                operationProgressMessage = "Purge completed";
                adminState = AdminOperationState.COMPLETED;
                lastOperationResult = "Purge completed. Deleted " + finalDocumentCount + " document(s)." +
                        (fullPurge ? " Index files deleted and reinitialized." : "");

                logger.info("Index purge completed: operationId={}", operationId);

            } catch (final Exception e) {
                logger.error("Index purge failed: operationId={}", operationId, e);
                adminState = AdminOperationState.FAILED;
                operationProgressMessage = "Purge failed: " + e.getMessage();
                lastOperationResult = "Purge failed: " + e.getMessage();
            } finally {
                // Reset to IDLE after a short delay so clients can see the final status
                try {
                    Thread.sleep(1000);
                } catch (final InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (adminState == AdminOperationState.COMPLETED || adminState == AdminOperationState.FAILED) {
                    adminState = AdminOperationState.IDLE;
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

    private String createSnippet(final String content, final String queryString, final int maxLength) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        // Extract search terms from query (simple approach - just split on whitespace and remove operators)
        final String[] terms = queryString.toLowerCase()
                .replaceAll("[(){}\\[\\]\":]", " ")
                .replaceAll("\\b(AND|OR|NOT)\\b", " ")
                .split("\\s+");

        // Find first occurrence of any search term
        int bestPos = -1;
        String bestTerm = null;
        for (final String term : terms) {
            if (term.length() > 2) { // Skip very short terms
                final int pos = content.toLowerCase().indexOf(term);
                if (pos >= 0 && (bestPos < 0 || pos < bestPos)) {
                    bestPos = pos;
                    bestTerm = term;
                }
            }
        }

        // If no term found, just take beginning
        if (bestPos < 0) {
            return content.substring(0, Math.min(maxLength, content.length())) + "...";
        }

        // Create snippet around the found term
        final int snippetStart = Math.max(0, bestPos - 100);
        final int snippetEnd = Math.min(content.length(), bestPos + 200);

        String snippet = content.substring(snippetStart, snippetEnd);

        // Add ellipsis
        if (snippetStart > 0) {
            snippet = "..." + snippet;
        }
        if (snippetEnd < content.length()) {
            snippet = snippet + "...";
        }

        // Highlight the search term (simple approach - wrap in <em> tags)
        if (bestTerm != null) {
            snippet = snippet.replaceAll("(?i)(" + java.util.regex.Pattern.quote(bestTerm) + ")",
                    "<em>$1</em>");
        }

        return snippet;
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
            List<Map<String, Object>> documents,
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
