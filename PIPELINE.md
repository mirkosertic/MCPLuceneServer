# MCP Lucene Server - Indexing and Query Pipeline

This document provides technical reference documentation for the multi-analyzer indexing pipeline and multi-field weighted query pipeline used by the MCP Lucene Server.

For general feature overview and user-facing documentation, see [README.md](README.md).

---

## 1. Overview

The MCP Lucene Server uses a multi-analyzer pipeline for indexing and a multi-field weighted query pipeline for searching. Documents are indexed with multiple shadow fields using different analyzers to optimize different search patterns (exact matching, leading wildcards, lemmatization, transliteration). At query time, searches combine these fields using weighted OR queries to balance precision and recall.

---

## 2. Indexing Pipeline

### 2.1 Document Extraction

Before indexing, each document passes through the extraction pipeline:

1. **Apache Tika extraction**: Tika extracts raw text content and metadata from PDFs, Office documents, and other supported formats
2. **Content normalization**: The extracted text undergoes multiple normalization steps:
   - NFKC Unicode normalization (full-width characters mapped to standard equivalents)
   - Ligature expansion (PDF ligatures like fi, fl expanded to separate characters)
   - Removal of invalid characters (U+FFFD replacement character, zero-width characters, control characters)
   - Whitespace normalization (multiple spaces collapsed to single space)
3. **Language detection**: Tika's OptimaizeLangDetector identifies the document language (ISO 639-1 code: en, de, fr, etc.)

### 2.2 Field Schema

Each document is indexed with the following fields:

| Field | Analyzer | Stored | Term Vectors | Faceted | Purpose |
|-------|----------|--------|-------------|---------|---------|
| `file_path` | None (StringField) | Yes | No | No | Unique document ID |
| `file_name` | UnicodeNormalizingAnalyzer | Yes | No | No | Searchable filename |
| `content` | UnicodeNormalizingAnalyzer | Yes | Yes (positions+offsets) | No | Primary search + highlighting |
| `content_reversed` | ReverseUnicodeNormalizingAnalyzer | No | No | No | Leading wildcard optimization |
| `content_lemma_de` | OpenNLPLemmatizingAnalyzer (de) | No | No | No | German lemmatization |
| `content_lemma_en` | OpenNLPLemmatizingAnalyzer (en) | No | No | No | English lemmatization |
| `content_translit_de` | GermanTransliteratingAnalyzer | No | No | No | Umlaut digraph transliteration |
| `file_extension` | None (StringField) | Yes | No | Yes | Facet + filter |
| `file_type` | None (StringField) | Yes | No | Yes | Facet + filter |
| `language` | None (StringField) | Yes | No | Yes | Facet + filter |
| `author` | UnicodeNormalizingAnalyzer (TextField) | Yes | No | Yes | Facet + filter |
| `title` | UnicodeNormalizingAnalyzer | Yes | No | No | Searchable |
| `keywords` | UnicodeNormalizingAnalyzer | Yes | No | No | Searchable |
| `creator` | UnicodeNormalizingAnalyzer | Yes | No | No | Searchable |
| `subject` | UnicodeNormalizingAnalyzer | Yes | No | No | Searchable |
| `file_size` | LongPoint | Yes (StoredField) | No | No | Range queries |
| `created_date` | LongPoint | Yes (StoredField) | No | No | Range queries |
| `modified_date` | LongPoint | Yes (StoredField) | No | No | Range queries |
| `indexed_date` | LongPoint | Yes (StoredField) | No | No | Range queries |
| `content_hash` | None (StringField) | Yes | No | No | Change detection |

**Important**: ALL content-based shadow fields (`content_reversed`, `content_lemma_de`, `content_lemma_en`, `content_translit_de`) are indexed for ALL documents regardless of detected language. This enables mixed-language content matching and robust cross-language search.

### 2.3 Analyzer Chains

Each analyzer applies a specific token processing chain to the input text.

#### UnicodeNormalizingAnalyzer

Used for: `content`, `file_name`, `title`, `keywords`, `author`, `creator`, `subject`

**Token chain:**
```
StandardTokenizer → LowerCaseFilter → ICUFoldingFilter
```

**What it does:**
- `StandardTokenizer`: Splits text into tokens using Unicode text segmentation rules
- `LowerCaseFilter`: Converts all tokens to lowercase
- `ICUFoldingFilter`: Applies Unicode normalization (NFKC), diacritic folding, and ligature expansion

**Examples:**

