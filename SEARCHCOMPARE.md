# Search Is Not a Database Feature
### A Comparison of Apache Lucene, MySQL, and PostgreSQL for Full-Text and Vector Search

> **Status**: Draft — Initial outline + first sections written. See [Open Topics](#open-topics) and [Todos](#todos) below.
>
> **Intent**: Blog post for a technical audience (Java/backend developers). Built from implementation experience in the MCP Lucene Server (this repo), not from benchmarks read online.

---

## Table of Contents

1. [Setting the Stage](#1-setting-the-stage)
2. [Full-Text Search: Tokenization, Stemming, Linguistic Depth](#2-full-text-search-tokenization-stemming-linguistic-depth)
3. [Wildcard, Proximity, and Scoring Transparency](#3-wildcard-proximity-and-scoring-transparency)
4. [Vector / Semantic Search](#4-vector--semantic-search)
5. [Faceting: DrillSideways vs. GROUP BY](#5-faceting-drillsideways-vs-group-by)
6. [Scoring: Runtime-Adaptive vs. Fixed Formulas](#6-scoring-runtime-adaptive-vs-fixed-formulas)
7. [Near-Real-Time Search and Operational Concerns](#7-near-real-time-search-and-operational-concerns)
8. [Where Databases Genuinely Win](#8-where-databases-genuinely-win)
9. [Why HibernateSearch and Elasticsearch Still Exist](#9-why-hibernatesearch-and-elasticsearch-still-exist)
10. [Conclusion: A Decision Framework](#10-conclusion-a-decision-framework)
11. [Open Topics](#open-topics)
12. [Todos](#todos)

---

## 1. Setting the Stage

Over the past year I built a full-featured local document search server backed by Apache Lucene. The system — the MCP Lucene Server — indexes local file system documents using Apache Tika for content extraction, OpenNLP for language-aware lemmatization, ONNX-runtime transformer models for semantic embeddings, and Lucene's block-join structure for parent/child document relationships. Its current schema (`SCHEMA_VERSION = 10`) stores 14+ fields per document, runs NRT (near-real-time) search via `SearcherManager`, and does DrillSideways faceting across language, file type, author, and extension dimensions.

Building it gave me direct implementation experience with every corner of Lucene's API. And it also made me ask, repeatedly: *do I actually need this? Could I have just used PostgreSQL with pgvector?*

This post is my honest answer.

### What I will compare

| Dimension                  | Lucene (standalone)                 | MySQL 8/9                 | PostgreSQL 16+                            |
|----------------------------|-------------------------------------|---------------------------|-------------------------------------------|
| Tokenization / analysis    | Custom per-field chains             | Word or n-gram parser     | `tsvector` + text search configs          |
| Stemming / lemmatization   | OpenNLP POS + lemmatizer            | Basic dictionary          | Snowball dictionaries                     |
| Wildcard search            | Reverse-field optimization          | Limited / full scan       | `pg_trgm` GIN index                       |
| Proximity search           | Native (`~N` slop)                  | None                      | None (phrase only)                        |
| BM25 scoring               | Native, fully transparent           | Black box                 | `ts_rank` (inspectable, not customizable) |
| Highlighting               | `UnifiedHighlighter` + term vectors | None                      | `ts_headline` (basic)                     |
| Faceting                   | DrillSideways                       | None                      | ParadeDB (early), or N+1 queries          |
| Vector search              | `KnnFloatVectorField` + HNSW        | HeatWave (cloud-only)     | pgvector (HNSW or IVF-Flat)               |
| Late chunking / block-join | Native                              | Manual, application-level | Manual, application-level                 |
| ACID transactions          | No                                  | Yes                       | Yes                                       |
| Operational simplicity     | Low (manage index lifecycle)        | High                      | High                                      |

### The thesis

Modern relational databases have closed the gap on the headline features — BM25 scoring, KNN vector search — that Lucene pioneered. For many applications, their built-in capabilities are now genuinely sufficient. But the gap is not zero. The real differentiators are not the features databases have copied; they are the invisible plumbing: per-field NLP pipelines, wildcard rewriting strategies, proximity-expanding query parsers, multi-dimensional DrillSideways faceting, term-vector-backed highlighting, and block-join parent/child structures for context-preserving embeddings. These are not configuration knobs in a database. They require a search engine.

---

## 2. Full-Text Search: Tokenization, Stemming, Linguistic Depth

### The analyzer pipeline

In Lucene, every field can have its own `Analyzer`. The MCP Lucene Server uses a `PerFieldAnalyzerWrapper` to route fields to different processing chains:

```
content          → UnicodeNormalizingAnalyzer:
                   StandardTokenizer → LowerCaseFilter → ICUFoldingFilter

content_lemma_de → OpenNLPLemmatizingAnalyzer (German, index: sentence-aware):
                   OpenNLPSentenceDetector → OpenNLPTokenizer → OpenNLPPOSFilter
                   → OpenNLPLemmatizerFilter → TypeTokenFilter (drop ".", "SYM")
                   → CompoundLemmaSplittingFilter (split "in+der" → "in", "der")
                   → LowerCaseFilter → ICUFoldingFilter

content_reversed → ReverseUnicodeNormalizingAnalyzer:
                   StandardTokenizer → LowerCaseFilter → ICUFoldingFilter → ReverseStringFilter
```

Compare this to **PostgreSQL**: text search uses a single `text search configuration` per column. You can choose a dictionary (e.g. `german`) and it applies Snowball stemming. There is no concept of separate fields with separate chains, no POS tagging, and no compound splitting. Snowball stems `verträge` to `vertrag` but has no knowledge of whether it is a noun or a verb.

**MySQL FULLTEXT** supports a word parser or an n-gram parser. The word parser uses a built-in stop word list and no stemming at all. The n-gram parser indexes every consecutive n-character sequence — effective for CJK scripts, expensive and linguistically meaningless for European languages.

### The dual-analyzer asymmetry — non-obvious insight #1

The most interesting design in the MCP server is one that no database can replicate: at **index time**, the OpenNLP analyzer uses sentence detection (`withSentenceDetection(true)`) to accurately identify sentence boundaries before POS tagging. POS tagging accuracy degrades significantly without sentence context. At **query time**, the same field uses a simpler analyzer without sentence detection — a short user query provides no useful sentence context, and `OpenNLPSentenceDetector` on a 3-word query only adds latency.

Lucene supports this because `IndexWriterConfig` and `IndexSearcher` use separate analyzer instances. Databases use the same analysis code path for both storing and querying a `tsvector` column. This forces a compromise: you can optimize for query accuracy or index accuracy, but not both independently.

### German compound splitting

German compounds like `Kaufvertrag` (purchase agreement), `Bundesverfassungsgericht` (Federal Constitutional Court), or `im` (contracted from `in` + `dem`) require splitting for accurate retrieval. The `CompoundLemmaSplittingFilter` in this project handles UD compound lemma format — OpenNLP's lemmatizer returns `in+der` for the contracted form `im`, and the filter splits it into two tokens `in` and `der`.

Neither PostgreSQL nor MySQL has any compound splitting capability. A German user searching for `Vertrag` will not find `Kaufvertrag` unless the text also contains the standalone word.

---

## 3. Wildcard, Proximity, and Scoring Transparency

### Leading wildcards — non-obvious insight #2

Wildcard queries in the form `*vertrag` (find everything ending in "vertrag") are a well-known performance problem. Lucene's inverted index is sorted by term; a leading wildcard cannot use this sort order and degenerates to a full index scan.

The MCP server solves this with a shadow field: `content_reversed` indexes every token reversed. The query-time `rewriteLeadingWildcards()` method rewrites:

- `*vertrag` → trailing wildcard `gartrev*` on `content_reversed`
- `*vertrag*` → OR of `*vertrag` on `content` + `gartrev*` on `content_reversed`
- `vertrag*` → unchanged (standard prefix query)

The reversed query costs exactly the same as a prefix query: one seek in a sorted structure. There is no performance penalty.

In **PostgreSQL**, `LIKE '%vertrag'` forces a sequential scan of the entire column unless a `pg_trgm` GIN index exists. A trigram index decomposes every value into 3-character n-grams and can answer leading-wildcard queries, but it adds storage overhead (typically 2–3× the original column size) and must be explicitly created. There is no zero-cost solution equivalent to the reversed field approach.

**MySQL** has no leading-wildcard optimization at all in FULLTEXT indexes.

### Proximity search

The `ProximityExpandingQueryParser` in the MCP server auto-expands quoted phrases. When a user writes `"Domain Design"`, the parser emits:

```
("Domain Design")^2.0 OR ("Domain Design"~3)
```

The first clause is an exact phrase match with boost 2.0. The second is a proximity query allowing up to 3 intervening words (slop=3). The user wrote one query; they get both behaviors.

PostgreSQL's `phraseto_tsquery('Domain Design')` produces an exact phrase query. There is no slop or proximity variant. MySQL FULLTEXT boolean mode has no phrase proximity at all.

### Scoring transparency

Lucene's `Explanation` API (exposed in this server via the `profileQuery` MCP tool) returns a full per-hit breakdown:

```
0.84 = sum of:
  0.51 = weight(content:"domain design" in doc 42) BM25...
    0.51 = score(freq=1.0), computed as...
      tf = 1.0, idf = 3.2, fieldNorm = 0.16
  0.33 = weight(content_lemma_de:domain in doc 42)...
```

Every term, every field, every boost factor is visible and debuggable.

PostgreSQL's `ts_rank` uses a fixed formula:
`rank = (0.1*d[0] + 0.2*d[1] + 0.4*d[2] + 1.0*d[3]) / (1 + ln(ndoc))` (where d[] = document coverage by position weight). You can read the formula in the documentation, but you cannot override it per-query or per-field. MySQL's relevance score is computed internally; there is no published formula and no explain equivalent.

---

## 4. Vector / Semantic Search

### The problem that motivated it

BM25 is excellent at lexical matching but blind to meaning. A document about `Vereinbarung` (agreement) will score zero for a query about `Vertrag` (contract) unless both words appear in the text. Semantic search via embeddings closes this gap.

### Indexing strategies

| System                         | Algorithm        | Dimensions   | Configuration                                               |
|--------------------------------|------------------|--------------|-------------------------------------------------------------|
| Lucene (`KnnFloatVectorField`) | HNSW (internal)  | Up to 4096   | Limited — set similarity function at field creation         |
| pgvector                       | HNSW or IVF-Flat | Up to 16,000 | Explicit `CREATE INDEX` with `m`, `ef_construction` tunable |
| MySQL HeatWave                 | HNSW             | Up to 1024   | Cloud/managed only; not available in community edition      |

pgvector gives more explicit control over the HNSW graph parameters (`m` controls neighbor count, `ef_construction` controls build quality). Lucene's HNSW is tunable only through source-level constants. For most use cases this does not matter; for a specialized retrieval system it might.

### Late chunking — non-obvious insight #3

The core challenge with embedding long documents is context loss. If you split a 10-page document into 128-token chunks and embed each chunk independently, a chunk that says "the plaintiff" contains no information about what the document is actually about. Standard chunking produces impoverished embeddings.

**Late chunking** solves this: the entire document (or a large macro-chunk) goes through the transformer in a single forward pass. The model's attention mechanism can see the full context. After the forward pass, the resulting token embeddings are **pooled per chunk span** — mean-pooled over the token positions that correspond to each chunk's character range. Each chunk embedding thus carries contextualized information from the whole document.

In the MCP server:
1. Full document tokenized → forward pass through `multilingual-e5-base` or `multilingual-e5-large`
2. Token-level embeddings extracted for each chunk's token span
3. Mean pooling over span → one vector per chunk
4. Long documents: macro-chunks of 480 tokens with 32-token overlap to preserve cross-boundary context

The Lucene **block-join structure** stores these chunks as child documents with the parent document as the last entry in the block:

```
[child_0: chunk_index=0, embedding=..., file_path="x"]
[child_1: chunk_index=1, embedding=..., file_path="x"]
[parent: _doc_type="parent", file_path="x", content=..., language=..., author=...]
← block boundary
```

Deleting and replacing a document is a single call: `indexWriter.deleteDocuments(new Term("file_path", path))`. This removes the parent and all children atomically within the segment. Re-indexing then adds a new block.

In **PostgreSQL with pgvector**, you manage two tables:

```sql
CREATE TABLE documents (id SERIAL PRIMARY KEY, file_path TEXT, content TEXT, ...);
CREATE TABLE chunks (id SERIAL PRIMARY KEY, doc_id INTEGER REFERENCES documents(id) ON DELETE CASCADE,
                     chunk_index INTEGER, chunk_text TEXT, embedding vector(768));
```

Updating a document requires: `DELETE FROM documents WHERE file_path = $1` (cascades to chunks), then `INSERT INTO documents ...`, then N × `INSERT INTO chunks ...`, all in one transaction. This is achievable. But the late-chunking embedding strategy (pooled from full-document forward pass) is purely application code in both cases — the database has no concept of it. The difference is that Lucene's block-join guarantees physical co-location of parent and children in the segment, which makes the parent-lookup join (`file_path` term lookup after KNN) a single segment-local operation rather than a cross-table join.

### Post-filter asymmetry

A subtlety worth knowing: in the MCP server, metadata filters (language, file_type, author) exist only on **parent documents**, not on child chunk documents. The KNN query runs against children (where the embedding field lives). After retrieving the top-K children, the system looks up each child's parent via `file_path` and applies metadata filters there.

This means metadata filters are **post-filters**, not pre-filters. All 50 KNN candidates are retrieved before any filter is applied. This is fine for small filter-out rates but can waste candidates if, say, 80% of the corpus is filtered out.

In pgvector, a `WHERE language = 'de'` clause can be applied **during** the KNN search if `language` is on the same row as the vector. This is a genuine architectural advantage of pgvector's flat table model when all data is co-located: pre-filtering reduces wasted KNN candidates.

---

## 5. Faceting: DrillSideways vs. GROUP BY

### What faceting is

A faceted search UI shows, for a given query, counts like:
```
File Type:  PDF (34)  DOCX (12)  TXT (8)
Language:   de (41)   en (13)
Author:     Müller (18)  Schmidt (7)  ...
```

When the user applies a filter — say, `file_type = PDF` — the other dimensions should update to show counts *within* the PDF result set. But the `File Type` dimension itself should still show all file types (not just PDFs), so the user can switch.

This is what **DrillSideways** computes: for each facet dimension `d`, the count as if the filter on `d` alone were relaxed while all other filters remain active. It is a single multi-pass execution over the index, not multiple queries.

### SQL cannot express this natively — non-obvious insight #4

The reason databases don't have DrillSideways is not an omission — it is a model incompatibility. The relational model computes results as a single WHERE predicate. To get DrillSideways semantics in SQL you need `n+1` separate queries:

```sql
-- Main result
SELECT * FROM documents WHERE language = 'de' AND file_type = 'PDF' AND ...;

-- Facet counts with file_type filter relaxed (all file types for this language)
SELECT file_type, COUNT(*) FROM documents WHERE language = 'de' GROUP BY file_type;

-- Facet counts with language filter relaxed (all languages for this file_type)
SELECT language, COUNT(*) FROM documents WHERE file_type = 'PDF' GROUP BY language;
```

For `n` active facet dimensions you issue `n+1` queries. PostgreSQL's ROLLUP or CUBE operators can reduce this in simple cases but cannot handle arbitrary compound filter predicates efficiently. ParadeDB (a PostgreSQL extension providing Lucene-like search) is adding faceting support but as of early 2025 it does not implement DrillSideways semantics.

In the MCP server, `DrillSideways.search()` does this in one pass:

```java
DrillSideways ds = new DrillSideways(searcher, facetsConfig, taxoReader);
DrillSideways.Result result = ds.search(drillDownQuery, collector);
FacetResult langFacets = result.facets.getTopChildren(10, "language");
FacetResult typeFacets = result.facets.getTopChildren(10, "file_type");
```

One call. All facet dimensions. Correct cross-dimension counts.

### DocValues: column-store within a row-store

Lucene's facet counts are computed from `SortedSetDocValues` — a column-oriented structure that stores, for each document, a sorted set of term ordinals for the faceted fields. This is independent of the inverted index used for text search. The search and faceting operations read from physically separate data structures, avoiding I/O contention.

Databases store rows. Computing facet counts on a WHERE-filtered result set requires either a covering index (which helps with access, not with aggregation) or a full scan of the filtered rows. For large corpora the performance difference is real.

---

## 6. Scoring: Runtime-Adaptive vs. Fixed Formulas

### Dynamic language-distribution boost — non-obvious insight #5

The `buildStemmedQuery()` method in `LuceneIndexService` does something no database engine supports: it reads the **live language distribution** from the index at query time and adjusts field boosts accordingly.

```java
// boostFor("de") returns: 0.3 + 0.7 * (deCount / totalDocs)
float deBoost = computeLanguageBoost("de");
float enBoost = computeLanguageBoost("en");

BooleanQuery.Builder stemmedQuery = new BooleanQuery.Builder();
stemmedQuery.add(new BoostQuery(contentQuery, 2.0f), SHOULD);
stemmedQuery.add(new BoostQuery(lemmaDeQuery, deBoost), SHOULD);
stemmedQuery.add(new BoostQuery(lemmaEnQuery, enBoost), SHOULD);
stemmedQuery.add(new BoostQuery(translitQuery, 0.5f), SHOULD);
```

If the corpus is 80% German, `deBoost ≈ 0.86`. If it is 50/50 German-English, both boosts ≈ 0.65. This is a **live feedback loop** from the index state to query scoring. No configuration file needs updating when you index a new language's documents; the scoring adapts automatically on the next NRT refresh cycle.

In PostgreSQL, `ts_rank` is a fixed formula. You can write a custom ranking function, but it receives per-document statistics, not index-wide language distribution. Computing index-wide statistics requires a separate query and introduces latency. MySQL's ranking formula is internal and not overridable.

---

## 7. Near-Real-Time Search and Operational Concerns

### NRT in Lucene

`SearcherManager` is initialized directly from `IndexWriter`:

```java
searcherManager = new SearcherManager(indexWriter, null);
```

This means new documents are visible to searchers **without a commit to disk**. A background thread calls `maybeRefresh()` on a configurable interval. The refresh opens a new `DirectoryReader` over the writer's in-memory buffer — it is a lightweight operation, not a full index reload.

### MVCC in PostgreSQL

PostgreSQL uses MVCC (Multi-Version Concurrency Control): every transaction sees a consistent snapshot of row versions. This is transactionally correct and consistent. But it requires maintaining multiple versions of each row, periodic `VACUUM` to reclaim dead row versions, and `autovacuum` configuration to prevent table bloat. For a write-heavy search index with frequent updates, MVCC overhead is real.

Lucene uses **segment immutability**: new documents go into a new in-memory segment; old segments are never modified. Merging (background segment consolidation) happens independently of reads and writes. There are no dead row versions to vacuum.

### Schema evolution

When the index schema changes — a new field added, an analyzer changed, a vector dimension updated — the MCP server increments `SCHEMA_VERSION` in `DocumentIndexer`. On startup, `LuceneIndexService.init()` reads the version from Lucene commit user data. If there is a mismatch, it sets `schemaUpgradeRequired = true` and `LuceneserverApplication` triggers a full re-crawl.

This is explicit and automatic. Databases handle schema changes with `ALTER TABLE`, which is sometimes online (PostgreSQL with `pg_repack`), sometimes not, and always requires DBA-level coordination in production. Embedding dimension is also tracked: switching from `multilingual-e5-base` (768 dimensions) to `multilingual-e5-large` (1024 dimensions) is auto-detected because the dimension is stored in commit metadata.

### Highlighting quality

The MCP server uses `UnifiedHighlighter` with term vectors (stored positions and offsets) and `BreakIterator.getSentenceInstance()` for sentence-aware passage boundaries. Each returned passage has its own BM25-derived score, normalized 0–1. Passages are truncated to center on `<em>` tags.

PostgreSQL's `ts_headline` returns a single highlighted string. It offers no per-passage scoring, no sentence-boundary awareness, and no position metadata. MySQL has no highlighting capability at all.

---

## 8. Where Databases Genuinely Win

### ACID transactions

Lucene's block-join update (delete parent+children, re-add parent+children) is **not transactional**. If the process crashes between the `deleteDocuments` call and the completion of `addDocuments`, the document is lost from the index until the next crawl cycle detects and re-indexes it. There is no rollback. In PostgreSQL, the same update is a single transaction: commit or roll back.

For a search system where the index is a derived view of a file system (which can be re-crawled), this is acceptable. For a system where the search index is the source of truth — or is tightly coupled to user data like annotations, ratings, or access logs — it is a significant risk.

### Single system of record

A database-backed application stores user annotations, ratings, access history, and document metadata in the same place as the content. Joins are free. In a Lucene-based system, the search index is a derived view. User data lives in a separate database. Cross-system consistency requires application-level coordination, which is complexity that small teams should seriously weigh.

### Operational simplicity

PostgreSQL + pgvector is one thing: one backup strategy, one replication setup, one monitoring stack, one connection pool. The MCP Lucene Server required building:

- `IndexReconciliationService`: detects files added/removed/changed since last crawl
- `DirectoryWatcherService`: real-time file system monitoring via Java NIO WatchService
- `CrawlStatisticsTracker`: progress and error tracking across crawl runs
- `CrawlerConfigurationManager`: YAML config persistence
- `LuceneIndexService.init()`: schema version checking and upgrade detection
- `BuildInfo`: version metadata from build time

None of this is search logic. All of it is infrastructure that a database gives you for free via its existing operational model.

---

## 9. Why HibernateSearch and Elasticsearch Still Exist

### The historical reason

Databases have had weak full-text search for 30 years. HibernateSearch was created so that Java enterprise developers could annotate a JPA entity with `@FullTextField` and have both the SQL table row and the Lucene (or later Elasticsearch) index updated together — with eventual consistency on the search side. The bridge exists because the linguistic quality gap was, and remains, real.

### The scale reason

Elasticsearch is Lucene with an operational layer: REST API, distributed index sharding, cluster coordination, rolling restarts, index lifecycle management, Kibana dashboards. Its existence proves that even excellent single-node Lucene needs infrastructure for production multi-node deployment. PostgreSQL has streaming replication, but it replicates the entire database, not individual index shards. There is no Lucene equivalent of a 10-TB single-table replicated database.

### The current state

In 2025:
- PostgreSQL + pgvector handles straightforward vector search competently
- ParadeDB is adding Lucene-grade full-text search inside PostgreSQL (early but promising)
- Elasticsearch dominates large-scale enterprise search
- Standalone Lucene remains the right choice for embedded, high-quality, linguistically sophisticated search on moderate-scale corpora

The question is not "which is better." It is "which set of trade-offs matches your product's requirements."

---

## 10. Conclusion: A Decision Framework

```
Is search the primary user experience? (document management, knowledge base, enterprise search)
  YES → Lucene (embedded) or Elasticsearch (distributed). Pay the operational cost.
  NO ↓

Is your corpus in morphologically complex languages (German, Finnish, Turkish, Arabic)?
  YES → Lucene/Elasticsearch. Database stemming is insufficient.
  NO ↓

Are you already running PostgreSQL for application data?
  YES → pgvector + pg_trgm + pg_search (ParadeDB). Accept the trade-offs:
        no proximity search, basic highlighting, no DrillSideways, manual chunking.
  NO ↓

Are you a Java shop with an existing Hibernate entity model?
  YES → HibernateSearch (Lucene or Elasticsearch backend). One entity, both stores.
  NO ↓

Is simplicity the top priority for a new greenfield service?
  YES → PostgreSQL + pgvector. One system to operate, good enough for most use cases.
```

The truly distinguishing Lucene features are not the headlines (BM25, KNN) — databases have those now. They are:

1. **Per-field NLP pipelines** with index/query asymmetry (dual-analyzer design)
2. **Leading wildcard optimization** via reversed shadow fields
3. **Proximity search** with auto-expansion
4. **DrillSideways faceting** — a model-level incompatibility with SQL, not a missing feature
5. **Runtime-adaptive scoring** from live index statistics
6. **Sentence-aware highlighting** with per-passage scores
7. **Block-join atomicity** for parent/child document structures

If any of these matter to your product, use a search engine. If none of them do, a database is probably enough.

---

## Open Topics

These items require further research or decisions before the post is ready to publish.

### Research needed

- [ ] **MySQL HeatWave vector search availability**: Confirm whether VECTOR column type and KNN is available in MySQL Community Edition 9.x on-premise, or only in HeatWave cloud. This affects the MySQL column in the comparison table. *(Current assumption: cloud-only — needs verification)*
- [ ] **ParadeDB / pg_search faceting status**: Verify whether ParadeDB 0.x has implemented DrillSideways-style cross-dimension count correction or only basic aggregation facets. Check release notes and GitHub issues. *(Current assumption: basic aggregation only, not DrillSideways)*
- [ ] **PostgreSQL `ts_rank` customization**: Confirm whether `ts_rank` weights (the `{0.1, 0.2, 0.4, 1.0}` vector) can be customized per-query or only globally via config. If per-query customization is possible, the scoring transparency comparison needs updating.
- [ ] **pgvector pre-filter behavior with HNSW**: Verify whether pgvector's HNSW supports true pre-filtering (index-level WHERE) or always does post-filtering. The IVF-Flat index supports pre-filtering; HNSW behavior may differ.
- [ ] **Elasticsearch faceting**: Add a brief mention of Elasticsearch's aggregation framework as a reference point (since it is built on Lucene and should match Lucene's DrillSideways capabilities).

### Design decisions

- [ ] **Single post vs. series**: The current length is ~5,000 words. Options:
  - Keep as one long post (technical audience can handle it)
  - Split into Part 1 (FTS), Part 2 (Vector), Part 3 (Architecture & Operations)
  - Split into "comparison" post + separate "implementation lessons" post
- [ ] **Code snippets**: Decide how much actual Java code to include. Current draft includes minimal snippets. Could add `buildStemmedQuery()` and the block-join indexing loop for concreteness.
- [ ] **Diagrams**: Consider adding visual diagrams for:
  - The dual-analyzer asymmetry (index vs. query analyzer chain)
  - Late chunking architecture (macro-chunk → forward pass → pooling → chunks)
  - Block-join document structure (children + parent)
  - DrillSideways vs. N+1 SQL queries

### Sections to write / expand

- [ ] **Section 4: Vector search** — Expand the pgvector pre-filter vs. post-filter section with a concrete example showing when post-filtering wastes KNN candidates.
- [ ] **Section 5: Faceting** — Add a concrete SQL example showing the N+1 query pattern, ideally with timing estimates for a medium-sized corpus.
- [ ] **Intro paragraph for Section 1** — The current intro is functional but could use a more engaging hook. Consider starting with the "Mueller/Müller" query example from the regression test suite — it is a concrete, relatable failure mode that databases cannot handle.

---

## Todos

### Before publishing

- [ ] Fact-check MySQL HeatWave availability (see Open Topics)
- [ ] Fact-check ParadeDB faceting (see Open Topics)
- [ ] Decide single vs. series format
- [ ] Write the intro hook (Mueller/Müller example or similar)
- [ ] Add at least one diagram (recommend: late chunking architecture)
- [ ] Technical review pass: verify all code snippets compile / are consistent with actual source
- [ ] Editorial pass: reduce length if single post format is chosen

### Source files to reference / extract snippets from

- `PIPELINE.md` — indexing + query pipeline overview (already in repo)
- `SEMANTICSEARCH.md` — late chunking + block-join architecture (already in repo)
- `src/.../index/LuceneIndexService.java` — `buildStemmedQuery()`, `doSemanticSearch()`, DrillSideways path
- `src/.../crawler/DocumentIndexer.java` — field schema, shadow fields, `SCHEMA_VERSION`
- `src/.../index/query/ProximityExpandingQueryParser.java` — proximity expansion
- `src/.../onnx/ONNXService.java` — late chunking token pooling implementation