package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class LuceneIndexService {

    private static final Logger logger = LoggerFactory.getLogger(LuceneIndexService.class);

    private final String indexPath;
    private volatile long nrtRefreshIntervalMs;

    private Directory directory;
    private IndexWriter indexWriter;
    private SearcherManager searcherManager;
    private ScheduledExecutorService refreshScheduler;
    private final StandardAnalyzer analyzer;
    private final DocumentIndexer documentIndexer;

    public LuceneIndexService(
            @Value("${lucene.index.path}") final String indexPath,
            @Value("${lucene.nrt.refresh.interval.ms:100}") final long nrtRefreshIntervalMs,
            final DocumentIndexer documentIndexer) {
        this.analyzer = new StandardAnalyzer();
        this.indexPath = indexPath;
        this.nrtRefreshIntervalMs = nrtRefreshIntervalMs;
        this.documentIndexer = documentIndexer;
    }

    @PostConstruct
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

    @PreDestroy
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

    public void addDocuments(final List<Document> documents) throws IOException {
        for (final Document document : documents) {
            indexWriter.addDocument(document);
        }
    }

    public void deleteDocuments(final String field, final String value) throws IOException {
        indexWriter.deleteDocuments(new org.apache.lucene.index.Term(field, value));
    }

    public void commit() throws IOException {
        indexWriter.commit();
    }

    public java.util.Set<String> getIndexedFields() throws IOException {
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            final java.util.Set<String> fields = new java.util.HashSet<>();
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