| Input | Output Tokens |
|-------|---------------|
| `"Der Vertrag wurde unterschrieben."` | `[der, vertrag, wurde, unterschrieben]` |
| `"Müller & Partner GmbH"` | `[muller, partner, gmbh]` (umlauts folded, `&` stripped by StandardTokenizer) |
| `"file_résumé.pdf"` | `[file_resume, pdf]` (diacritics removed) |
| `"café"` | `[cafe]` (accent removed) |
| `"naïve"` | `[naive]` (diaeresis removed) |

#### ReverseUnicodeNormalizingAnalyzer

Used for: `content_reversed`

**Token chain:**
```
StandardTokenizer → LowerCaseFilter → ICUFoldingFilter → ReverseStringFilter
```

**What it does:**
- Applies the same normalization as UnicodeNormalizingAnalyzer, then reverses each token

**Examples:**

| Input | Output Tokens |
|-------|---------------|
| `"Arbeitsvertrag"` | `[gartrevstiebra]` |
| `"Kaufvertrag"` | `[gartrevfuak]` |
| `"Mietvertrag"` | `[gartrevteim]` |

**Why it exists**: Enables efficient leading wildcard queries. `*vertrag` is internally rewritten as `gartrev*` on the `content_reversed` field, avoiding costly full-index scans.

#### OpenNLPLemmatizingAnalyzer

Used for: `content_lemma_de` (German), `content_lemma_en` (English)

This analyzer has two modes with different tokenization strategies:

**Sentence-aware mode (indexing)**: Uses OpenNLPTokenizer with sentence detection for accurate POS tagging on long texts

**Token chain (indexing):**
```
OpenNLPTokenizer(sentenceModel, tokenizerModel)
  → OpenNLPPOSFilter(posModel)
  → OpenNLPLemmatizerFilter(lemmatizerModel)
  → TypeTokenFilter(drop "." and "SYM")
  → CompoundLemmaSplittingFilter
  → LowerCaseFilter
  → ICUFoldingFilter
```

**Simple mode (query time)**: Uses StandardTokenizer without sentence detection for short queries

**Token chain (query time):**
```
StandardTokenizer
  → OpenNLPPOSFilter(posModel)
  → OpenNLPLemmatizerFilter(lemmatizerModel)
  → TypeTokenFilter(drop "." and "SYM")
  → CompoundLemmaSplittingFilter
  → LowerCaseFilter
  → ICUFoldingFilter
```

**Key pipeline details:**

- **TypeTokenFilter**: Drops punctuation tokens (OpenNLP POS type `"."` = all punctuation) and symbol tokens (type `"SYM"` = `&` etc.). StandardTokenizer would strip these, but OpenNLPTokenizer retains them as separate tokens because it's trained on Universal Dependencies data.

- **CompoundLemmaSplittingFilter**: Splits German UD compound lemmas on `+`. German contractions like "im" get lemmatized to "in+der", "zum" to "zu+der", "beim" to "bei+der". This filter splits them into individual searchable tokens.

- **LowerCaseFilter + ICUFoldingFilter applied AFTER lemmatization**: The lemmatizer requires original casing and POS tags to work correctly, so normalization comes last.

- **Independent caches per mode**: Sentence-aware and query-time modes use separate lemmatizer caches because different POS tagging contexts can produce different lemmas for the same token.

**German examples (sentence-aware mode):**

| Input | Output Tokens |
|-------|---------------|
| `"Der Vertrag wurde unterschrieben."` | `[der, vertrag, werden, unterschreiben]` |
| `"Die Häuser sind renoviert."` | `[der, haus, sein, renovieren]` |
| `"Er ging nach Hause."` | `[er, gehen, nach, haus]` |
| `"Er ist im Haus."` | `[er, sein, in, der, haus]` (compound "im"→"in+der" split) |
| `"Forschung & Entwicklung sind wichtig."` | `[forschung, entwicklung, sein, wichtig]` (`&` filtered as SYM) |
| `"R&D Abteilung"` | `[r&d, abteilung]` (embedded `&` preserved by tokenizer) |

**English examples (sentence-aware mode):**

| Input | Output Tokens |
|-------|---------------|
| `"The contracts were signed."` | `[the, contract, be, sign]` |
| `"She ran to the bus."` | `[she, run, to, the, bus]` |
| `"Multiple analyses were performed."` | `[multiple, analysis, be, perform]` |

**Query mode examples (single words):**

| Input | Output Tokens |
|-------|---------------|
| `"Vertrages"` | `[vertrag]` |
| `"Häuser"` | `[haus]` |
| `"ran"` | `[run]` |
| `"paid"` | `[pay]` |

