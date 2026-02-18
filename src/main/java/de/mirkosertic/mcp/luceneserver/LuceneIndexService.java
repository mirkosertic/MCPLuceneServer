package de.mirkosertic.mcp.luceneserver;

import de.mirkosertic.mcp.luceneserver.config.ApplicationConfig;
import de.mirkosertic.mcp.luceneserver.config.BuildInfo;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentIndexer;
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
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchFilter;
import de.mirkosertic.mcp.luceneserver.mcp.dto.SearchMetrics;
import de.mirkosertic.mcp.luceneserver.mcp.dto.TermStatistics;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.document.Document;
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
import org.apache.lucene.index.PointValues;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
    static final Set<String> LONG_POINT_FIELDS = Set.of(
            "file_size", "created_date", "modified_date", "indexed_date");

    /** Subset of LONG_POINT_FIELDS that represent dates (epoch millis). */
    static final Set<String> DATE_FIELDS = Set.of(
            "created_date", "modified_date", "indexed_date");

    /** Fields that are analyzed TextField — cannot be filtered with exact term match. */
    static final Set<String> ANALYZED_FIELDS = Set.of(
            "content", "content_reversed", "content_lemma_de", "content_lemma_en",
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
            "content", "content_reversed", "content_lemma_de", "content_lemma_en");

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

    public LuceneIndexService(final ApplicationConfig config,
                              final DocumentIndexer documentIndexer) {
        this.config = config;
        final Analyzer defaultAnalyzer = new UnicodeNormalizingAnalyzer();
        this.deLemmatizer = new OpenNLPLemmatizingAnalyzer("de", true);
        this.enLemmatizer = new OpenNLPLemmatizingAnalyzer("en", true);
        // Index analyzer: sentence-aware OpenNLP pipeline for accurate POS tagging on long texts
        this.indexAnalyzer = new PerFieldAnalyzerWrapper(defaultAnalyzer, Map.of(
                "content_reversed", new ReverseUnicodeNormalizingAnalyzer(),
                "content_lemma_de", deLemmatizer,
                "content_lemma_en", enLemmatizer
        ));
        // Query analyzer: simple mode (no sentence detection) for better handling of short queries
        this.queryAnalyzer = new PerFieldAnalyzerWrapper(defaultAnalyzer, Map.of(
                "content_reversed", new ReverseUnicodeNormalizingAnalyzer(),
                "content_lemma_de", deLemmatizer.withSentenceDetection(false),
                "content_lemma_en", enLemmatizer.withSentenceDetection(false)
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
        final IndexWriterConfig config = new IndexWriterConfig(indexAnalyzer);
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
                final QueryParser parser = new QueryParser("content", queryAnalyzer);
                parser.setAllowLeadingWildcard(true);
                final Query parsed = parser.parse(queryString);
                final Query contentQuery = rewriteLeadingWildcards(parsed);
                highlightQuery = contentQuery;

                // Build stemmed query: content (boosted) + stemmed fields
                final String languageHint = extractLanguageHint(filters);
                mainQuery = buildStemmedQuery(contentQuery, queryString, languageHint);
            }

            // 2. Classify filters
            final List<SearchFilter> positiveFacetFilters = new ArrayList<>();
            final List<SearchFilter> negativeFilters = new ArrayList<>();
            final List<SearchFilter> rangeFilters = new ArrayList<>();
            final List<SearchFilter> stringTermFilters = new ArrayList<>();
            final List<SearchFilter> longPointEqFilters = new ArrayList<>();

            for (final SearchFilter f : filters) {
                final String op = f.effectiveOperator();
                final String field = f.field();

                switch (op) {
                    case "not", "not_in" -> negativeFilters.add(f);
                    case "range" -> rangeFilters.add(f);
                    case "eq", "in" -> {
                        if (FACETED_FIELDS.contains(field)) {
                            positiveFacetFilters.add(f);
                        } else if (LONG_POINT_FIELDS.contains(field)) {
                            longPointEqFilters.add(f);
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
                final long fromVal = rf.from() != null ? parseLongFilterValue(rf.field(), rf.from()) : Long.MIN_VALUE;
                final long toVal = rf.to() != null ? parseLongFilterValue(rf.field(), rf.to()) : Long.MAX_VALUE;
                baseBuilder.add(LongPoint.newRangeQuery(rf.field(), fromVal, toVal), BooleanClause.Occur.FILTER);
            }

            // Negative filters
            for (final SearchFilter nf : negativeFilters) {
                final String op = nf.effectiveOperator();
                if ("not".equals(op) && nf.value() != null) {
                    if (FACETED_FIELDS.contains(nf.field()) || STRING_FIELDS.contains(nf.field())) {
                        baseBuilder.add(new TermQuery(new Term(nf.field(), nf.value())), BooleanClause.Occur.MUST_NOT);
                    } else if (LONG_POINT_FIELDS.contains(nf.field())) {
                        final long val = parseLongFilterValue(nf.field(), nf.value());
                        baseBuilder.add(LongPoint.newExactQuery(nf.field(), val), BooleanClause.Occur.MUST_NOT);
                    }
                } else if ("not_in".equals(op) && nf.values() != null) {
                    for (final String v : nf.values()) {
                        if (FACETED_FIELDS.contains(nf.field()) || STRING_FIELDS.contains(nf.field())) {
                            baseBuilder.add(new TermQuery(new Term(nf.field(), v)), BooleanClause.Occur.MUST_NOT);
                        } else if (LONG_POINT_FIELDS.contains(nf.field())) {
                            final long val = parseLongFilterValue(nf.field(), v);
                            baseBuilder.add(LongPoint.newExactQuery(nf.field(), val), BooleanClause.Occur.MUST_NOT);
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

            // 8. Collect results for the requested page
            final List<SearchDocument> results = new ArrayList<>();
            final ScoreDoc[] scoreDocs = topDocs.scoreDocs;

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

            // 8. Compute active filters
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
                final QueryParser parser = new QueryParser("content", queryAnalyzer);
                parser.setAllowLeadingWildcard(true);
                final Query parsed = parser.parse(request.effectiveQuery());
                final Query contentQuery = rewriteLeadingWildcards(parsed);
                final String languageHint = extractLanguageHint(request.effectiveFilters());
                mainQuery = buildStemmedQuery(contentQuery, request.effectiveQuery(), languageHint);
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

            // Remove broken/invalid characters from passage text
            final String cleanedPassageText = TextCleaner.clean(passageText);
            if (cleanedPassageText == null || cleanedPassageText.isBlank()) {
                continue;
            }

            // --- score: normalise to 0-1 using the best passage's score as the maximum ---
            final double normalizedScore = maxScore > 0
                    ? Math.round((double) fp.score() / maxScore * 100.0) / 100.0
                    : 0.0;

            // --- matchedTerms: extract text between <em> tags, with fallback to query-term scanning ---
            final List<String> matchedTerms = extractMatchedTerms(cleanedPassageText, queryTerms);

            // --- termCoverage: unique matched terms / total query terms ---
            final double termCoverage = calculateTermCoverage(matchedTerms, queryTerms);

            // --- position: derived directly from the passage's start offset ---
            // (content is guaranteed non-empty by the early return at the top of this method)
            final double position = Math.round((double) fp.startOffset() / content.length() * 100.0) / 100.0;

            passages.add(new Passage(cleanedPassageText, normalizedScore, matchedTerms, termCoverage, position));
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
        return new Passage(cleanedFallbackText, 0.0, List.of(), 0.0, 0.0);
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
        final QueryParser parser = new QueryParser(targetField, queryAnalyzer);
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
    static String validateFilter(final SearchFilter filter) {
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

        // Range is only valid on LONG_POINT_FIELDS
        if ("range".equals(op) && !LONG_POINT_FIELDS.contains(filter.field())) {
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
    static long parseIso8601ToEpochMillis(final String value) {
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
    static long parseLongFilterValue(final String field, final String value) {
        if (DATE_FIELDS.contains(field)) {
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
     * Build facets map from DrillSideways result.
     */
    private FacetBuildResult buildFacetsFromDrillSideways(final Facets facetsResult) {
        final Map<String, List<FacetValue>> facets = new HashMap<>();
        final Map<String, Long> perFieldDurationMicros = new LinkedHashMap<>();
        final long overallStart = System.nanoTime();
        for (final String dimension : FACETED_FIELDS) {
            final long fieldStart = System.nanoTime();
            try {
                final FacetResult facetResult = facetsResult.getTopChildren(100, dimension);
                if (facetResult != null && facetResult.labelValues.length > 0) {
                    final List<FacetValue> values = new ArrayList<>();
                    for (final LabelAndValue lv : facetResult.labelValues) {
                        values.add(new FacetValue(lv.label, lv.value.intValue()));
                    }
                    facets.put(dimension, values);
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
        if (!FACETED_FIELDS.contains(filter.field()) || "range".equals(op)) {
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

            // Retrieve top facets for each dimension
            for (final String dimension : FACETED_FIELDS) {
                final long fieldStart = System.nanoTime();
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

        if (query instanceof final BooleanQuery bq) {
            // Iterate over all occur types
            for (final BooleanClause.Occur occur : BooleanClause.Occur.values()) {
                for (final Query subQuery : bq.getClauses(occur)) {
                    final long cost = estimateQueryCost(subQuery, searcher);
                    final String occurName = occur.name();

                    if (subQuery instanceof TermQuery || subQuery instanceof WildcardQuery) {
                        components.add(new QueryComponent(
                                subQuery.getClass().getSimpleName(),
                                extractField(subQuery),
                                extractValue(subQuery),
                                occurName,
                                cost,
                                formatCost(cost)
                        ));
                    } else {
                        // Recursively extract from nested boolean queries
                        extractQueryComponents(subQuery, components, searcher);
                    }
                }
            }
        } else {
            final long cost = estimateQueryCost(query, searcher);
            components.add(new QueryComponent(
                    query.getClass().getSimpleName(),
                    extractField(query),
                    extractValue(query),
                    null,
                    cost,
                    formatCost(cost)
            ));
        }
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
                    if (FACETED_FIELDS.contains(field) || STRING_FIELDS.contains(field)) {
                        builder.add(new TermQuery(new Term(field, f.value())), BooleanClause.Occur.MUST_NOT);
                    } else if (LONG_POINT_FIELDS.contains(field)) {
                        builder.add(LongPoint.newExactQuery(field, parseLongFilterValue(field, f.value())), BooleanClause.Occur.MUST_NOT);
                    }
                }
            }
            case "not_in" -> {
                if (f.values() != null) {
                    for (final String v : f.values()) {
                        if (FACETED_FIELDS.contains(field) || STRING_FIELDS.contains(field)) {
                            builder.add(new TermQuery(new Term(field, v)), BooleanClause.Occur.MUST_NOT);
                        } else if (LONG_POINT_FIELDS.contains(field)) {
                            builder.add(LongPoint.newExactQuery(field, parseLongFilterValue(field, v)), BooleanClause.Occur.MUST_NOT);
                        }
                    }
                }
            }
            case "range" -> {
                final long fromVal = f.from() != null ? parseLongFilterValue(field, f.from()) : Long.MIN_VALUE;
                final long toVal = f.to() != null ? parseLongFilterValue(field, f.to()) : Long.MAX_VALUE;
                builder.add(LongPoint.newRangeQuery(field, fromVal, toVal), BooleanClause.Occur.FILTER);
            }
            case "eq" -> {
                if (f.value() != null) {
                    if (FACETED_FIELDS.contains(field) || STRING_FIELDS.contains(field)) {
                        builder.add(new TermQuery(new Term(field, f.value())), BooleanClause.Occur.FILTER);
                    } else if (LONG_POINT_FIELDS.contains(field)) {
                        builder.add(LongPoint.newExactQuery(field, parseLongFilterValue(field, f.value())), BooleanClause.Occur.FILTER);
                    }
                }
            }
            case "in" -> {
                if (f.values() != null) {
                    if (FACETED_FIELDS.contains(field) || STRING_FIELDS.contains(field)) {
                        final BooleanQuery.Builder orBuilder = new BooleanQuery.Builder();
                        for (final String v : f.values()) {
                            orBuilder.add(new TermQuery(new Term(field, v)), BooleanClause.Occur.SHOULD);
                        }
                        builder.add(orBuilder.build(), BooleanClause.Occur.FILTER);
                    } else if (LONG_POINT_FIELDS.contains(field)) {
                        final long[] vals = new long[f.values().size()];
                        for (int i = 0; i < f.values().size(); i++) {
                            vals[i] = parseLongFilterValue(field, f.values().get(i));
                        }
                        builder.add(LongPoint.newSetQuery(field, vals), BooleanClause.Occur.FILTER);
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

            for (final String dimension : FACETED_FIELDS) {
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
            for (final String dimension : FACETED_FIELDS) {
                dimensions.put(dimension, new FacetDimensionCost(dimension, 0, 0, 0.0));
            }
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
