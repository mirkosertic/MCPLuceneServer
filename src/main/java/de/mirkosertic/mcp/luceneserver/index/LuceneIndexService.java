package de.mirkosertic.mcp.luceneserver.index;

import de.mirkosertic.mcp.luceneserver.index.analysis.GermanTransliteratingAnalyzer;
import de.mirkosertic.mcp.luceneserver.index.analysis.LemmatizerCacheStats;
import de.mirkosertic.mcp.luceneserver.index.analysis.OpenNLPLemmatizingAnalyzer;
import de.mirkosertic.mcp.luceneserver.index.analysis.ReverseUnicodeNormalizingAnalyzer;
import de.mirkosertic.mcp.luceneserver.index.analysis.UnicodeNormalizingAnalyzer;
import de.mirkosertic.mcp.luceneserver.index.query.IndividualPassageFormatter;
import de.mirkosertic.mcp.luceneserver.index.query.ProximityExpandingQueryParser;
import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.config.BuildInfo;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
import de.mirkosertic.mcp.luceneserver.onnx.ONNXService;
import de.mirkosertic.mcp.luceneserver.util.TextCleaner;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ActiveFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.DocumentScoringExplanation;
import de.mirkosertic.mcp.luceneserver.mcp.dto.FacetCostAnalysis;
import de.mirkosertic.mcp.luceneserver.mcp.dto.FacetDimensionCost;
import de.mirkosertic.mcp.luceneserver.mcp.dto.FilterImpact;
import de.mirkosertic.mcp.luceneserver.mcp.dto.FilterImpactAnalysis;
import de.mirkosertic.mcp.luceneserver.mcp.dto.Passage;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ProfileQueryResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.QueryAnalysis;
import de.mirkosertic.mcp.luceneserver.mcp.dto.QueryComponent;
import de.mirkosertic.mcp.luceneserver.mcp.dto.QueryRewrite;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ScoreComponent;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ScoreDetails;
import de.mirkosertic.mcp.luceneserver.mcp.dto.ScoringBreakdown;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchDocument;
import de.mirkosertic.mcp.luceneserver.mcp.dto.VectorSearchDebug;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchMetrics;
import de.mirkosertic.mcp.luceneserver.mcp.dto.TermStatistics;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.DrillSideways;
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
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Types;
import java.util.Collections;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.time.Instant;
import java.util.Locale;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

    /** Fields indexed as SortedSetDocValuesFacetField (facetable via DrillSideways). */
    static final Set<String> FACETED_FIELDS = Set.of(
            "language", "file_extension", "file_type", "author");

    /** Fields indexed as LongPoint (support range queries). */
    public static final Set<String> LONG_POINT_FIELDS = Set.of(
            "file_size", "created_date", "modified_date", "indexed_date");

    /** Subset of LONG_POINT_FIELDS that represent dates (epoch millis). */
    static final Set<String> DATE_FIELDS = Set.of(
            "created_date", "modified_date", "indexed_date");

    /** Fields that are analyzed TextField — cannot be filtered with exact term match. */
    static final Set<String> ANALYZED_FIELDS = Set.of(
            "content", "content_reversed", "content_lemma_de", "content_lemma_en",
            "content_translit_de",
            "keywords", "file_name", "title", "author", "creator", "subject");

    /** Mapping from language code to the corresponding stemmed shadow field. */
    static final Map<String, String> STEMMED_FIELD_BY_LANGUAGE = Map.of(
            "de", "content_lemma_de",
            "en", "content_lemma_en");

    /** Fields that are StringField (exact match, not analyzed). */
    static final Set<String> STRING_FIELDS = Set.of(
            "file_path", "file_extension", "file_type", "language", "content_hash");

    /**
     * Fields whose analyzers apply a LowerCaseFilter, so wildcard/prefix term text must be
     * lowercased before querying to match the indexed tokens.
     */
    static final Set<String> LOWERCASE_WILDCARD_FIELDS = Set.of(
            "content", "content_reversed", "content_lemma_de", "content_lemma_en",
            "content_translit_de");

    /**
     * Controls how the query string is parsed.
     *
     * <ul>
     *   <li>{@code SIMPLE} — the query string is escaped with {@link QueryParser#escape} before
     *       parsing, so special Lucene characters are treated as literals.</li>
     *   <li>{@code EXTENDED} — the query string is parsed as-is, allowing the full Lucene query
     *       syntax (wildcards, boolean operators, phrases, etc.).</li>
     * </ul>
     */
    public enum QueryMode { SIMPLE, EXTENDED }

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

    /** Dynamically registered facet fields from JDBC metadata enrichment. */
    private final Set<String> dynamicFacetedFields = new java.util.concurrent.CopyOnWriteArraySet<>();
    final Set<String> dynamicLongPointFields = new java.util.concurrent.CopyOnWriteArraySet<>();
    final Set<String> dynamicIntPointFields = new java.util.concurrent.CopyOnWriteArraySet<>();
    final Set<String> dynamicDateFields = new java.util.concurrent.CopyOnWriteArraySet<>();

    private final ApplicationConfig config;
    private final String indexPath;
    private volatile long nrtRefreshIntervalMs;

    /** Nullable: non-null only when vector search is enabled. */
    private final ONNXService onnxService;

    /**
     * BitSet producer used for block-join parent filtering.
     * Identifies parent documents (those with _doc_type = "parent") within each segment.
     */
    private final QueryBitSetProducer parentFilter = new QueryBitSetProducer(
            new TermQuery(new Term(DocumentIndexer.DOC_TYPE_FIELD, DocumentIndexer.DOC_TYPE_PARENT)));

    private Directory directory;
    private IndexWriter indexWriter;
    private SearcherManager searcherManager;
    private ScheduledExecutorService refreshScheduler;
    private final Analyzer indexAnalyzer;
    private final Analyzer queryAnalyzer;
    private final DocumentIndexer documentIndexer;
    private boolean schemaUpgradeRequired = false;

    // Lemmatizing analyzers for cache stats access
    private final OpenNLPLemmatizingAnalyzer deLemmatizer;
    private final OpenNLPLemmatizingAnalyzer enLemmatizer;

    // Language distribution cache for stemmed query boosting
    private volatile Map<String, Long> cachedLanguageDistribution = Map.of();
    private volatile long cachedTotalDocs = 0;

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

    /** Convenience constructor without vector search (onnxService = null). */
    public LuceneIndexService(final ApplicationConfig config,
                              final DocumentIndexer documentIndexer) {
        this(config, documentIndexer, null);
    }

    public LuceneIndexService(final ApplicationConfig config,
                              final DocumentIndexer documentIndexer,
                              final ONNXService onnxService) {
        this.config = config;
        this.onnxService = onnxService;
        final Analyzer defaultAnalyzer = new UnicodeNormalizingAnalyzer();
        this.deLemmatizer = new OpenNLPLemmatizingAnalyzer("de", true);
        this.enLemmatizer = new OpenNLPLemmatizingAnalyzer("en", true);
        final GermanTransliteratingAnalyzer translitAnalyzer = new GermanTransliteratingAnalyzer();
        // Index analyzer: sentence-aware OpenNLP pipeline for accurate POS tagging on long texts
        this.indexAnalyzer = new PerFieldAnalyzerWrapper(defaultAnalyzer, Map.of(
                "content_reversed", new ReverseUnicodeNormalizingAnalyzer(),
                "content_lemma_de", deLemmatizer,
                "content_lemma_en", enLemmatizer,
                "content_translit_de", translitAnalyzer
        ));
        // Query analyzer: simple mode (no sentence detection) for better handling of short queries
        this.queryAnalyzer = new PerFieldAnalyzerWrapper(defaultAnalyzer, Map.of(
                "content_reversed", new ReverseUnicodeNormalizingAnalyzer(),
                "content_lemma_de", deLemmatizer.withSentenceDetection(false),
                "content_lemma_en", enLemmatizer.withSentenceDetection(false),
                "content_translit_de", translitAnalyzer
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
        int storedEmbeddingDimension = 0;
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
                final String embDimStr = userData.get("embedding_dimension");
                if (embDimStr != null) {
                    storedEmbeddingDimension = Integer.parseInt(embDimStr);
                    logger.info("Existing index has embedding_dimension: {}", storedEmbeddingDimension);
                }
            }
        }

        // Open IndexWriter
        final IndexWriterConfig config = new IndexWriterConfig(indexAnalyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        indexWriter = new IndexWriter(directory, config);

        // Build commit metadata map: schema_version + software_version + optional embedding_dimension
        final Map<String, String> commitData = new java.util.LinkedHashMap<>();
        commitData.put("schema_version", String.valueOf(DocumentIndexer.SCHEMA_VERSION));
        commitData.put("software_version", BuildInfo.getVersion());
        if (onnxService != null) {
            commitData.put("embedding_dimension", String.valueOf(onnxService.getHiddenSize()));
        }

        // Set commit user data with current schema version, software version, and embedding dimension
        indexWriter.setLiveCommitData(commitData.entrySet());

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

            // Check embedding dimension mismatch when vector search is enabled
            if (onnxService != null && storedEmbeddingDimension > 0
                    && storedEmbeddingDimension != onnxService.getHiddenSize()) {
                schemaUpgradeRequired = true;
                logger.warn("Schema upgrade required: embedding dimension mismatch " +
                        "(stored={}, current={})", storedEmbeddingDimension, onnxService.getHiddenSize());
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

        // Initialize language distribution cache for stemmed query boosting
        refreshLanguageDistribution();

        logger.info("Lucene index initialized at: {} with NRT refresh interval {}ms",
                path.toAbsolutePath(), nrtRefreshIntervalMs);
    }

    private void maybeRefreshSearcher() {
        try {
            searcherManager.maybeRefresh();
            refreshLanguageDistribution();
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

    /**
     * Index a single document, optionally with block-join child chunk documents when
     * vector search is enabled.
     *
     * <p>When {@code onnxService} is non-null and the document has content, the content
     * is embedded with late chunking and the resulting child chunk documents are prepended
     * to the parent in a block-join block (children first, parent last) so that
     * {@link KnnFloatVectorQuery} with a ToParentBlockJoinQuery can find the parent
     * document by its nearest-neighbour chunk.</p>
     *
     * <p>When {@code onnxService} is null (vector search disabled) or the content is
     * blank, falls back to the standard single-document indexing path.</p>
     *
     * @param parentDoc the parent {@link Document} created by {@link de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer#createDocument}
     * @param content   the raw text content of the document (may be null or blank)
     * @throws IOException if writing to the index fails
     */
    public void indexDocument(final Document parentDoc,
                              final String content) throws IOException {

        final String filePath = parentDoc.get("file_path");

        if (onnxService != null && content != null && !content.isBlank()) {
            try {
                final List<float[]> embeddings = onnxService.embedWithLateChunking(
                        content, ONNXService.PASSAGE_PREFIX, ONNXService.DEFAULT_BATCH_SIZE);

                if (!embeddings.isEmpty()) {
                    // Build approximate chunk texts by splitting content into equal character parts
                    final List<String> chunkTexts = splitContentIntoChunks(content, embeddings.size());

                    // Build faceted parent document first
                    final Document facetedParent = documentIndexer.getFacetsConfig().build(parentDoc);

                    // Create child documents
                    final List<Document> children = documentIndexer.createChildDocuments(
                            filePath, embeddings, chunkTexts);

                    // Block join: children first, parent last
                    final List<Document> block = new ArrayList<>(children.size() + 1);
                    block.addAll(children);
                    block.add(facetedParent);

                    // Delete old block (parent + its children) before inserting new block
                    indexWriter.deleteDocuments(new Term("file_path", filePath));
                    indexWriter.addDocuments(block);
                    return;
                }
            } catch (final Exception e) {
                logger.warn("Embedding failed for '{}', falling back to standard indexing: {}",
                        filePath, e.getMessage());
            }
        }

        if (filePath != null) {
            // Update or insert document (using file_path as unique identifier)
            indexWriter.updateDocument(new Term("file_path", filePath), parentDoc);
        } else {
            indexWriter.addDocument(parentDoc);
        }
    }

    /**
     * Split content into approximately equal character-length chunks.
     *
     * @param content    the full document content
     * @param numChunks  number of chunks to produce (must be &gt; 0)
     * @return list of chunk strings, size == numChunks
     */
    private static List<String> splitContentIntoChunks(final String content, final int numChunks) {
        if (numChunks <= 1) {
            return List.of(content);
        }
        final int len = content.length();
        final int chunkSize = Math.max(1, len / numChunks);
        final List<String> chunks = new ArrayList<>(numChunks);
        int start = 0;
        for (int i = 0; i < numChunks; i++) {
            final int end = (i == numChunks - 1) ? len : Math.min(start + chunkSize, len);
            chunks.add(content.substring(start, end));
            start = end;
            if (start >= len) {
                // Fill remaining slots with empty string placeholder if content is exhausted
                while (chunks.size() < numChunks) {
                    chunks.add("");
                }
                break;
            }
        }
        return chunks;
    }

    /**
     * Build a Sort object from sortBy and sortOrder parameters.
     *
     * @param sortBy    the field to sort by (_score, modified_date, created_date, file_size)
     * @param sortOrder the sort order (asc, desc)
     * @return Sort object, or null for default score-based sorting
     */
    private Sort buildSort(final String sortBy, final String sortOrder) {
        if ("_score".equals(sortBy)) {
            // Score sorting is default, no explicit Sort needed
            // Lucene sorts by score descending by default
            return null;
        }

        final boolean reverse = "desc".equals(sortOrder);

        final SortField sortField = switch (sortBy) {
            case "modified_date" -> new SortedNumericSortField("modified_date", SortField.Type.LONG, reverse);
            case "created_date" -> new SortedNumericSortField("created_date", SortField.Type.LONG, reverse);
            case "file_size" -> new SortedNumericSortField("file_size", SortField.Type.LONG, reverse);
            default -> SortField.FIELD_SCORE;  // Fallback to score
        };

        // Always include score as secondary sort for tie-breaking
        return new Sort(sortField, SortField.FIELD_SCORE);
    }

    /**
     * Search the index with structured multi-filters and DrillSideways faceting.
     * Backward-compatible overload that defaults to {@link QueryMode#EXTENDED}.
     *
     * @param queryString the user query (null or blank = MatchAllDocsQuery)
     * @param filters     list of structured filters (may be empty)
     * @param page        0-based page number
     * @param pageSize    results per page
     * @param sortBy      the field to sort by
     * @param sortOrder   the sort order
     * @return search results with documents, facets, and active filters
     */
    public SearchResult search(final String queryString, final List<SearchFilter> filters,
                               final int page, final int pageSize,
                               final String sortBy, final String sortOrder) throws IOException, ParseException {
        return search(queryString, filters, page, pageSize, sortBy, sortOrder, QueryMode.EXTENDED);
    }

    /**
     * Search the index with structured multi-filters and DrillSideways faceting.
     *
     * @param queryString the user query (null or blank = MatchAllDocsQuery)
     * @param filters     list of structured filters (may be empty)
     * @param page        0-based page number
     * @param pageSize    results per page
     * @param sortBy      the field to sort by
     * @param sortOrder   the sort order
     * @param queryMode   {@link QueryMode#SIMPLE} escapes the query string before parsing;
     *                    {@link QueryMode#EXTENDED} parses as-is (full Lucene syntax)
     * @return search results with documents, facets, and active filters
     */
    public SearchResult search(final String queryString, final List<SearchFilter> filters,
                               final int page, final int pageSize,
                               final String sortBy, final String sortOrder,
                               final QueryMode queryMode) throws IOException, ParseException {

        // Validate all filters first
        for (final SearchFilter filter : filters) {
            final String error = validateFilter(filter);
            if (error != null) {
                throw new IllegalArgumentException(error);
            }
        }

        final IndexSearcher searcher = searcherManager.acquire();
        try {
            // 1. Build main query (with stemming) and highlight query (unstemmed)
            final Query mainQuery;
            final Query highlightQuery;
            if (queryString == null || queryString.isBlank()) {
                mainQuery = new MatchAllDocsQuery();
                highlightQuery = mainQuery;
            } else {
                // In SIMPLE mode, escape special Lucene characters so the query is treated as literals.
                final String effectiveQueryString = queryMode == QueryMode.SIMPLE
                        ? QueryParser.escape(queryString)
                        : queryString;
                final QueryParser parser = new ProximityExpandingQueryParser("content", queryAnalyzer);
                parser.setAllowLeadingWildcard(true);
                final Query parsed = parser.parse(effectiveQueryString);
                final Query contentQuery = rewriteLeadingWildcards(parsed);
                highlightQuery = contentQuery;

                // Build stemmed query: content (boosted) + stemmed fields
                final String languageHint = extractLanguageHint(filters);
                mainQuery = buildStemmedQuery(contentQuery, effectiveQueryString, languageHint);
            }

            // 2. Classify filters
            final List<SearchFilter> positiveFacetFilters = new ArrayList<>();
            final List<SearchFilter> negativeFilters = new ArrayList<>();
            final List<SearchFilter> rangeFilters = new ArrayList<>();
            final List<SearchFilter> stringTermFilters = new ArrayList<>();
            final List<SearchFilter> longPointEqFilters = new ArrayList<>();
            final List<SearchFilter> intPointEqFilters = new ArrayList<>();

            for (final SearchFilter f : filters) {
                final String op = f.effectiveOperator();
                final String field = f.field();

                switch (op) {
                    case "not", "not_in" -> negativeFilters.add(f);
                    case "range" -> rangeFilters.add(f);
                    case "eq", "in" -> {
                        if (FACETED_FIELDS.contains(field) || dynamicFacetedFields.contains(field)) {
                            positiveFacetFilters.add(f);
                        } else if (isLongPointField(field)) {
                            longPointEqFilters.add(f);
                        } else if (isIntPointField(field)) {
                            intPointEqFilters.add(f);
                        } else if (STRING_FIELDS.contains(field)) {
                            stringTermFilters.add(f);
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown filter operator: " + op);
                }
            }

            // 3. Build base BooleanQuery: mainQuery MUST + range FILTER + NOT MUST_NOT + string/longpoint FILTER
            final BooleanQuery.Builder baseBuilder = new BooleanQuery.Builder();
            baseBuilder.add(mainQuery, BooleanClause.Occur.MUST);

            // Range filters
            for (final SearchFilter rf : rangeFilters) {
                if (isIntPointField(rf.field())) {
                    final int fromVal = rf.from() != null ? parseIntFilterValue(rf.field(), rf.from()) : Integer.MIN_VALUE;
                    final int toVal = rf.to() != null ? parseIntFilterValue(rf.field(), rf.to()) : Integer.MAX_VALUE;
                    baseBuilder.add(IntPoint.newRangeQuery(rf.field(), fromVal, toVal), BooleanClause.Occur.FILTER);
                } else {
                    final long fromVal = rf.from() != null ? parseLongFilterValue(rf.field(), rf.from()) : Long.MIN_VALUE;
                    final long toVal = rf.to() != null ? parseLongFilterValue(rf.field(), rf.to()) : Long.MAX_VALUE;
                    baseBuilder.add(LongPoint.newRangeQuery(rf.field(), fromVal, toVal), BooleanClause.Occur.FILTER);
                }
            }

            // Negative filters
            for (final SearchFilter nf : negativeFilters) {
                final String op = nf.effectiveOperator();
                if ("not".equals(op) && nf.value() != null) {
                    if (FACETED_FIELDS.contains(nf.field()) || STRING_FIELDS.contains(nf.field())
                            || dynamicFacetedFields.contains(nf.field())) {
                        baseBuilder.add(new TermQuery(new Term(nf.field(), nf.value())), BooleanClause.Occur.MUST_NOT);
                    } else if (isLongPointField(nf.field())) {
                        final long val = parseLongFilterValue(nf.field(), nf.value());
                        baseBuilder.add(LongPoint.newExactQuery(nf.field(), val), BooleanClause.Occur.MUST_NOT);
                    } else if (isIntPointField(nf.field())) {
                        final int val = parseIntFilterValue(nf.field(), nf.value());
                        baseBuilder.add(IntPoint.newExactQuery(nf.field(), val), BooleanClause.Occur.MUST_NOT);
                    }
                } else if ("not_in".equals(op) && nf.values() != null) {
                    for (final String v : nf.values()) {
                        if (FACETED_FIELDS.contains(nf.field()) || STRING_FIELDS.contains(nf.field())
                                || dynamicFacetedFields.contains(nf.field())) {
                            baseBuilder.add(new TermQuery(new Term(nf.field(), v)), BooleanClause.Occur.MUST_NOT);
                        } else if (isLongPointField(nf.field())) {
                            final long val = parseLongFilterValue(nf.field(), v);
                            baseBuilder.add(LongPoint.newExactQuery(nf.field(), val), BooleanClause.Occur.MUST_NOT);
                        } else if (isIntPointField(nf.field())) {
                            final int val = parseIntFilterValue(nf.field(), v);
                            baseBuilder.add(IntPoint.newExactQuery(nf.field(), val), BooleanClause.Occur.MUST_NOT);
                        }
                    }
                }
            }

            // String term filters (eq/in on StringField, non-faceted)
            for (final SearchFilter sf : stringTermFilters) {
                if ("eq".equals(sf.effectiveOperator()) && sf.value() != null) {
                    baseBuilder.add(new TermQuery(new Term(sf.field(), sf.value())), BooleanClause.Occur.FILTER);
                } else if ("in".equals(sf.effectiveOperator()) && sf.values() != null) {
                    final BooleanQuery.Builder orBuilder = new BooleanQuery.Builder();
                    for (final String v : sf.values()) {
                        orBuilder.add(new TermQuery(new Term(sf.field(), v)), BooleanClause.Occur.SHOULD);
                    }
                    baseBuilder.add(orBuilder.build(), BooleanClause.Occur.FILTER);
                }
            }

            // LongPoint exact/in filters
            for (final SearchFilter lf : longPointEqFilters) {
                if ("eq".equals(lf.effectiveOperator()) && lf.value() != null) {
                    final long val = parseLongFilterValue(lf.field(), lf.value());
                    baseBuilder.add(LongPoint.newExactQuery(lf.field(), val), BooleanClause.Occur.FILTER);
                } else if ("in".equals(lf.effectiveOperator()) && lf.values() != null) {
                    final long[] vals = new long[lf.values().size()];
                    for (int i = 0; i < lf.values().size(); i++) {
                        vals[i] = parseLongFilterValue(lf.field(), lf.values().get(i));
                    }
                    baseBuilder.add(LongPoint.newSetQuery(lf.field(), vals), BooleanClause.Occur.FILTER);
                }
            }

            // IntPoint exact/in filters
            for (final SearchFilter inf : intPointEqFilters) {
                if ("eq".equals(inf.effectiveOperator()) && inf.value() != null) {
                    final int val = parseIntFilterValue(inf.field(), inf.value());
                    baseBuilder.add(IntPoint.newExactQuery(inf.field(), val), BooleanClause.Occur.FILTER);
                } else if ("in".equals(inf.effectiveOperator()) && inf.values() != null) {
                    final int[] vals = new int[inf.values().size()];
                    for (int i = 0; i < inf.values().size(); i++) {
                        vals[i] = parseIntFilterValue(inf.field(), inf.values().get(i));
                    }
                    baseBuilder.add(IntPoint.newSetQuery(inf.field(), vals), BooleanClause.Occur.FILTER);
                }
            }

            final Query baseQuery = baseBuilder.build();

            // 4. Build sort
            final Sort sort = buildSort(sortBy, sortOrder);

            // 5. Pagination
            final int startIndex = page * pageSize;
            final int maxResults = startIndex + pageSize;

            // 6. Execute search — DrillSideways if positive facet filters exist, otherwise FacetsCollectorManager
            final TopDocs topDocs;
            final FacetBuildResult facetBuildResult;

            if (!positiveFacetFilters.isEmpty()) {
                // DrillSideways path
                final SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(
                        searcher.getIndexReader(), documentIndexer.getFacetsConfig());

                final DrillDownQuery ddq = new DrillDownQuery(documentIndexer.getFacetsConfig(), baseQuery);
                // Group positive facet filters by dimension — same dimension = OR
                final Map<String, List<String>> facetValuesByDim = new LinkedHashMap<>();
                for (final SearchFilter pf : positiveFacetFilters) {
                    final List<String> vals = new ArrayList<>();
                    if ("eq".equals(pf.effectiveOperator()) && pf.value() != null) {
                        vals.add(pf.value());
                    } else if ("in".equals(pf.effectiveOperator()) && pf.values() != null) {
                        vals.addAll(pf.values());
                    }
                    facetValuesByDim.computeIfAbsent(pf.field(), k -> new ArrayList<>()).addAll(vals);
                }
                for (final var entry : facetValuesByDim.entrySet()) {
                    for (final String val : entry.getValue()) {
                        ddq.add(entry.getKey(), val);
                    }
                }

                final DrillSideways ds = new DrillSideways(searcher, documentIndexer.getFacetsConfig(), state);
                final DrillSideways.DrillSidewaysResult dsResult;
                if (sort != null) {
                    dsResult = ds.search(ddq, null, null, maxResults, sort, false);
                } else {
                    dsResult = ds.search(ddq, maxResults);
                }

                topDocs = dsResult.hits;
                facetBuildResult = buildFacetsFromDrillSideways(dsResult.facets);
            } else {
                // Standard FacetsCollectorManager path (preserves original behavior)
                final FacetsCollectorManager facetsCollectorManager = new FacetsCollectorManager();
                final FacetsCollectorManager.FacetsResult result;
                if (sort != null) {
                    result = FacetsCollectorManager.search(
                            searcher, baseQuery, maxResults, sort, facetsCollectorManager);
                } else {
                    result = FacetsCollectorManager.search(
                            searcher, baseQuery, maxResults, facetsCollectorManager);
                }

                topDocs = result.topDocs();
                facetBuildResult = buildFacets(searcher, result.facetsCollector());
            }
            final Map<String, List<FacetValue>> facets = facetBuildResult.facets();

            final long totalHits = topDocs.totalHits.value();

            // 7. Highlighting — uses highlightQuery (unstemmed content only) so <em> tags
            //    wrap the correct surface forms; docs found only via stemmed fields get
            //    fallback passages via withMaxNoHighlightPassages(1)
            final int maxPassages = config.getMaxPassages();
            final int maxPassageCharLength = config.getMaxPassageCharLength();
            final PassageAwareHighlighter highlighter = new PassageAwareHighlighter(
                    UnifiedHighlighter.builder(searcher, queryAnalyzer)
                            .withMaxLength(10_000)
                            .withFormatter(new IndividualPassageFormatter())
                            .withHandleMultiTermQuery(true)
                            .withBreakIterator(BreakIterator::getSentenceInstance)
                            .withMaxNoHighlightPassages(1)
            );
            final Set<String> queryTerms = extractQueryTerms(highlightQuery);

            // 8. Collect results for the requested page (BM25 only — hybrid RRF removed)
            final ScoreDoc[] scoreDocs = topDocs.scoreDocs;
            final List<SearchDocument> results = new ArrayList<>();

            for (int i = startIndex; i < scoreDocs.length && i < maxResults; i++) {
                final Document doc = searcher.storedFields().document(scoreDocs[i].doc);

                logger.debug("Query for highlighting: {}", highlightQuery);
                final String content = doc.get("content");
                final List<Passage> passages = createPassages(
                        content, highlightQuery, highlighter, maxPassages, maxPassageCharLength, queryTerms, scoreDocs[i]);

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

            // 10. Compute active filters
            final List<ActiveFilter> activeFilters = computeActiveFilters(filters, facets);

            return new SearchResult(results, totalHits, page, pageSize, facets, activeFilters,
                    facetBuildResult.perFieldDurationMicros(), facetBuildResult.totalDurationMicros());
        } finally {
            searcherManager.release(searcher);
        }
    }


    /**
     * Profile a query to understand its behavior, performance, and scoring.
     *
     * @param request the profile query request
     * @return detailed profiling information
     */
    public ProfileQueryResponse profileQuery(final ProfileQueryRequest request)
            throws IOException, ParseException {

        // Validate all filters first
        for (final SearchFilter filter : request.effectiveFilters()) {
            final String error = validateFilter(filter);
            if (error != null) {
                throw new IllegalArgumentException(error);
            }
        }

        final IndexSearcher searcher = searcherManager.acquire();
        try {
            // Build main query (with stemming and leading wildcard rewriting)
            final Query mainQuery;
            if (request.effectiveQuery() == null || request.effectiveQuery().isBlank()) {
                mainQuery = new MatchAllDocsQuery();
            } else {
                // In SIMPLE mode, escape special Lucene characters so the query is treated as literals.
                final String effectiveQueryString = request.effectiveQueryMode() == QueryMode.SIMPLE
                        ? QueryParser.escape(request.effectiveQuery())
                        : request.effectiveQuery();
                final QueryParser parser = new ProximityExpandingQueryParser("content", queryAnalyzer);
                parser.setAllowLeadingWildcard(true);
                final Query parsed = parser.parse(effectiveQueryString);
                final Query contentQuery = rewriteLeadingWildcards(parsed);
                final String languageHint = extractLanguageHint(request.effectiveFilters());
                mainQuery = buildStemmedQuery(contentQuery, effectiveQueryString, languageHint);
            }

            // Level 1: Fast analysis (always included)
            final QueryAnalysis queryAnalysis = analyzeQueryStructure(
                    mainQuery, request.effectiveQuery(), searcher);
            final SearchMetrics searchMetrics = computeSearchMetrics(
                    mainQuery, request.effectiveFilters(), searcher);

            // Level 2: Filter impact (opt-in, expensive)
            FilterImpactAnalysis filterImpact = null;
            if (request.effectiveAnalyzeFilterImpact() && !request.effectiveFilters().isEmpty()) {
                filterImpact = analyzeFilterImpact(mainQuery, request.effectiveFilters(), searcher);
            }

            // Level 3: Document scoring explanations (opt-in, expensive)
            List<DocumentScoringExplanation> docExplanations = null;
            if (request.effectiveAnalyzeDocumentScoring()) {
                docExplanations = analyzeDocumentScoring(
                        mainQuery, request.effectiveFilters(), request.effectiveMaxDocExplanations(), searcher);
            }

            // Level 4: Facet cost analysis (opt-in, expensive)
            FacetCostAnalysis facetCost = null;
            if (request.effectiveAnalyzeFacetCost()) {
                facetCost = analyzeFacetCost(mainQuery, request.effectiveFilters(), searcher);
            }

            // Generate recommendations
            final List<String> recommendations = generateRecommendations(
                    queryAnalysis, searchMetrics, filterImpact, docExplanations);

            return ProfileQueryResponse.success(
                    queryAnalysis, searchMetrics, filterImpact,
                    docExplanations, facetCost, recommendations);

        } finally {
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

    /**
     * Register a dynamically created facet field (e.g. from JDBC metadata enrichment).
     * The field will be included in facet computations for all subsequent searches.
     *
     * @param fieldName the fully-qualified Lucene field name (e.g. "dbmeta_customer_id")
     */
    public void registerFacetField(final String fieldName) {
        if (dynamicFacetedFields.add(fieldName)) {
            logger.debug("Registered dynamic facet field: {}", fieldName);
        }
    }

    /**
     * Register a dynamically created LongPoint field (e.g. a LONG-type JDBC metadata field).
     * The field will be treated as a numeric point field for filter query construction.
     *
     * @param fieldName the fully-qualified Lucene field name (e.g. "dbmeta_amount")
     */
    public void registerLongPointField(final String fieldName) {
        if (dynamicLongPointFields.add(fieldName)) {
            logger.debug("Registered dynamic LongPoint field: {}", fieldName);
        }
    }

    /**
     * Register a dynamically created date field (e.g. a DATE-type JDBC metadata field).
     * The field will be treated as a date point field, supporting ISO-8601 value parsing.
     *
     * @param fieldName the fully-qualified Lucene field name (e.g. "dbmeta_created_at")
     */
    public void registerDateField(final String fieldName) {
        dynamicLongPointFields.add(fieldName);
        dynamicDateFields.add(fieldName);
        logger.debug("Registered dynamic date field: {}", fieldName);
    }

    private boolean isLongPointField(final String field) {
        return LONG_POINT_FIELDS.contains(field) || dynamicLongPointFields.contains(field);
    }

    /**
     * Register a dynamically created IntPoint field (e.g. an INT-type JDBC metadata field).
     * The field will be treated as a 32-bit numeric point field for filter query construction.
     *
     * @param fieldName the fully-qualified Lucene field name (e.g. "dbmeta_count")
     */
    public void registerIntPointField(final String fieldName) {
        dynamicIntPointFields.add(fieldName);
        logger.debug("Registered dynamic IntPoint field: {}", fieldName);
    }

    private boolean isIntPointField(final String field) {
        return dynamicIntPointFields.contains(field);
    }

    private boolean isDateField(final String field) {
        return DATE_FIELDS.contains(field) || dynamicDateFields.contains(field);
    }

    public void commit() throws IOException {
        indexWriter.commit();
    }

    /**
     * Delete all index documents whose {@code file_path} field matches the given path.
     * Used by the metadata sync service when a file has been removed from disk but its
     * metadata entry still exists in the database.
     *
     * @param filePath the file path to remove from the index
     * @throws IOException if the deletion fails
     */
    public void deleteDocumentByPath(final String filePath) throws IOException {
        indexWriter.deleteDocuments(new Term("file_path", filePath));
        logger.debug("Deleted document from index: {}", filePath);
    }

    /**
     * Finds file_paths of all parent documents matching the given field/value.
     * The Lucene query type is derived from jdbcType (java.sql.Types constant):
     *   - INTEGER, SMALLINT, TINYINT → IntPoint.newExactQuery()
     *   - BIGINT, NUMERIC, DECIMAL   → LongPoint.newExactQuery()
     *   - all other types            → TermQuery (exact string match)
     * The field must not be an analyzed TextField — tokenized fields will not match.
     *
     * @param fieldName the Lucene field name (e.g. "dbmeta_customer_id")
     * @param rawValue  the value to search for, as a string (parsed to numeric if needed)
     * @param jdbcType  the java.sql.Types constant describing the column type
     * @return unmodifiable list of file_path values from matching parent documents
     * @throws IOException if the index cannot be read
     */
    public List<String> findFilePathsByField(
            final String fieldName, final String rawValue, final int jdbcType) throws IOException {

        final Query fieldQuery;
        if (isIntJdbcType(jdbcType)) {
            fieldQuery = IntPoint.newExactQuery(fieldName, Integer.parseInt(rawValue));
        } else if (isLongJdbcType(jdbcType)) {
            fieldQuery = LongPoint.newExactQuery(fieldName, Long.parseLong(rawValue));
        } else {
            fieldQuery = new TermQuery(new Term(fieldName, rawValue));
        }

        final IndexSearcher searcher = searcherManager.acquire();
        try {
            final Query query = new BooleanQuery.Builder()
                    .add(fieldQuery, BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(DocumentIndexer.DOC_TYPE_FIELD, DocumentIndexer.DOC_TYPE_PARENT)),
                            BooleanClause.Occur.MUST)
                    .build();
            final TopDocs topDocs = searcher.search(query, 1000);
            final List<String> filePaths = new ArrayList<>();
            for (final ScoreDoc scoreDoc : topDocs.scoreDocs) {
                final Document doc = searcher.storedFields().document(scoreDoc.doc);
                final String filePath = doc.get("file_path");
                if (filePath != null) {
                    filePaths.add(filePath);
                }
            }
            return Collections.unmodifiableList(filePaths);
        } finally {
            searcherManager.release(searcher);
        }
    }

    // package-private for testing
    static boolean isIntJdbcType(final int jdbcType) {
        return switch (jdbcType) {
            case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> true;
            default -> false;
        };
    }

    static boolean isLongJdbcType(final int jdbcType) {
        return switch (jdbcType) {
            case Types.BIGINT, Types.NUMERIC, Types.DECIMAL -> true;
            default -> false;
        };
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

    // ==================== Index Observability ====================

    /**
     * Result of a term suggestion query.
     */
    public record TermSuggestionResult(List<Map.Entry<String, Integer>> terms, int totalMatched) {
    }

    /**
     * Result of a top-terms query.
     */
    public record TopTermsResult(List<Map.Entry<String, Integer>> terms, long uniqueTermCount, String warning) {
    }

    /**
     * Validate that a field name is suitable for term enumeration.
     *
     * @return error message if invalid, {@code null} if valid
     */
    public String validateTermField(final String field) {
        if (field == null || field.isBlank()) {
            return "Field name is required";
        }
        if (isLongPointField(field) || isIntPointField(field)) {
            return "Field '" + field + "' is a numeric/date point field and does not support term enumeration. " +
                    "Use getIndexStats for date field ranges, or filter with range queries.";
        }
        return null;
    }

    /**
     * Suggest terms from the index that match a given prefix.
     * <p>
     * For fields in {@link #LOWERCASE_WILDCARD_FIELDS}, the prefix is lowercased
     * to match the indexed (analyzed) tokens.
     * </p>
     *
     * @param field  the field to enumerate terms from
     * @param prefix the prefix to match
     * @param limit  maximum number of terms to return
     * @return suggestion result sorted by docFreq descending
     */
    public TermSuggestionResult suggestTerms(final String field, final String prefix, final int limit) throws IOException {
        final String effectivePrefix = LOWERCASE_WILDCARD_FIELDS.contains(field)
                ? prefix.toLowerCase(Locale.ROOT)
                : prefix;

        final IndexSearcher searcher = searcherManager.acquire();
        try {
            final Map<String, Integer> termFreqs = new HashMap<>();

            for (final LeafReaderContext leafCtx : searcher.getIndexReader().leaves()) {
                final Terms terms = leafCtx.reader().terms(field);
                if (terms == null) {
                    continue;
                }
                final TermsEnum termsEnum = terms.iterator();
                final TermsEnum.SeekStatus status = termsEnum.seekCeil(new BytesRef(effectivePrefix));
                if (status == TermsEnum.SeekStatus.END) {
                    continue;
                }

                do {
                    final BytesRef termBytes = termsEnum.term();
                    final String termText = termBytes.utf8ToString();
                    if (!termText.startsWith(effectivePrefix)) {
                        break;
                    }
                    termFreqs.merge(termText, termsEnum.docFreq(), Integer::sum);
                } while (termsEnum.next() != null);
            }

            final int totalMatched = termFreqs.size();

            final List<Map.Entry<String, Integer>> sorted = termFreqs.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(limit)
                    .toList();

            return new TermSuggestionResult(sorted, totalMatched);
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Get the most frequent terms in a field.
     * <p>
     * Iterates all terms in the field across all segments, aggregating doc frequencies.
     * For fields with more than 100,000 unique terms a warning is included in the result.
     * </p>
     *
     * @param field the field to enumerate terms from
     * @param limit maximum number of terms to return
     * @return top-terms result sorted by docFreq descending
     */
    public TopTermsResult getTopTerms(final String field, final int limit) throws IOException {
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            final Map<String, Integer> termFreqs = new HashMap<>();

            for (final LeafReaderContext leafCtx : searcher.getIndexReader().leaves()) {
                final Terms terms = leafCtx.reader().terms(field);
                if (terms == null) {
                    continue;
                }
                final TermsEnum termsEnum = terms.iterator();
                BytesRef termBytes;
                while ((termBytes = termsEnum.next()) != null) {
                    termFreqs.merge(termBytes.utf8ToString(), termsEnum.docFreq(), Integer::sum);
                }
            }

            final long uniqueTermCount = termFreqs.size();
            final String warning = uniqueTermCount > 100_000
                    ? "Field '" + field + "' has " + uniqueTermCount + " unique terms. " +
                      "Consider using suggestTerms with a prefix for more targeted exploration."
                    : null;

            final List<Map.Entry<String, Integer>> sorted = termFreqs.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(limit)
                    .toList();

            return new TopTermsResult(sorted, uniqueTermCount, warning);
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
            // Match only the parent document — child chunk documents also carry file_path
            // and appear before the parent in the Block Join segment order, so a plain
            // TermQuery on file_path alone would return a child chunk instead of the parent.
            final Query query = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term("file_path", filePath)), BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term(DocumentIndexer.DOC_TYPE_FIELD, DocumentIndexer.DOC_TYPE_PARENT)),
                            BooleanClause.Occur.MUST)
                    .build();

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
     * Returns lemmatizer cache statistics for all language analyzers.
     *
     * @return a map of language code to cache statistics
     */
    public Map<String, LemmatizerCacheStats> getLemmatizerCacheStats() {
        return Map.of(
                "de", deLemmatizer.getCacheStats(),
                "en", enLemmatizer.getCacheStats()
        );
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
        final IndexWriterConfig config = new IndexWriterConfig(indexAnalyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        indexWriter = new IndexWriter(directory, config);

        // Build commit metadata map: schema_version + software_version + optional embedding_dimension
        final Map<String, String> reinitCommitData = new java.util.LinkedHashMap<>();
        reinitCommitData.put("schema_version", String.valueOf(DocumentIndexer.SCHEMA_VERSION));
        reinitCommitData.put("software_version", BuildInfo.getVersion());
        if (onnxService != null) {
            reinitCommitData.put("embedding_dimension", String.valueOf(onnxService.getHiddenSize()));
        }

        // Set commit user data with current schema version, software version, and embedding dimension
        indexWriter.setLiveCommitData(reinitCommitData.entrySet());

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

            // Remove broken/invalid characters from passage text
            final String cleanedPassageText = TextCleaner.clean(passageText);
            if (cleanedPassageText == null || cleanedPassageText.isBlank()) {
                continue;
            }

            // --- score: normalise to 0-1 using the best passage's score as the maximum ---
            final double normalizedScore = maxScore > 0
                    ? Math.round((double) fp.score() / maxScore * 100.0) / 100.0
                    : 0.0;

            // --- matchedTerms: extract text between markdown bold markers, with fallback to query-term scanning ---
            final List<String> matchedTerms = extractMatchedTerms(cleanedPassageText, queryTerms);

            // --- termCoverage: unique matched terms / total query terms ---
            final double termCoverage = calculateTermCoverage(matchedTerms, queryTerms);

            // --- position: derived directly from the passage's start offset ---
            // (content is guaranteed non-empty by the early return at the top of this method)
            final double position = Math.round((double) fp.startOffset() / content.length() * 100.0) / 100.0;

            passages.add(new Passage(cleanedPassageText, normalizedScore, matchedTerms, termCoverage, position, "keyword", null));
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
        // Remove broken/invalid characters from fallback passage
        final String cleanedFallbackText = TextCleaner.clean(fallbackText);
        return new Passage(cleanedFallbackText, 0.0, List.of(), 0.0, 0.0, "keyword", null);
    }

    /**
     * Scan {@code text} for occurrences of each query term (case-insensitive, unicode-aware)
     * and wrap the first occurrence of each term in {@code **markdown bold**} markers.
     * This is used for semantic passages where Lucene's term-vector-based highlighter
    /**
     * Extract all terms wrapped in {@code **markdown bold**} markers from a highlighted passage.
     * Duplicates are removed (case-insensitive); order follows first appearance.
     *
     * <p>If no {@code **} markers are found but {@code queryTerms} is non-empty the method
     * falls back to scanning the passage for any query term that appears verbatim
     * (after NFKC + lowercase normalisation).  This handles the case where the
     * highlighter returns a relevant passage without wrapping the matched tokens.</p>
     *
     * @param highlightedText the passage text (may contain {@code **} markdown bold markup)
     * @param queryTerms      the original query terms; used only for the fallback scan
     * @return list of matched terms in first-appearance order
     */
    private static List<String> extractMatchedTerms(final String highlightedText, final Set<String> queryTerms) {
        final List<String> terms = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        // First try to extract from markdown bold syntax
        int searchFrom = 0;
        while (true) {
            final int emOpen = highlightedText.indexOf("**", searchFrom);
            if (emOpen < 0) {
                break;
            }
            final int emClose = highlightedText.indexOf("**", emOpen + 2);
            if (emClose < 0) {
                break;
            }
            final String term = highlightedText.substring(emOpen + 2, emClose);
            if (!term.isEmpty() && seen.add(term.toLowerCase())) {
                terms.add(term);
            }
            searchFrom = emClose + 2;
        }

        // Fallback: if no ** markers found, check which query terms appear in the passage
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
     * @param matchedTerms terms found in this passage (from {@code **} markdown bold markers or fallback scan)
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
    public static String truncatePassage(final String text, final int maxLength) {
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
        final int contextBefore = availableContext / 2;
        final int contextAfter = availableContext - contextBefore;

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
        if (query instanceof final TermQuery tq) {
            terms.add(tq.getTerm().text());
        } else if (query instanceof final BooleanQuery bq) {
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

    // ==================== Stemmed Query Support ====================

    /**
     * Refresh the cached language distribution from the index facets.
     * Called on NRT refresh and once at end of init().
     */
    private void refreshLanguageDistribution() {
        try {
            final IndexSearcher searcher = searcherManager.acquire();
            try {
                final long total = searcher.getIndexReader().numDocs();
                if (total == 0) {
                    cachedLanguageDistribution = Map.of();
                    cachedTotalDocs = 0;
                    return;
                }

                final Map<String, Long> distribution = new HashMap<>();
                final FacetsCollectorManager fcm = new FacetsCollectorManager();
                final FacetsCollectorManager.FacetsResult result =
                        FacetsCollectorManager.search(searcher, new MatchAllDocsQuery(), 1, fcm);

                try {
                    final SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(
                            searcher.getIndexReader(), documentIndexer.getFacetsConfig());
                    final Facets facets = new SortedSetDocValuesFacetCounts(state, result.facetsCollector());
                    final FacetResult langResult = facets.getTopChildren(100, "language");
                    if (langResult != null) {
                        for (final LabelAndValue lv : langResult.labelValues) {
                            distribution.put(lv.label, (long) lv.value.intValue());
                        }
                    }
                } catch (final IllegalArgumentException e) {
                    // No language facet in index yet
                    logger.debug("Language facet not available for distribution cache");
                }

                cachedLanguageDistribution = distribution;
                cachedTotalDocs = total;
            } finally {
                searcherManager.release(searcher);
            }
        } catch (final IOException e) {
            logger.debug("Failed to refresh language distribution cache", e);
        }
    }

    /**
     * Compute the boost for a stemmed field based on how many documents in the index
     * have that language. Soft scaling: {@code 0.3 + 0.7 * (langCount / totalDocs)}.
     * Returns a minimum of 0.3 even if no documents have that language.
     */
    private double computeStemmedBoost(final String languageCode) {
        final long total = cachedTotalDocs;
        if (total == 0) {
            return 0.3;
        }
        final long langCount = cachedLanguageDistribution.getOrDefault(languageCode, 0L);
        return 0.3 + 0.7 * ((double) langCount / total);
    }

    /**
     * Re-parse a query string targeting a specific field. The PerFieldAnalyzerWrapper
     * ensures the correct analyzer is used for the target field.
     */
    private Query parseQueryForField(final String queryString, final String targetField) throws ParseException {
        final QueryParser parser = new ProximityExpandingQueryParser(targetField, queryAnalyzer);
        parser.setAllowLeadingWildcard(true);
        return parser.parse(queryString);
    }

    /**
     * Build a combined query that includes the unstemmed content query (boosted highest)
     * plus stemmed variants for each supported language.
     *
     * <p>If a {@code languageHint} is provided (e.g. from a language filter), only that
     * language's stemmed field is included at boost 1.0. Otherwise, all supported stemmed
     * fields are included with dynamic boosts from the language distribution cache.</p>
     *
     * @param contentQuery   the unstemmed content query (already rewritten for leading wildcards)
     * @param rawQueryString the original query string to re-parse for stemmed fields
     * @param languageHint   optional language code hint (e.g. "de"), or null
     * @return combined BooleanQuery with minimumNumberShouldMatch(1)
     */
    private Query buildStemmedQuery(final Query contentQuery, final String rawQueryString,
                                    final String languageHint) throws ParseException {
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();

        // Unstemmed content query always boosted highest
        builder.add(new BoostQuery(contentQuery, 2.0f), BooleanClause.Occur.SHOULD);

        if (languageHint != null && STEMMED_FIELD_BY_LANGUAGE.containsKey(languageHint)) {
            // Single language hint — only include that stemmed field
            final String stemmedField = STEMMED_FIELD_BY_LANGUAGE.get(languageHint);
            // Apply rewriteLeadingWildcards() to normalize wildcard/prefix term case on analyzed fields
            final Query stemmedQuery = rewriteLeadingWildcards(parseQueryForField(rawQueryString, stemmedField));
            builder.add(new BoostQuery(stemmedQuery, 1.0f), BooleanClause.Occur.SHOULD);
        } else {
            // No hint — include all stemmed fields with distribution-based boost
            for (final Map.Entry<String, String> entry : STEMMED_FIELD_BY_LANGUAGE.entrySet()) {
                final String langCode = entry.getKey();
                final String stemmedField = entry.getValue();
                // Apply rewriteLeadingWildcards() to normalize wildcard/prefix term case on analyzed fields
                final Query stemmedQuery = rewriteLeadingWildcards(parseQueryForField(rawQueryString, stemmedField));
                final float boost = (float) computeStemmedBoost(langCode);
                builder.add(new BoostQuery(stemmedQuery, boost), BooleanClause.Occur.SHOULD);
            }
        }

        // Always add German transliteration field — harmless for non-German content,
        // enables Mueller→Müller matching regardless of language hint.
        final Query translitQuery = rewriteLeadingWildcards(parseQueryForField(rawQueryString, "content_translit_de"));
        builder.add(new BoostQuery(translitQuery, 0.5f), BooleanClause.Occur.SHOULD);

        builder.setMinimumNumberShouldMatch(1);
        return builder.build();
    }

    /**
     * Extract a language hint from filters: if there is exactly one {@code language eq "xx"}
     * filter, return its value. Otherwise return null.
     */
    static String extractLanguageHint(final List<SearchFilter> filters) {
        String hint = null;
        for (final SearchFilter f : filters) {
            if ("language".equals(f.field()) && "eq".equals(f.effectiveOperator()) && f.value() != null) {
                if (hint != null) {
                    return null; // Multiple language filters — no single hint
                }
                hint = f.value();
            }
        }
        return hint;
    }

    /**
     * Normalize and rewrite wildcard/prefix queries for efficient execution.
     *
     * <p>This method performs two normalizations:</p>
     * <ol>
     *   <li><b>Lowercasing</b>: Lucene's {@code QueryParser} does NOT apply the analyzer to
     *       wildcard or prefix terms, so a query like {@code Vertrag*} is created with the
     *       uppercase text {@code Vertrag*}. Because the index stores lowercased tokens, this
     *       would produce zero results. For fields whose analyzers apply a {@code LowerCaseFilter}
     *       (see {@link #LOWERCASE_WILDCARD_FIELDS}), the term text is lowercased here.</li>
     *   <li><b>Leading-wildcard rewriting</b>: Leading wildcards on the {@code content} field
     *       are rewritten to use the {@code content_reversed} field for efficient execution.</li>
     * </ol>
     *
     * <p>Rewriting rules for leading wildcards (only for queries on the {@code content} field):</p>
     * <ul>
     *   <li>{@code *vertrag} &rarr; {@code WildcardQuery("content_reversed", "gartrev*")}</li>
     *   <li>{@code *vertrag*} &rarr; {@code BooleanQuery(OR): content:*vertrag* OR content_reversed:gartrev*}</li>
     *   <li>{@code vertrag*} &rarr; no change (trailing wildcard is already efficient)</li>
     *   <li>{@code BooleanQuery} &rarr; recurse into sub-queries</li>
     *   <li>Everything else &rarr; no change</li>
     * </ul>
     */
    static Query rewriteLeadingWildcards(final Query query) {
        if (query instanceof final WildcardQuery wq) {
            final String field = wq.getTerm().field();
            final String text = wq.getTerm().text();

            // Lowercase wildcard term text on analyzed fields (analyzers apply LowerCaseFilter,
            // but QueryParser does NOT apply the analyzer to wildcard terms).
            final String normalizedText = LOWERCASE_WILDCARD_FIELDS.contains(field)
                    ? text.toLowerCase(Locale.ROOT) : text;

            // Only rewrite leading wildcards on the "content" field
            if (!"content".equals(field)) {
                // For non-content fields, just return lowercased version if needed
                if (!normalizedText.equals(text)) {
                    return new WildcardQuery(new Term(field, normalizedText));
                }
                return query;
            }

            final boolean startsWithWildcard = normalizedText.startsWith("*") || normalizedText.startsWith("?");
            final boolean endsWithWildcard = normalizedText.endsWith("*") || normalizedText.endsWith("?");

            if (!startsWithWildcard) {
                // Trailing wildcard only (e.g. vertrag*) -- already efficient
                if (!normalizedText.equals(text)) {
                    return new WildcardQuery(new Term(field, normalizedText));
                }
                return query;
            }

            // Strip the leading wildcard character
            final String core = normalizedText.substring(1);

            if (endsWithWildcard) {
                // Infix wildcard: *vertrag*
                // Strip trailing wildcard to get the core word for reversal
                final String coreWithoutTrailing = core.substring(0, core.length() - 1);
                final String reversed = new StringBuilder(coreWithoutTrailing).reverse().toString();

                // OR: original (lowercased) query on content OR reversed trailing wildcard on content_reversed
                final WildcardQuery normalizedWq = normalizedText.equals(text)
                        ? wq : new WildcardQuery(new Term(field, normalizedText));
                final BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(normalizedWq, BooleanClause.Occur.SHOULD);
                builder.add(new WildcardQuery(new Term("content_reversed", reversed + "*")),
                        BooleanClause.Occur.SHOULD);
                return builder.build();
            } else {
                // Pure leading wildcard: *vertrag
                // Reverse the core and make it a trailing wildcard on the reversed field
                final String reversed = new StringBuilder(core).reverse().toString();
                return new WildcardQuery(new Term("content_reversed", reversed + "*"));
            }

        } else if (query instanceof final PrefixQuery pq) {
            // QueryParser sometimes produces PrefixQuery (for "term*") instead of WildcardQuery.
            // Apply the same lowercasing normalization for analyzed fields.
            final String field = pq.getPrefix().field();
            final String text = pq.getPrefix().text();
            if (LOWERCASE_WILDCARD_FIELDS.contains(field)) {
                final String lower = text.toLowerCase(Locale.ROOT);
                if (!lower.equals(text)) {
                    return new PrefixQuery(new Term(field, lower));
                }
            }
            return query;

        } else if (query instanceof final BooleanQuery bq) {
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

    /**
     * Validate a filter and return an error message, or null if valid.
     */
    String validateFilter(final SearchFilter filter) {
        if (filter.field() == null || filter.field().isBlank()) {
            return "Filter field must not be blank";
        }
        final String op = filter.effectiveOperator();
        if (!Set.of("eq", "in", "not", "not_in", "range").contains(op)) {
            return "Unknown filter operator: " + op;
        }

        // Disallow eq/in/not/not_in on analyzed TextFields (content, keywords, file_name, title, etc.)
        // These fields are tokenized and cannot be filtered with exact term match.
        // Note: author, creator, subject ARE in ANALYZED_FIELDS but they also have faceted fields,
        // so they are allowed for facet-based filtering via FACETED_FIELDS.
        if (ANALYZED_FIELDS.contains(filter.field()) && !FACETED_FIELDS.contains(filter.field())
                && !STRING_FIELDS.contains(filter.field()) && !"range".equals(op)) {
            return "Cannot filter on analyzed field '" + filter.field()
                    + "' — use query syntax instead (e.g. field:value in the query string)";
        }

        // Range is only valid on LONG_POINT_FIELDS or INT_POINT_FIELDS
        if ("range".equals(op) && !isLongPointField(filter.field()) && !isIntPointField(filter.field())) {
            return "Range filter is only supported on numeric/date fields: " + LONG_POINT_FIELDS;
        }

        // Check required values
        if (("eq".equals(op) || "not".equals(op)) && (filter.value() == null || filter.value().isBlank())) {
            return "Filter operator '" + op + "' requires 'value'";
        }
        if (("in".equals(op) || "not_in".equals(op)) && (filter.values() == null || filter.values().isEmpty())) {
            return "Filter operator '" + op + "' requires 'values' array";
        }
        if ("range".equals(op) && filter.from() == null && filter.to() == null) {
            return "Range filter requires at least 'from' or 'to'";
        }

        return null;
    }

    /**
     * Parse an ISO-8601 date string to epoch millis.
     * Supports: "2024-01-15T14:30:00Z" (Instant), "2024-01-15T14:30:00" (LocalDateTime, UTC),
     * "2024-01-15" (LocalDate, start of day UTC).
     */
    public static long parseIso8601ToEpochMillis(final String value) {
        // Try Instant (2024-01-15T14:30:00Z)
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (final DateTimeParseException ignored) {
        }
        // Try LocalDateTime (2024-01-15T14:30:00) — assume UTC
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (final DateTimeParseException ignored) {
        }
        // Try LocalDate (2024-01-15) — start of day UTC
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (final DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException("Cannot parse date: '" + value
                + "'. Expected ISO-8601 format: '2024-01-15', '2024-01-15T10:30:00', or '2024-01-15T10:30:00Z'");
    }

    /**
     * Parse a filter value to a long — uses ISO-8601 parsing for date fields, Long.parseLong otherwise.
     */
    long parseLongFilterValue(final String field, final String value) {
        if (isDateField(field)) {
            return parseIso8601ToEpochMillis(value);
        }
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Cannot parse value '" + value + "' as number for field '" + field + "'");
        }
    }

    /**
     * Parse a filter value to an int — uses Integer.parseInt.
     */
    int parseIntFilterValue(final String field, final String value) {
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Cannot parse value '" + value + "' as integer for field '" + field + "'");
        }
    }

    /**
     * Build facets map from DrillSideways result.
     */
    private FacetBuildResult buildFacetsFromDrillSideways(final Facets facetsResult) {
        final Map<String, List<FacetValue>> facets = new HashMap<>();
        final Map<String, Long> perFieldDurationMicros = new LinkedHashMap<>();
        final long overallStart = System.nanoTime();
        final List<String> allDimensions = java.util.stream.Stream
                .concat(FACETED_FIELDS.stream(), dynamicFacetedFields.stream())
                .toList();
        for (final String dimension : allDimensions) {
            final long fieldStart = System.nanoTime();
            try {
                final FacetResult facetResult = facetsResult.getTopChildren(100, dimension);
                if (facetResult != null && facetResult.labelValues.length > 0) {
                    final List<FacetValue> values = new ArrayList<>();
                    for (final LabelAndValue lv : facetResult.labelValues) {
                        values.add(new FacetValue(lv.label, lv.value.intValue()));
                    }
                    facets.put(dimension, values);
                    if ("author".equals(dimension) && facetResult.labelValues.length > 500) {
                        logger.warn("author facet has {} unique values - consider whether faceting is appropriate for this corpus size",
                                facetResult.labelValues.length);
                    }
                }
            } catch (final Exception e) {
                logger.debug("Facet dimension {} not available in DrillSideways result", dimension);
            }
            perFieldDurationMicros.put(dimension, (System.nanoTime() - fieldStart) / 1_000L);
        }
        final long totalDurationMicros = (System.nanoTime() - overallStart) / 1_000L;
        return new FacetBuildResult(facets, perFieldDurationMicros, totalDurationMicros);
    }

    /**
     * Compute active filters with matchCounts from the facets map.
     */
    private List<ActiveFilter> computeActiveFilters(final List<SearchFilter> filters,
                                                    final Map<String, List<FacetValue>> facets) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        final List<ActiveFilter> activeFilters = new ArrayList<>();
        for (final SearchFilter filter : filters) {
            final long matchCount = lookupCountInFacetMap(facets, filter);
            activeFilters.add(ActiveFilter.fromFilter(filter, matchCount));
        }
        return activeFilters;
    }

    /**
     * Look up the count for a filter value in the facets map.
     * Returns -1 if the field/value is not found in facets (e.g. range or non-faceted filters).
     */
    private long lookupCountInFacetMap(final Map<String, List<FacetValue>> facets, final SearchFilter filter) {
        final String op = filter.effectiveOperator();
        // Only faceted eq/in can be looked up
        if ((!FACETED_FIELDS.contains(filter.field()) && !dynamicFacetedFields.contains(filter.field()))
                || "range".equals(op)) {
            return -1;
        }
        final List<FacetValue> dimValues = facets.get(filter.field());
        if (dimValues == null) {
            return 0;
        }

        if (("eq".equals(op) || "not".equals(op)) && filter.value() != null) {
            for (final FacetValue fv : dimValues) {
                if (fv.value().equals(filter.value())) {
                    return fv.count();
                }
            }
            return 0;
        }
        if (("in".equals(op) || "not_in".equals(op)) && filter.values() != null) {
            long total = 0;
            for (final String v : filter.values()) {
                for (final FacetValue fv : dimValues) {
                    if (fv.value().equals(v)) {
                        total += fv.count();
                    }
                }
            }
            return total;
        }
        return -1;
    }

    /**
     * Get the min/max date ranges for all date LongPoint fields.
     * Returns a map from field name to [minEpochMillis, maxEpochMillis].
     * Returns empty map if index is empty or PointValues are unavailable.
     */
    public Map<String, long[]> getDateFieldRanges() throws IOException {
        final IndexSearcher searcher = searcherManager.acquire();
        try {
            final Map<String, long[]> ranges = new LinkedHashMap<>();
            for (final String field : DATE_FIELDS) {
                try {
                    for (final var leafCtx : searcher.getIndexReader().leaves()) {
                        final PointValues pointValues = leafCtx.reader().getPointValues(field);
                        if (pointValues != null && pointValues.getDocCount() > 0) {
                            final byte[] minBytes = pointValues.getMinPackedValue();
                            final byte[] maxBytes = pointValues.getMaxPackedValue();
                            if (minBytes != null && maxBytes != null) {
                                final long min = LongPoint.decodeDimension(minBytes, 0);
                                final long max = LongPoint.decodeDimension(maxBytes, 0);
                                final long[] existing = ranges.get(field);
                                if (existing == null) {
                                    ranges.put(field, new long[]{min, max});
                                } else {
                                    existing[0] = Math.min(existing[0], min);
                                    existing[1] = Math.max(existing[1], max);
                                }
                            }
                        }
                    }
                } catch (final Exception e) {
                    logger.debug("Could not read point values for field {}", field, e);
                }
            }
            return ranges;
        } finally {
            searcherManager.release(searcher);
        }
    }

    private FacetBuildResult buildFacets(final IndexSearcher searcher, final FacetsCollector facetsCollector) {
        final Map<String, List<FacetValue>> facets = new HashMap<>();
        final Map<String, Long> perFieldDurationMicros = new LinkedHashMap<>();
        final long overallStart = System.nanoTime();
        try {
            // Create facets state from the index using the same FacetsConfig as indexing
            final SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(
                    searcher.getIndexReader(),
                    documentIndexer.getFacetsConfig()
            );

            // Create facet counts
            final Facets facetsResult = new SortedSetDocValuesFacetCounts(state, facetsCollector);

            // Retrieve top facets for each dimension (static + dynamically registered)
            final List<String> allFacetDimensions = java.util.stream.Stream
                    .concat(FACETED_FIELDS.stream(), dynamicFacetedFields.stream())
                    .toList();
            for (final String dimension : allFacetDimensions) {
                final long fieldStart = System.nanoTime();
                try {
                    final FacetResult facetResult = facetsResult.getTopChildren(100, dimension);
                    if (facetResult != null && facetResult.labelValues.length > 0) {
                        final List<FacetValue> values = new ArrayList<>();
                        for (final LabelAndValue lv : facetResult.labelValues) {
                            values.add(new FacetValue(lv.label, lv.value.intValue()));
                        }
                        facets.put(dimension, values);
                        if ("author".equals(dimension) && facetResult.labelValues.length > 500) {
                            logger.warn("author facet has {} unique values - consider whether faceting is appropriate for this corpus size",
                                    facetResult.labelValues.length);
                        }
                    }
                } catch (final IllegalArgumentException e) {
                    // Dimension doesn't exist in index - skip it
                    logger.debug("Facet dimension {} not found in index", dimension);
                }
                perFieldDurationMicros.put(dimension, (System.nanoTime() - fieldStart) / 1_000L);
            }
        } catch (final Exception e) {
            logger.warn("Error building facets", e);
        }

        final long totalDurationMicros = (System.nanoTime() - overallStart) / 1_000L;
        return new FacetBuildResult(facets, perFieldDurationMicros, totalDurationMicros);
    }

    /**
     * Analyze query structure and components.
     */
    private QueryAnalysis analyzeQueryStructure(
            final Query query,
            final String originalQueryString,
            final IndexSearcher searcher) throws IOException {

        final List<QueryComponent> components = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        // Analyze the main query
        extractQueryComponents(query, components, searcher);

        // Check for rewrites
        final Query rewritten = query.rewrite(searcher);
        final List<QueryRewrite> rewrites = new ArrayList<>();
        if (!rewritten.equals(query)) {
            rewrites.add(new QueryRewrite(
                    query.toString(),
                    rewritten.toString(),
                    "Lucene query optimization"
            ));
        }

        // Detect automatic phrase expansion
        if (originalQueryString != null && originalQueryString.contains("\"") &&
                query instanceof BooleanQuery) {
            if (detectsPhraseExpansion(query)) {
                rewrites.add(new QueryRewrite(
                        originalQueryString,
                        query.toString(),
                        "Automatic phrase proximity expansion (exact match boosted + proximity variants)"
                ));
            }
        }

        // Add warnings for common issues
        if (originalQueryString != null && originalQueryString.contains("*") &&
                !originalQueryString.startsWith("*")) {
            warnings.add("Wildcard queries can be slow. Consider using more specific terms.");
        }

        final String queryType = query.getClass().getSimpleName();
        final String displayQuery = originalQueryString != null ? originalQueryString : "*";

        return new QueryAnalysis(
                displayQuery,
                queryType,
                components,
                rewrites.isEmpty() ? null : rewrites,
                warnings
        );
    }

    /**
     * Extract query components recursively.
     */
    private void extractQueryComponents(
            final Query query,
            final List<QueryComponent> components,
            final IndexSearcher searcher) {
        extractQueryComponentsWithBoost(query, components, searcher, null, 1.0f);
    }

    /**
     * Extract query components recursively, tracking accumulated boost.
     */
    private void extractQueryComponentsWithBoost(
            final Query query,
            final List<QueryComponent> components,
            final IndexSearcher searcher,
            final String occur,
            final float accumulatedBoost) {

        if (query instanceof final BooleanQuery bq) {
            // Iterate over all occur types
            for (final BooleanClause.Occur clauseOccur : BooleanClause.Occur.values()) {
                for (final Query subQuery : bq.getClauses(clauseOccur)) {
                    extractQueryComponentsWithBoost(subQuery, components, searcher,
                            clauseOccur.name(), accumulatedBoost);
                }
            }
        } else if (query instanceof final org.apache.lucene.search.BoostQuery boostQuery) {
            // Unwrap boost and accumulate it
            final float newBoost = accumulatedBoost * boostQuery.getBoost();
            extractQueryComponentsWithBoost(boostQuery.getQuery(), components, searcher, occur, newBoost);
        } else if (query instanceof final org.apache.lucene.search.PhraseQuery phraseQuery) {
            // Leaf node - add PhraseQuery component
            final long cost = estimateQueryCost(query, searcher);
            final String type = accumulatedBoost != 1.0f
                    ? "PhraseQuery (boost=" + String.format("%.1f", accumulatedBoost) + ")"
                    : "PhraseQuery";

            components.add(new QueryComponent(
                    type,
                    extractField(query),
                    formatPhraseQueryValue(phraseQuery),
                    occur,
                    cost,
                    formatCost(cost)
            ));
        } else if (query instanceof TermQuery || query instanceof WildcardQuery) {
            // Leaf node - add Term/Wildcard component
            final long cost = estimateQueryCost(query, searcher);
            final String type = accumulatedBoost != 1.0f
                    ? query.getClass().getSimpleName() + " (boost=" + String.format("%.1f", accumulatedBoost) + ")"
                    : query.getClass().getSimpleName();

            components.add(new QueryComponent(
                    type,
                    extractField(query),
                    extractValue(query),
                    occur,
                    cost,
                    formatCost(cost)
            ));
        } else {
            // Other query types - add as-is
            final long cost = estimateQueryCost(query, searcher);
            components.add(new QueryComponent(
                    query.getClass().getSimpleName(),
                    extractField(query),
                    extractValue(query),
                    occur,
                    cost,
                    formatCost(cost)
            ));
        }
    }

    /**
     * Format PhraseQuery value to show the terms and slop.
     */
    private String formatPhraseQueryValue(final org.apache.lucene.search.PhraseQuery phraseQuery) {
        final var terms = phraseQuery.getTerms();
        if (terms.length == 0) {
            return null;
        }

        final StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < terms.length; i++) {
            if (i > 0) {
                sb.append(" ");
            }
            sb.append(terms[i].text());
        }
        sb.append("\"");

        if (phraseQuery.getSlop() > 0) {
            sb.append("~").append(phraseQuery.getSlop());
        }

        return sb.toString();
    }

    /**
     * Recursively detect if a query contains phrase expansion pattern
     * (boosted exact phrase + proximity phrase variants).
     */
    private boolean detectsPhraseExpansion(final Query query) {
        if (query instanceof final BooleanQuery bq) {
            final var shouldClauses = bq.getClauses(BooleanClause.Occur.SHOULD);

            // Look for pattern: boosted PhraseQuery (slop=0) and PhraseQuery with slop>0
            boolean hasExactPhrase = false;
            boolean hasProximityPhrase = false;

            for (final Query clause : shouldClauses) {
                final org.apache.lucene.search.PhraseQuery phraseQuery = extractPhraseQuery(clause);
                if (phraseQuery != null) {
                    if (phraseQuery.getSlop() == 0) {
                        hasExactPhrase = true;
                    } else {
                        hasProximityPhrase = true;
                    }
                }

                // Recurse into nested BooleanQuery
                if (clause instanceof BooleanQuery || clause instanceof org.apache.lucene.search.BoostQuery) {
                    if (detectsPhraseExpansion(unwrapBoostQuery(clause))) {
                        return true;
                    }
                }
            }

            return hasExactPhrase && hasProximityPhrase;
        } else if (query instanceof final org.apache.lucene.search.BoostQuery boostQuery) {
            return detectsPhraseExpansion(boostQuery.getQuery());
        }

        return false;
    }

    /**
     * Extract PhraseQuery from a clause, unwrapping BoostQuery if needed.
     */
    private org.apache.lucene.search.PhraseQuery extractPhraseQuery(final Query query) {
        if (query instanceof final org.apache.lucene.search.PhraseQuery pq) {
            return pq;
        } else if (query instanceof final org.apache.lucene.search.BoostQuery boostQuery) {
            if (boostQuery.getQuery() instanceof final org.apache.lucene.search.PhraseQuery pq) {
                return pq;
            }
        }
        return null;
    }

    /**
     * Unwrap BoostQuery to get the inner query.
     */
    private Query unwrapBoostQuery(final Query query) {
        if (query instanceof final org.apache.lucene.search.BoostQuery boostQuery) {
            return boostQuery.getQuery();
        }
        return query;
    }

    /**
     * Estimate query cost using Lucene's internal cost API.
     */
    private long estimateQueryCost(final Query query, final IndexSearcher searcher) {
        try {
            final Query rewritten = query.rewrite(searcher);
            // Use Weight to get cost
            final var weight = rewritten.createWeight(searcher, org.apache.lucene.search.ScoreMode.COMPLETE_NO_SCORES, 1.0f);
            // Get scorer for first segment to estimate cost
            final var context = searcher.getIndexReader().leaves().isEmpty() ?
                    null : searcher.getIndexReader().leaves().getFirst();
            if (context != null) {
                final var scorer = weight.scorer(context);
                if (scorer != null) {
                    return scorer.iterator().cost();
                }
            }
        } catch (final Exception e) {
            // Fallback if cost estimation fails
        }
        return searcher.getIndexReader().numDocs();
    }

    /**
     * Extract field from query.
     */
    private String extractField(final Query query) {
        if (query instanceof final TermQuery tq) {
            return tq.getTerm().field();
        } else if (query instanceof final WildcardQuery wq) {
            return wq.getTerm().field();
        }
        return null;
    }

    /**
     * Extract value from query.
     */
    private String extractValue(final Query query) {
        if (query instanceof final TermQuery tq) {
            return tq.getTerm().text();
        } else if (query instanceof final WildcardQuery wq) {
            return wq.getTerm().text();
        }
        return null;
    }

    /**
     * Format cost as human-readable string.
     */
    private String formatCost(final long cost) {
        if (cost < 10) {
            return "~" + cost + " documents (very fast)";
        } else if (cost < 100) {
            return "~" + cost + " documents (fast)";
        } else if (cost < 1000) {
            return "~" + cost + " documents (moderate)";
        } else {
            return "~" + cost + " documents to examine";
        }
    }

    /**
     * Compute search metrics.
     */
    private SearchMetrics computeSearchMetrics(
            final Query mainQuery,
            final List<SearchFilter> filters,
            final IndexSearcher searcher) throws IOException {

        final long totalDocs = searcher.getIndexReader().numDocs();

        // Count documents matching the base query (without filters)
        final TopDocs baseResults = searcher.search(mainQuery, 1);
        final long docsMatchingQuery = baseResults.totalHits.value();

        // Count documents after applying filters
        long docsAfterFilters = docsMatchingQuery;
        if (!filters.isEmpty()) {
            final BooleanQuery.Builder filteredBuilder = new BooleanQuery.Builder();
            filteredBuilder.add(mainQuery, BooleanClause.Occur.MUST);
            for (final SearchFilter f : filters) {
                addFilterClause(filteredBuilder, f);
            }
            final TopDocs filteredResults = searcher.search(filteredBuilder.build(), 1);
            docsAfterFilters = filteredResults.totalHits.value();
        }

        final double filterReduction = docsMatchingQuery > 0 ?
                ((docsMatchingQuery - docsAfterFilters) * 100.0 / docsMatchingQuery) : 0;

        // Collect term statistics
        final Map<String, TermStatistics> termStats = collectTermStatistics(mainQuery, searcher, totalDocs);

        return new SearchMetrics(
                totalDocs,
                docsMatchingQuery,
                docsAfterFilters,
                filterReduction,
                termStats
        );
    }

    /**
     * Collect statistics for terms in the query.
     */
    private Map<String, TermStatistics> collectTermStatistics(
            final Query query,
            final IndexSearcher searcher,
            final long totalDocs) throws IOException {

        final Map<String, TermStatistics> stats = new HashMap<>();
        final Set<String> termTexts = extractQueryTerms(query);

        for (final String termText : termTexts) {
            // Try to get term statistics for the content field (most common)
            final Term term = new Term("content", termText);
            final long docFreq = searcher.getIndexReader().docFreq(term);
            final long totalTermFreq = searcher.getIndexReader().totalTermFreq(term);

            if (docFreq > 0) {
                final double idf = Math.log((totalDocs + 1.0) / (docFreq + 1.0));
                final String rarity = categorizeTermRarity(docFreq, totalDocs);

                stats.put(termText, new TermStatistics(
                        termText,
                        docFreq,
                        totalTermFreq,
                        idf,
                        rarity
                ));
            }
        }

        return stats;
    }

    /**
     * Categorize term rarity based on document frequency.
     */
    private String categorizeTermRarity(final long docFreq, final long totalDocs) {
        final double percentOfDocs = (docFreq * 100.0) / totalDocs;
        if (percentOfDocs > 50) return "very common";
        if (percentOfDocs > 20) return "common";
        if (percentOfDocs > 5) return "uncommon";
        return "rare";
    }

    /**
     * Analyze filter impact by running queries with and without each filter.
     */
    private FilterImpactAnalysis analyzeFilterImpact(
            final Query mainQuery,
            final List<SearchFilter> filters,
            final IndexSearcher searcher) throws IOException {

        final long startTime = System.nanoTime();
        final long baselineHits = searcher.count(mainQuery);
        final List<FilterImpact> impacts = new ArrayList<>();

        // Test each filter incrementally — apply filters cumulatively and measure hit reduction
        long currentHits = baselineHits;
        final BooleanQuery.Builder cumulativeBuilder = new BooleanQuery.Builder();
        cumulativeBuilder.add(mainQuery, BooleanClause.Occur.MUST);

        for (final SearchFilter filter : filters) {
            final long filterStartTime = System.nanoTime();

            // Add this filter to the cumulative query
            addFilterClause(cumulativeBuilder, filter);

            final long hitsAfter = searcher.count(cumulativeBuilder.build());
            final long removed = currentHits - hitsAfter;
            final double reduction = currentHits > 0 ? (removed * 100.0 / currentHits) : 0;
            final String selectivity = categorizeSelectivity(reduction);
            final double filterTime = (System.nanoTime() - filterStartTime) / 1_000_000.0;

            impacts.add(new FilterImpact(
                    filter,
                    currentHits,
                    hitsAfter,
                    removed,
                    reduction,
                    selectivity,
                    filterTime
            ));

            currentHits = hitsAfter;
        }

        final double totalTime = (System.nanoTime() - startTime) / 1_000_000.0;

        return new FilterImpactAnalysis(baselineHits, currentHits, impacts, totalTime);
    }

    /**
     * Add a single filter as a clause to the given BooleanQuery builder.
     */
    private void addFilterClause(final BooleanQuery.Builder builder, final SearchFilter f) {
        final String op = f.effectiveOperator();
        final String field = f.field();

        switch (op) {
            case "not" -> {
                if (f.value() != null) {
                    if (FACETED_FIELDS.contains(field) || STRING_FIELDS.contains(field)
                            || dynamicFacetedFields.contains(field)) {
                        builder.add(new TermQuery(new Term(field, f.value())), BooleanClause.Occur.MUST_NOT);
                    } else if (isLongPointField(field)) {
                        builder.add(LongPoint.newExactQuery(field, parseLongFilterValue(field, f.value())), BooleanClause.Occur.MUST_NOT);
                    } else if (isIntPointField(field)) {
                        builder.add(IntPoint.newExactQuery(field, parseIntFilterValue(field, f.value())), BooleanClause.Occur.MUST_NOT);
                    }
                }
            }
            case "not_in" -> {
                if (f.values() != null) {
                    for (final String v : f.values()) {
                        if (FACETED_FIELDS.contains(field) || STRING_FIELDS.contains(field)
                                || dynamicFacetedFields.contains(field)) {
                            builder.add(new TermQuery(new Term(field, v)), BooleanClause.Occur.MUST_NOT);
                        } else if (isLongPointField(field)) {
                            builder.add(LongPoint.newExactQuery(field, parseLongFilterValue(field, v)), BooleanClause.Occur.MUST_NOT);
                        } else if (isIntPointField(field)) {
                            builder.add(IntPoint.newExactQuery(field, parseIntFilterValue(field, v)), BooleanClause.Occur.MUST_NOT);
                        }
                    }
                }
            }
            case "range" -> {
                if (isIntPointField(field)) {
                    final int fromVal = f.from() != null ? parseIntFilterValue(field, f.from()) : Integer.MIN_VALUE;
                    final int toVal = f.to() != null ? parseIntFilterValue(field, f.to()) : Integer.MAX_VALUE;
                    builder.add(IntPoint.newRangeQuery(field, fromVal, toVal), BooleanClause.Occur.FILTER);
                } else {
                    final long fromVal = f.from() != null ? parseLongFilterValue(field, f.from()) : Long.MIN_VALUE;
                    final long toVal = f.to() != null ? parseLongFilterValue(field, f.to()) : Long.MAX_VALUE;
                    builder.add(LongPoint.newRangeQuery(field, fromVal, toVal), BooleanClause.Occur.FILTER);
                }
            }
            case "eq" -> {
                if (f.value() != null) {
                    if (FACETED_FIELDS.contains(field) || STRING_FIELDS.contains(field)
                            || dynamicFacetedFields.contains(field)) {
                        builder.add(new TermQuery(new Term(field, f.value())), BooleanClause.Occur.FILTER);
                    } else if (isLongPointField(field)) {
                        builder.add(LongPoint.newExactQuery(field, parseLongFilterValue(field, f.value())), BooleanClause.Occur.FILTER);
                    } else if (isIntPointField(field)) {
                        builder.add(IntPoint.newExactQuery(field, parseIntFilterValue(field, f.value())), BooleanClause.Occur.FILTER);
                    }
                }
            }
            case "in" -> {
                if (f.values() != null) {
                    if (FACETED_FIELDS.contains(field) || STRING_FIELDS.contains(field)
                            || dynamicFacetedFields.contains(field)) {
                        final BooleanQuery.Builder orBuilder = new BooleanQuery.Builder();
                        for (final String v : f.values()) {
                            orBuilder.add(new TermQuery(new Term(field, v)), BooleanClause.Occur.SHOULD);
                        }
                        builder.add(orBuilder.build(), BooleanClause.Occur.FILTER);
                    } else if (isLongPointField(field)) {
                        final long[] vals = new long[f.values().size()];
                        for (int i = 0; i < f.values().size(); i++) {
                            vals[i] = parseLongFilterValue(field, f.values().get(i));
                        }
                        builder.add(LongPoint.newSetQuery(field, vals), BooleanClause.Occur.FILTER);
                    } else if (isIntPointField(field)) {
                        final int[] vals = new int[f.values().size()];
                        for (int i = 0; i < f.values().size(); i++) {
                            vals[i] = parseIntFilterValue(field, f.values().get(i));
                        }
                        builder.add(IntPoint.newSetQuery(field, vals), BooleanClause.Occur.FILTER);
                    }
                }
            }
            default -> { /* unknown op — skip */ }
        }
    }

    /**
     * Categorize filter selectivity.
     */
    private String categorizeSelectivity(final double reductionPercent) {
        if (reductionPercent < 10) return "low";
        if (reductionPercent < 40) return "medium";
        if (reductionPercent < 80) return "high";
        return "very high";
    }

    /**
     * Analyze document scoring using Lucene's Explanation API.
     */
    private List<DocumentScoringExplanation> analyzeDocumentScoring(
            final Query mainQuery,
            final List<SearchFilter> filters,
            final int maxDocs,
            final IndexSearcher searcher) throws IOException {

        // Build filtered query to match what search() actually returns
        final Query effectiveQuery;
        if (!filters.isEmpty()) {
            final BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(mainQuery, BooleanClause.Occur.MUST);
            for (final SearchFilter f : filters) {
                addFilterClause(builder, f);
            }
            effectiveQuery = builder.build();
        } else {
            effectiveQuery = mainQuery;
        }

        // Run search to get top documents
        final TopDocs topDocs = searcher.search(effectiveQuery, maxDocs);
        final List<DocumentScoringExplanation> explanations = new ArrayList<>();

        for (int i = 0; i < topDocs.scoreDocs.length; i++) {
            final ScoreDoc scoreDoc = topDocs.scoreDocs[i];
            final org.apache.lucene.document.Document doc = searcher.storedFields().document(scoreDoc.doc);
            final String filePath = doc.get("file_path");

            // Get an explanation from Lucene
            final org.apache.lucene.search.Explanation explanation = searcher.explain(mainQuery, scoreDoc.doc);

            // Parse explanation into structured format
            final ScoringBreakdown breakdown = parseExplanation(explanation);

            // Extract matched terms
            final List<String> matchedTerms = new ArrayList<>(extractQueryTerms(mainQuery));

            explanations.add(new DocumentScoringExplanation(
                    filePath,
                    i + 1,
                    scoreDoc.score,
                    breakdown,
                    matchedTerms
            ));
        }

        return explanations;
    }

    /**
     * Parse Lucene's Explanation into a structured, LLM-friendly format.
     */
    private ScoringBreakdown parseExplanation(final org.apache.lucene.search.Explanation explanation) {
        final List<ScoreComponent> components = new ArrayList<>();
        final double totalScore = explanation.getValue().doubleValue();

        // Parse explanation details recursively
        parseExplanationDetails(explanation, components, totalScore);

        // Generate summary
        final String summary = generateScoringSummary(components, totalScore);

        return new ScoringBreakdown(totalScore, components, summary);
    }

    /**
     * Recursively parse explanation details.
     */
    private void parseExplanationDetails(
            final org.apache.lucene.search.Explanation explanation,
            final List<ScoreComponent> components,
            final double totalScore) {

        final double value = explanation.getValue().doubleValue();
        final double percent = totalScore > 0 ? (value * 100.0 / totalScore) : 0;

        // Extract term and field from description
        final String description = explanation.getDescription();
        String term = null;
        String field = null;

        // Simple parsing - could be enhanced
        if (description.contains("weight(") && description.contains(":")) {
            final int fieldStart = description.indexOf("weight(") + 7;
            final int colon = description.indexOf(":", fieldStart);
            if (colon > fieldStart) {
                field = description.substring(fieldStart, colon);
                final int termEnd = description.indexOf(" in", colon);
                if (termEnd > colon) {
                    term = description.substring(colon + 1, termEnd);
                }
            }
        }

        components.add(new ScoreComponent(
                term,
                field,
                value,
                percent,
                new ScoreDetails(0, 0, 0, 0, 0, description)
        ));

        // Recurse into sub-explanations
        for (final org.apache.lucene.search.Explanation sub : explanation.getDetails()) {
            if (sub.getValue().doubleValue() > 0.01) {
                parseExplanationDetails(sub, components, totalScore);
            }
        }
    }

    /**
     * Generate human-readable summary of scoring.
     */
    private String generateScoringSummary(final List<ScoreComponent> components, final double totalScore) {
        if (components.isEmpty()) {
            return "No scoring details available";
        }

        // Find dominant component
        final ScoreComponent dominant = components.stream()
                .max(Comparator.comparingDouble(ScoreComponent::contribution))
                .orElse(components.getFirst());

        if (dominant.term() != null) {
            return String.format("Score dominated by term '%s' (%.1f%%)",
                    dominant.term(), dominant.contributionPercent());
        } else {
            return String.format("Total score: %.2f", totalScore);
        }
    }

    /**
     * Analyze faceting cost by comparing search with and without facet collection,
     * then measuring per-dimension facet computation time.
     */
    private FacetCostAnalysis analyzeFacetCost(
            final Query mainQuery,
            final List<SearchFilter> filters,
            final IndexSearcher searcher) throws IOException {

        // Build filtered query to match what search() actually executes
        final Query effectiveQuery;
        if (!filters.isEmpty()) {
            final BooleanQuery.Builder builder = new BooleanQuery.Builder();
            builder.add(mainQuery, BooleanClause.Occur.MUST);
            for (final SearchFilter f : filters) {
                addFilterClause(builder, f);
            }
            effectiveQuery = builder.build();
        } else {
            effectiveQuery = mainQuery;
        }

        // 1. Baseline: search without faceting
        final long baseStart = System.nanoTime();
        searcher.search(effectiveQuery, 10);
        final double baseTimeMs = (System.nanoTime() - baseStart) / 1_000_000.0;

        // 2. Search with facet collection
        final long facetStart = System.nanoTime();
        final FacetsCollectorManager facetsCollectorManager = new FacetsCollectorManager();
        final FacetsCollectorManager.FacetsResult result = FacetsCollectorManager.search(
                searcher, effectiveQuery, 10, facetsCollectorManager);
        final double facetSearchTimeMs = (System.nanoTime() - facetStart) / 1_000_000.0;

        final double overheadMs = Math.max(0, facetSearchTimeMs - baseTimeMs);

        // 3. Per-dimension cost: measure time to compute facet counts for each dimension
        final Map<String, FacetDimensionCost> dimensions = new HashMap<>();
        try {
            final SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(
                    searcher.getIndexReader(), documentIndexer.getFacetsConfig());
            final Facets facets = new SortedSetDocValuesFacetCounts(state, result.facetsCollector());

            final List<String> allProfileDimensions = java.util.stream.Stream
                    .concat(FACETED_FIELDS.stream(), dynamicFacetedFields.stream())
                    .toList();
            for (final String dimension : allProfileDimensions) {
                final long dimStart = System.nanoTime();
                try {
                    final FacetResult fr = facets.getTopChildren(100, dimension);
                    final double dimTimeMs = (System.nanoTime() - dimStart) / 1_000_000.0;
                    if (fr != null) {
                        dimensions.put(dimension, new FacetDimensionCost(
                                dimension, fr.childCount, (int) fr.value, dimTimeMs));
                    } else {
                        dimensions.put(dimension, new FacetDimensionCost(dimension, 0, 0, dimTimeMs));
                    }
                } catch (final IllegalArgumentException e) {
                    // Dimension not present in index
                    final double dimTimeMs = (System.nanoTime() - dimStart) / 1_000_000.0;
                    dimensions.put(dimension, new FacetDimensionCost(dimension, 0, 0, dimTimeMs));
                }
            }
        } catch (final IllegalStateException e) {
            // No facet data in index at all
            java.util.stream.Stream.concat(FACETED_FIELDS.stream(), dynamicFacetedFields.stream())
                    .forEach(dimension -> dimensions.put(dimension, new FacetDimensionCost(dimension, 0, 0, 0.0)));
        }

        final double overheadPercent = baseTimeMs > 0 ? (overheadMs * 100.0 / baseTimeMs) : 0;

        return new FacetCostAnalysis(overheadMs, overheadPercent, dimensions);
    }

    /**
     * Generate optimization recommendations based on analysis.
     */
    private List<String> generateRecommendations(
            final QueryAnalysis queryAnalysis,
            final SearchMetrics searchMetrics,
            final FilterImpactAnalysis filterImpact,
            final List<DocumentScoringExplanation> docExplanations) {

        final List<String> recommendations = new ArrayList<>();

        // Check for very common terms
        if (searchMetrics.termStatistics() != null) {
            for (final TermStatistics ts : searchMetrics.termStatistics().values()) {
                if ("very common".equals(ts.rarity()) || "common".equals(ts.rarity())) {
                    recommendations.add(String.format(
                            "Term '%s' appears in %.0f%% of documents. Consider adding more specific terms to narrow results.",
                            ts.term(), (ts.documentFrequency() * 100.0 / searchMetrics.totalIndexedDocuments())));
                }
            }
        }

        // Check for wildcard warnings
        if (!queryAnalysis.warnings().isEmpty()) {
            recommendations.addAll(queryAnalysis.warnings());
        }

        // Check filter efficiency
        if (filterImpact != null) {
            for (final FilterImpact impact : filterImpact.filterImpacts()) {
                if ("low".equals(impact.selectivity())) {
                    recommendations.add(String.format(
                            "Filter on '%s' has low selectivity (%.1f%% reduction). Consider if this filter is necessary.",
                            impact.filter().field(), impact.reductionPercent()));
                }
            }
        }

        // Check result count
        if (searchMetrics.documentsMatchingQuery() > 10000) {
            recommendations.add("Query matches many documents (" + searchMetrics.documentsMatchingQuery() +
                    "). Consider adding filters or more specific terms for better performance.");
        }

        // Check document scoring patterns
        if (docExplanations != null && docExplanations.size() >= 2) {
            final double topScore = docExplanations.getFirst().score();
            final double lastScore = docExplanations.getLast().score();
            if (topScore > 0 && lastScore > 0) {
                final double scoreSpread = (topScore - lastScore) / topScore;
                if (scoreSpread < 0.1) {
                    recommendations.add(String.format(
                            "Top %d results have very similar scores (%.2f to %.2f). " +
                            "Consider adding more specific terms or filters to better differentiate results.",
                            docExplanations.size(), topScore, lastScore));
                }
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Query looks well-optimized. No specific recommendations.");
        }

        return recommendations;
    }

    public record FacetValue(String value, int count) {
    }

    private record FacetBuildResult(
            Map<String, List<FacetValue>> facets,
            Map<String, Long> perFieldDurationMicros,
            long totalDurationMicros
    ) {}

    public record SearchResult(
            List<SearchDocument> documents,
            long totalHits,
            int page,
            int pageSize,
            Map<String, List<FacetValue>> facets,
            List<ActiveFilter> activeFilters,
            Map<String, Long> facetFieldDurationMicros,
            long facetTotalDurationMicros
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
     * Extended result of a semantic search that also carries raw KNN debug candidates.
     * Only produced by the profiling/debug code path; never returned to MCP clients directly.
     */
    public record SemanticSearchWithDebugResult(
            SemanticSearchResult searchResult,
            List<VectorSearchDebug.VectorCandidateInfo> knnCandidates
    ) {}

    /**
     * Perform a pure KNN semantic search using the ONNX embedding model.
     *
     * <p>The query string is embedded using the query prefix and used to find the
     * nearest-neighbour child chunk documents in the index.  Results are grouped by
     * parent document (file_path), keeping the best-scoring chunk per parent.  Parent
     * documents are resolved via a secondary lookup and paged according to {@code page}
     * and {@code pageSize}.</p>
     *
     * @param queryString         the natural-language query to embed
     * @param filters             optional filters to apply as a post-filter on parent documents
     * @param page                zero-based page number
     * @param pageSize            number of results per page
     * @param similarityThreshold cosine similarity threshold (0.0–1.0); candidates below this are dropped
     * @return a flat {@link SemanticSearchResult} with paged passages, total hit count,
     *         embedding latency, and the effective cosine cutoff applied
     * @throws IOException           if reading from the index fails
     * @throws IllegalStateException if the ONNX service is not configured
     */
    public SemanticSearchResult semanticSearch(
            final String queryString,
            final List<SearchFilter> filters,
            final int page,
            final int pageSize,
            final float similarityThreshold) throws IOException {
        return doSemanticSearch(queryString, filters, page, pageSize, similarityThreshold, null);
    }

    /**
     * Perform a semantic search and also collect raw KNN debug candidates for profiling.
     *
     * <p>This is the debug/profiling variant of {@link #semanticSearch}. It behaves
     * identically but additionally populates the {@code knnCandidates} list in the
     * returned {@link SemanticSearchWithDebugResult} with all raw KNN hits before
     * threshold filtering.</p>
     *
     * @param queryString         the natural-language query to embed
     * @param filters             optional filters to apply as a post-filter on parent documents
     * @param page                zero-based page number
     * @param pageSize            number of results per page
     * @param similarityThreshold cosine similarity threshold (0.0–1.0); candidates below this are dropped
     * @return a {@link SemanticSearchWithDebugResult} containing the search result and raw KNN candidates
     * @throws IOException           if reading from the index fails
     * @throws IllegalStateException if the ONNX service is not configured
     */
    public SemanticSearchWithDebugResult semanticSearchWithDebug(
            final String queryString,
            final List<SearchFilter> filters,
            final int page,
            final int pageSize,
            final float similarityThreshold) throws IOException {
        final List<VectorSearchDebug.VectorCandidateInfo> knnCandidates = new ArrayList<>();
        final SemanticSearchResult result = doSemanticSearch(
                queryString, filters, page, pageSize, similarityThreshold, knnCandidates);
        return new SemanticSearchWithDebugResult(result, knnCandidates);
    }

    /**
     * Private implementation that drives both {@link #semanticSearch} and
     * {@link #semanticSearchWithDebug}.
     *
     * @param debugCandidatesOut when non-null, all raw KNN candidates are added to this list
     */
    private SemanticSearchResult doSemanticSearch(
            final String queryString,
            final List<SearchFilter> filters,
            final int page,
            final int pageSize,
            final float similarityThreshold,
            final @Nullable List<VectorSearchDebug.VectorCandidateInfo> debugCandidatesOut) throws IOException {

        if (onnxService == null) {
            throw new IllegalStateException("Semantic search requires VECTOR_MODEL to be configured");
        }

        final long embeddingStart = System.nanoTime();
        final float[] queryVector;
        try {
            queryVector = onnxService.embed(queryString, ONNXService.QUERY_PREFIX);
        } catch (final Exception e) {
            throw new IOException("Failed to embed query string: " + e.getMessage(), e);
        }
        final long embeddingDurationMs = (System.nanoTime() - embeddingStart) / 1_000_000;
        logger.info("Embedding computation took {} ms", embeddingDurationMs);

        // Convert cosine similarity threshold to Lucene DOT_PRODUCT score threshold.
        // Lucene stores DOT_PRODUCT scores in [0,1] as (1 + cosine) / 2.
        final float luceneScoreThreshold = (1.0f + similarityThreshold) / 2.0f;

        final IndexSearcher searcher = searcherManager.acquire();
        try {
            // Retrieve top-50 nearest-neighbour child chunk documents.
            // NOTE: User filters must NOT be applied as KNN pre-filter here because
            // filter fields only exist on parent documents, not on child chunk documents.
            final int knnCandidates = 50;
            final KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery("embedding", queryVector, knnCandidates);
            final TopDocs vectorTopDocs = searcher.search(knnQuery, knnCandidates);
            logger.info("Found {} nearest-neighbour candidates, checking for threshold {}", vectorTopDocs.scoreDocs.length, luceneScoreThreshold);
            final int rawCandidateCount = vectorTopDocs.scoreDocs.length;

            // Local record to hold per-chunk match data
            record ChunkMatch(int chunkIndex, String chunkText, float cosineScore, float position) {}

            // Group ALL chunks that pass the threshold by parent file_path
            final Map<String, List<ChunkMatch>> chunksByPath = new LinkedHashMap<>();

            for (int candidateIdx = 0; candidateIdx < vectorTopDocs.scoreDocs.length; candidateIdx++) {
                final ScoreDoc scoreDoc = vectorTopDocs.scoreDocs[candidateIdx];
                final float cosineScore = 2.0f * scoreDoc.score - 1.0f;
                final Document childDoc = searcher.storedFields().document(scoreDoc.doc);
                final String filePath = childDoc.get("file_path");
                final String chunkText = childDoc.get("chunk_text");
                final int chunkIndex = childDoc.getField("chunk_index") != null
                        ? childDoc.getField("chunk_index").numericValue().intValue()
                        : candidateIdx;
                final float chunkPosition = childDoc.getField("chunk_position") != null
                        ? childDoc.getField("chunk_position").numericValue().floatValue()
                        : 0.0f;
                final boolean passedThreshold = scoreDoc.score >= luceneScoreThreshold;
                logger.debug("Candidate {}: score={}, cosineScore={}, position={}, passedThreshold={}", candidateIdx, scoreDoc.score, cosineScore, scoreDoc.doc, passedThreshold);

                // Only allocate debug info when the caller wants it
                if (debugCandidatesOut != null) {
                    debugCandidatesOut.add(new VectorSearchDebug.VectorCandidateInfo(
                            filePath != null ? filePath : "",
                            chunkIndex,
                            chunkText,
                            scoreDoc.score,
                            cosineScore,
                            passedThreshold,
                            candidateIdx + 1
                    ));
                }

                if (!passedThreshold || filePath == null) {
                    continue;
                }

                chunksByPath.computeIfAbsent(filePath, k -> new ArrayList<>())
                        .add(new ChunkMatch(chunkIndex, chunkText != null ? chunkText : "", cosineScore, chunkPosition));
            }

            // Sort parents by their best chunk cosine score (descending)
            final List<String> sortedPaths = chunksByPath.entrySet().stream()
                    .sorted(Comparator.comparingDouble(
                            e -> -e.getValue().stream().mapToDouble(ChunkMatch::cosineScore).max().orElse(0)))
                    .map(Map.Entry::getKey)
                    .toList();

            // Apply pagination over parents
            final int startIdx = page * pageSize;
            final int endIdx = Math.min(startIdx + pageSize, sortedPaths.size());

            final List<SearchDocument> documents = new ArrayList<>();
            for (int i = startIdx; i < endIdx; i++) {
                final String filePath = sortedPaths.get(i);

                // Look up the parent document, applying user filters as a post-filter.
                final BooleanQuery.Builder parentQueryBuilder = new BooleanQuery.Builder()
                        .add(new TermQuery(new Term("file_path", filePath)), BooleanClause.Occur.MUST)
                        .add(new TermQuery(new Term(DocumentIndexer.DOC_TYPE_FIELD, DocumentIndexer.DOC_TYPE_PARENT)),
                                BooleanClause.Occur.MUST);
                if (filters != null && !filters.isEmpty()) {
                    for (final SearchFilter f : filters) {
                        addFilterClause(parentQueryBuilder, f);
                    }
                }
                final TopDocs parentDocs = searcher.search(parentQueryBuilder.build(), 1);
                if (parentDocs.scoreDocs.length == 0) {
                    continue;
                }

                final Document parentDoc = searcher.storedFields().document(parentDocs.scoreDocs[0].doc);

                // Sort this parent's matching chunks by cosine score descending
                final List<ChunkMatch> chunks = new ArrayList<>(chunksByPath.get(filePath));
                chunks.sort(Comparator.comparingDouble(ChunkMatch::cosineScore).reversed());

                // Build one Passage per matching chunk
                final List<Passage> passages = chunks.stream()
                        .map(c -> new Passage(
                                TextCleaner.clean(c.chunkText()),
                                Math.round((double) c.cosineScore() * 100.0) / 100.0,
                                List.of(),
                                0.0,
                                c.position(),
                                "semantic",
                                c.chunkIndex()
                        ))
                        .toList();

                final SearchDocument searchDoc = SearchDocument.builder()
                        .score(passages.getFirst().score())
                        .filePath(parentDoc.get("file_path"))
                        .fileName(parentDoc.get("file_name"))
                        .title(parentDoc.get("title"))
                        .author(parentDoc.get("author"))
                        .creator(parentDoc.get("creator"))
                        .subject(parentDoc.get("subject"))
                        .language(parentDoc.get("language"))
                        .fileExtension(parentDoc.get("file_extension"))
                        .fileType(parentDoc.get("file_type"))
                        .fileSize(parentDoc.get("file_size"))
                        .createdDate(parentDoc.get("created_date"))
                        .modifiedDate(parentDoc.get("modified_date"))
                        .indexedDate(parentDoc.get("indexed_date"))
                        .passages(passages)
                        .build();
                documents.add(searchDoc);
            }

            return new SemanticSearchResult(
                    documents,
                    chunksByPath.size(),
                    page,
                    pageSize,
                    embeddingDurationMs,
                    similarityThreshold,
                    rawCandidateCount
            );
        } finally {
            searcherManager.release(searcher);
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