#### GermanTransliteratingAnalyzer

Used for: `content_translit_de`

**Token chain:**
```
MappingCharFilter(ae→ä, oe→ö, ue→ü, Ae→Ä, Oe→Ö, Ue→Ü, AE→Ä, OE→Ö, UE→Ü)
  → StandardTokenizer
  → LowerCaseFilter
  → ICUFoldingFilter
```

**What it does:**
- `MappingCharFilter`: Maps ASCII digraphs to German umlauts before tokenization
- Then applies standard tokenization and normalization

**Examples:**

| Input | CharFilter Output | Final Tokens |
|-------|-------------------|--------------|
| `"Mueller"` | `"Müller"` | `[muller]` |
| `"Müller"` | `"Müller"` (no change) | `[muller]` |
| `"Kaese"` | `"Käse"` | `[kase]` |
| `"Goethe"` | `"Göthe"` | `[gothe]` |
| `"blue"` | `"blü"` (known false positive) | `[blu]` (acceptable at low boost) |

**Why it exists**: Handles the German convention of writing umlauts as ASCII digraphs. Enables queries like "Mueller" to match documents containing "Müller".

### 2.4 PerFieldAnalyzerWrapper Configuration

Two PerFieldAnalyzerWrapper instances route fields to appropriate analyzers:

**Index analyzer (sentence-aware OpenNLP for long texts):**
```
Default analyzer: UnicodeNormalizingAnalyzer

Field routing:
  content_reversed    → ReverseUnicodeNormalizingAnalyzer
  content_lemma_de    → OpenNLPLemmatizingAnalyzer("de", sentenceAware=true)
  content_lemma_en    → OpenNLPLemmatizingAnalyzer("en", sentenceAware=true)
  content_translit_de → GermanTransliteratingAnalyzer
  (all other fields)  → UnicodeNormalizingAnalyzer
```

**Query analyzer (simple mode for short queries):**
```
Default analyzer: UnicodeNormalizingAnalyzer

Field routing:
  content_reversed    → ReverseUnicodeNormalizingAnalyzer
  content_lemma_de    → OpenNLPLemmatizingAnalyzer("de", sentenceAware=false)
  content_lemma_en    → OpenNLPLemmatizingAnalyzer("en", sentenceAware=false)
  content_translit_de → GermanTransliteratingAnalyzer
  (all other fields)  → UnicodeNormalizingAnalyzer
```

---

## 3. Query Pipeline

### 3.1 Query Processing Steps

When a search request is received, it passes through the following steps:

#### Step 1: Query Parsing

`ProximityExpandingQueryParser` parses the query string targeting the `content` field. This parser extends Lucene's `QueryParser` with automatic phrase expansion and adaptive prefix scoring (see sections 3.3 and 3.4).

#### Step 2: Leading Wildcard Rewriting

`rewriteLeadingWildcards()` normalizes and rewrites the parsed query:

- **Lowercasing**: Wildcard/prefix terms are lowercased for fields in `LOWERCASE_WILDCARD_FIELDS` (because Lucene's QueryParser does NOT apply the analyzer to wildcard terms, but the index stores lowercased tokens)

- **Leading wildcard → reversed field**: Queries on `content` field:
  - `*vertrag` → `WildcardQuery("content_reversed", "gartrev*")`
  - `*vertrag*` → `OR(content:*vertrag*, content_reversed:gartrev*)`
  - `vertrag*` → unchanged (already efficient)

- Recurses into BooleanQuery sub-clauses to rewrite nested wildcards

#### Step 3: Stemmed Query Building

`buildStemmedQuery()` creates a weighted OR query combining multiple fields:

```
BooleanQuery(minShouldMatch=1):
  content query          (boost 2.0)      — SHOULD
  content_lemma_de query (dynamic boost)  — SHOULD
  content_lemma_en query (dynamic boost)  — SHOULD
  content_translit_de query (boost 0.5)   — SHOULD
```

**Field boosts:**
- The `content` query (unstemmed, exact) always gets the highest boost (2.0)
- Lemma field boosts are computed dynamically from the language distribution in the index: `boost = 0.3 + 0.7 * (langCount / totalDocs)`. If 80% of documents are German, the German lemma field gets boost ~0.86.
- The transliteration field always gets boost 0.5 regardless of language
- If a `language eq "xx"` filter is present, only that language's lemma field is included (at boost 1.0)
- Each shadow field query is also run through `rewriteLeadingWildcards()` for consistent normalization

#### Step 4: Highlight Query

A separate unstemmed query on the `content` field is used for highlighting. This ensures `**bold**` markers appear on the exact matching terms in the stored content, not on stemmed/lemmatized forms.

#### Step 5: Filter Application

Filters are classified and applied:

- **Positive facet filters** → DrillSideways (shows alternative facet values)
- **Negative filters** → MUST_NOT clauses
- **Range filters** → LongPoint range queries as FILTER clauses
- **String term filters** → TermQuery FILTER clauses
- **LongPoint exact filters** → LongPoint.newExactQuery FILTER clauses

#### Step 6: Result Highlighting

UnifiedHighlighter with `IndividualPassageFormatter` produces markdown bold (`**term**`) highlighted passages from the stored `content` field.

### 3.2 Supported Query Types

| Query Type | Syntax | Example | Notes |
|-----------|--------|---------|-------|
| Simple terms | `word1 word2` | `contract signed` | Implicit AND between terms |
| Phrase query | `"exact phrase"` | `"signed contract"` | Preserves word order; auto-expanded (see 3.3) |
| Boolean AND | `term1 AND term2` | `contract AND payment` | Both terms required |
| Boolean OR | `term1 OR term2` | `contract OR agreement` | Either term matches |
| Boolean NOT | `NOT term` | `NOT draft` | Excludes documents with term |
| Trailing wildcard | `prefix*` | `contract*` | Matches contracts, contracting, etc. |
| Leading wildcard | `*suffix` | `*vertrag` | Optimized via reversed field |
| Infix wildcard | `*infix*` | `*vertrag*` | OR of forward + reversed |
| Single char wildcard | `te?t` | `te?t` | Matches test, text, etc. |
| Fuzzy search | `term~N` | `contract~2` | Levenshtein edit distance (default: 2) |
| Proximity search | `"term1 term2"~N` | `"contract signed"~5` | Terms within N words |
| Field-specific | `field:term` | `title:report` | Search specific field |
| Grouping | `(a OR b) AND c` | `(contract OR agreement) AND signed` | Logical grouping |
| Range (numeric) | `field:[from TO to]` | `modified_date:[1609459200000 TO *]` | Numeric range |
| Boost | `term^N` | `contract^3` | Boost term importance |

### 3.3 Automatic Phrase Proximity Expansion

When you search for an exact multi-word phrase, the `ProximityExpandingQueryParser` automatically expands it to include near-matches:

```
User query:  "Domain Design"
Parsed as:   ("Domain Design")^2.0 OR ("Domain Design"~3)
```

**How it works:**
- Exact phrase match gets a 2.0x boost → always ranked highest
- Proximity match allows up to 3 words between terms → catches variations
- Single-word phrases are NOT expanded (no benefit)
- If user specifies slop (`"Domain Design"~5`), expansion is skipped (user intent honored)

**Real-world example:**

```
Query: "Domain Design"

Results by score:
1. "...Domain Design..."           → Score: 0.698 (exact match, boosted)
2. "...Domain-driven Design..."    → Score: 0.136 (1 word gap)
3. "...Domain Effective Design..." → Score: 0.136 (1 word gap)
4. "...Domain Very Effective Design..." → Score: 0.086 (2 word gap)
```

**Configuration defaults:**
- `DEFAULT_PROXIMITY_SLOP = 3`
- `DEFAULT_EXACT_BOOST = 2.0f`

### 3.4 Adaptive Prefix Query Scoring

Prefix queries use real BM25 scoring when the prefix is specific enough:

- **>= 4 characters** (`vertrag*`, `design*`): BM25 scoring via `TopTermsBlendedFreqScoringRewrite(50)`. Shorter, more frequent terms rank higher.
- **< 4 characters** (`ver*`, `de*`): Constant score for performance (too many matching terms)

**Example:**

```
Query: vertrag*  (>= 4 chars, BM25 scoring)
  1. "vertrag"           → Score: 2.8 (short, frequent)
  2. "vertrags"          → Score: 1.9
  3. "vertragsklausel"   → Score: 1.2 (long, rare)

Query: ver*  (< 4 chars, constant score)
  1. "verarbeiten"  → Score: 1.0
  2. "vertrag"      → Score: 1.0
  3. "vereinfachen" → Score: 1.0
```

**Configuration:**
- `MIN_PREFIX_LENGTH_FOR_SCORING = 4`
- `MAX_SCORED_PREFIX_TERMS = 50`

### 3.5 German Compound Word Search

German compound words can be found using wildcard patterns:

- `*vertrag` → finds Arbeitsvertrag, Kaufvertrag, Mietvertrag (via reversed field)
- `vertrag*` → finds Vertragsbedingungen, Vertragsklausel (trailing wildcard)
- `*vertrag*` → finds both (OR of forward + reversed)

Leading wildcards are optimized via `content_reversed`, executing as fast as trailing wildcards.

### 3.6 Stemmed Query Boost Calculation

Dynamic boost formula: `boost = 0.3 + 0.7 * (langCount / totalDocs)`

**Example with 1000 docs (800 German, 150 English, 50 other):**

```
content          → boost 2.0 (always fixed)
content_lemma_de → boost 0.3 + 0.7 * (800/1000) = 0.86
content_lemma_en → boost 0.3 + 0.7 * (150/1000) = 0.405
content_translit_de → boost 0.5 (always fixed)
```

**With explicit `language eq "de"` filter:**

```
content          → boost 2.0
content_lemma_de → boost 1.0 (only this language included)
content_translit_de → boost 0.5
```

---

## 4. Scoring and Ranking

The multi-field approach affects ranking as follows:

**Exact match on content**: Hits both the `content` field (boost 2.0) AND lemma field(s) → highest combined score

**Lemmatized match only**: Document found only via lemma field (e.g., searching "Vertrag" finds "Vertrages") → lower score (lemma field boost only)

**Transliteration match only**: Document found via `content_translit_de` (e.g., "Mueller" finds "Müller") → lowest boost (0.5)

**Highlighting**: Always performed on the unstemmed `content` field using the highlight query. Documents matched only via stemmed fields get a fallback passage (no bold markers).

---

## 5. Schema Version Management

The server tracks the index schema version to detect when changes require reindexing:

- `DocumentIndexer.SCHEMA_VERSION` (currently 8) is the single source of truth
- Stored in Lucene commit user data (`schema_version` and `software_version` keys)
- On startup, `LuceneIndexService.init()` detects mismatches → triggers automatic re-index
- Any change to analyzer chains, field additions/removals, or indexing options requires a SCHEMA_VERSION bump

**What triggers a schema version bump:**
- Adding or removing indexed fields
- Changing field analyzers
- Modifying field indexing options (stored, term vectors, etc.)

**Version information**: Use the `getIndexStats` MCP tool to see the current schema version, software version, and build timestamp.

---

## Technical Notes

### Why Multiple Shadow Fields?

Each shadow field optimizes a specific search pattern:

- `content`: Exact matching and highlighting
- `content_reversed`: Leading wildcard optimization (`*vertrag`)
- `content_lemma_de` / `content_lemma_en`: Morphological variant matching (run→ran, Haus→Häuser)
- `content_translit_de`: ASCII digraph to umlaut mapping (Mueller→Müller)

This multi-field approach allows combining multiple search strategies in a single query without sacrificing precision or performance.

### Why Store Tokens Only in `content`?

Shadow fields are `Store.NO` because:
- They're used only for searching, never for highlighting
- Reduces index size (no duplicate stored content)
- Highlighting uses the original `content` field for accurate term position markup

### Why Separate Index/Query Analyzers for OpenNLP?

- **Index time**: Long document texts benefit from sentence-aware tokenization for accurate POS tagging
- **Query time**: Short query strings don't need sentence detection; StandardTokenizer is faster
- Both modes produce compatible tokens for matching, but with different tokenization strategies

### Performance Characteristics

**Indexing throughput** (approximate, 10-page PDF):
- Content extraction (Tika): ~100-200ms
- UnicodeNormalizingAnalyzer: ~5-10ms
- ReverseUnicodeNormalizingAnalyzer: ~5-10ms
- OpenNLPLemmatizingAnalyzer (sentence-aware): ~100-500ms
- GermanTransliteratingAnalyzer: ~5-10ms

**Query processing** (approximate):
- Query parsing: <1ms
- Leading wildcard rewriting: <1ms
- Stemmed query building: 1-5ms
- Search execution: 10-100ms (varies with index size and query complexity)
- Highlighting: 5-50ms (varies with passage count and document length)

---

For implementation details, see:
- `DocumentIndexer.java` - Field schema and indexing logic
- `LuceneIndexService.java` - Query processing and search execution
- `ProximityExpandingQueryParser.java` - Automatic phrase expansion
- Analyzer implementations in `src/main/java/com/bitplan/lucene/analyzer/`
