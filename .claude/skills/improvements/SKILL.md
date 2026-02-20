---
name: improvements
description: Roadmap and analysis of potential improvements, design trade-offs, and feature priorities. Use when planning new features, evaluating what to build next, or understanding why certain approaches were chosen or rejected.
allowed-tools: Read, Glob, Grep
---

# Improvement Roadmap

## Core Design Principle: Client-Side Intelligence

The fundamental architecture decision is that **semantic understanding lives in the MCP client (the AI), not in the server**. The server is a fast, precise, lexical retrieval engine. The AI compensates for what the server doesn't do.

This means:
- The AI generates synonym expansions via OR queries — no synonym files needed
- The AI handles multilingual query formulation — per-language stemmed shadow fields complement this (see candidate E)
- The AI iterates on results (search, read, refine) — no "smart" ranking needed
- The server stays simple, fast, dependency-light, and debuggable

**Any proposed improvement must be evaluated against this principle.** If the AI client can do it, the server shouldn't duplicate it.

---

## Current Strengths (Do Not Regress)

### Excellent — Competitive Advantages
1. **MCP-native architecture** — AI client as the semantic layer is genuinely more powerful than static synonym/stemming configuration
2. **Structured passage output** — `score`, `matchedTerms`, `termCoverage`, `position` per passage is optimized for LLM consumption
3. **Leading wildcard optimization** — `content_reversed` field for efficient `*vertrag`-style queries (German compound words)
4. **Incremental crawling** — 4-way reconciliation diff (DELETE/ADD/UPDATE/SKIP) is production-grade
5. **Operational polish** — Schema version management, auto-reindex, OS-native notifications, NRT adaptive refresh, MCP App admin UI, lock file recovery

### Solid — Good Foundation
6. **Tika extraction pipeline** — Thorough content normalization (HTML entities, URL encoding, NFKC, ligature expansion)
7. **Faceted search** — SortedSetDocValues facets with AI-guided drill-down workflow
8. **Crawler lifecycle** — Pause/resume, directory watching, batch processing, config persistence

---

## Improvement Candidates

### Tier 1: High Impact — Amplify the Existing Architecture

These improve the **AI client's ability to use the server effectively** without duplicating semantic logic.

#### 1. Structured Multi-Filter Support
**Status**: Done
**Effort**: Medium
**Impact**: High

Implemented `filters[]` array with operators (`eq`, `in`, `not`, `not_in`, `range`), DrillSideways faceting for faceted fields, ISO-8601 date parsing, `activeFilters` with `matchCount` in response, `dateFieldHints` in `getIndexStats`, and backward compatibility with legacy `filterField`/`filterValue`. Also addresses item 6 (Date-Friendly Query Parameters) via the `range` operator with ISO-8601 support.

#### 2. Index Observability Tools
**Status**: Done
**Effort**: Low-Medium
**Impact**: High

Implemented two read-only MCP tools for index vocabulary exploration:

**`suggestTerms`** — Prefix-based term completion with doc frequency sorting. Auto-lowercases prefix for analyzed fields (using `LOWERCASE_WILDCARD_FIELDS`). Cross-segment aggregation via HashMap merge. Point fields rejected with helpful error message.

**`getTopTerms`** — Full term enumeration sorted by frequency. Warning for fields with >100K unique terms. Works with both analyzed and StringFields.

Both tools: empty results for nonexistent fields (graceful), validation rejects `LONG_POINT_FIELDS`, new `IndexObservabilityIntegrationTest` with 20 tests covering prefix matching, case handling, limits, empty index, cross-segment aggregation.

#### 3. Document Chunking for Long Documents
**Status**: Not started
**Effort**: High
**Impact**: High

Currently each file = one Lucene document. The highlighter reads only 10,000 chars (`withMaxLength`), so passages from the second half of long PDFs are never found.

**Approach**: Split documents into overlapping chunks (e.g., ~2000 chars with 200-char overlap) at paragraph boundaries. Each chunk is a Lucene document linked to its parent by `file_path`. Search returns chunk-level passages; the AI sees which section of the document is relevant.

**Considerations**:
- Increases document count significantly (a 100-page PDF might produce 200+ chunks)
- Facets and metadata should be duplicated across chunks (or stored on a parent doc)
- `getDocumentDetails` would need to reconstruct the full document from chunks
- Schema version bump required
- This is also a prerequisite if vector search is ever added (embeddings need chunk-level granularity)

**Key files**: `DocumentIndexer.java`, `LuceneIndexService.search()`, `DocumentCrawlerService`

#### 4. Sort Options
**Status**: ✅ Done
**Effort**: Low
**Impact**: Medium-High

Implemented sorting capability for search results by metadata fields.

**What was implemented:**
- Added `sortBy` parameter: `_score` (default), `modified_date`, `created_date`, `file_size`
- Added `sortOrder` parameter: `asc` or `desc` (defaults: desc for dates/size, desc for score)
- Validation for invalid sort fields and orders
- Secondary sort by score for tie-breaking when sorting by metadata
- Comprehensive documentation in README.md and query-syntax resource

**Examples:**
```json
// Most recently modified
{"query": "contract", "sortBy": "modified_date", "sortOrder": "desc"}

// Oldest documents
{"query": "*", "sortBy": "created_date", "sortOrder": "asc"}

// Smallest files
{"query": "summary", "sortBy": "file_size", "sortOrder": "asc"}
```

**Key decisions:**
- Default sort remains by relevance score (no breaking changes)
- Always compute scores even when sorting by metadata (needed for highlighting and tie-breaking)
- Use `SortedNumericSortField` for numeric/date fields (DocValues)
- Clear error messages for invalid parameters

**Key files**: `SearchRequest.java`, `LuceneIndexService.search()`, README.md, query-syntax resource

### Tier 2: Medium Impact — Expand Capabilities

#### 5. "More Like This" / Similar Document Search
**Status**: Not started
**Effort**: Medium
**Impact**: Medium

Lucene has `MoreLikeThis` built in. A `findSimilar` tool that takes a `file_path` and returns related documents would be useful for the AI ("find documents similar to this contract").

**Key files**: New tool in `LuceneSearchTools.java`, new method in `LuceneIndexService.java`

#### 6. Date-Friendly Query Parameters
**Status**: Done (addressed by Structured Multi-Filter Support)
**Effort**: Low
**Impact**: Medium

Implemented as part of the `filters[]` array with `range` operator and ISO-8601 date parsing. The `getIndexStats` tool now also returns `dateFieldHints` with min/max dates.

#### 7. OCR Support for Scanned PDFs
**Status**: Not started
**Effort**: Medium-High
**Impact**: Medium (depends on user's document mix)

Tika supports Tesseract OCR. Scanned PDFs are a blind spot — the content extractor returns empty text. Even basic OCR would dramatically expand coverage for users with scanned documents.

**Considerations**:
- Tesseract must be installed on the host system (external dependency)
- OCR is slow — may need async processing or a separate queue
- Should be opt-in via configuration (`ocr-enabled: true`)
- Language hints from filename or metadata can improve OCR accuracy

**Key files**: `FileContentExtractor.java`, `application.yaml`

#### 8. Expanded File Format Support
**Status**: Done
**Effort**: Low
**Impact**: Low-Medium

Added default support for 8 new file formats (all handled natively by Tika 3.2.3, no new dependencies):
- `.eml`, `.msg` (emails)
- `.md`, `.rst` (markup)
- `.html`, `.htm` (web pages)
- `.rtf` (rich text)
- `.epub` (ebooks)

Includes include-patterns in `application.yaml` + `ApplicationConfig.java`, test document generators, parameterized extraction tests, and README documentation.

**Remaining**: `.csv` (spreadsheets as text) could still be added.

**Key files**: `application.yaml`, `ApplicationConfig.java`, `TestDocumentGenerator.java`, `FileContentExtractorTest.java`, README.md

#### 8.5. Markdown Bold Highlighting for Search Results
**Status**: ✅ Done
**Effort**: Low
**Impact**: Medium

Changed search result highlighting from HTML `<em>` tags to markdown `**bold**` syntax for proper rendering in Claude Desktop.

**What was implemented:**
- Updated `IndividualPassageFormatter` to use `**` instead of `<em>` tags
- Modified `extractMatchedTerms()` in `LuceneIndexService` to parse markdown bold markers
- Updated all documentation and test expectations
- Highlighted terms now render as bold in markdown-aware interfaces

**Rationale:**
- HTML tags show as literal text in Claude Desktop markdown rendering
- Markdown `**bold**` syntax renders properly as visual emphasis
- More LLM-friendly and conventional for markdown-based interfaces
- Aligns with how Claude Desktop displays content

**Key files:** `IndividualPassageFormatter.java`, `LuceneIndexService.java`, `Passage.java`, test files

**Key decisions:**
- Chose markdown over HTML for native Claude Desktop rendering
- Changed from Lucene's default `<b>` tags to markdown `**`
- Maintained all existing functionality (matched term extraction, coverage calculation)

#### 9. Query Profiling and Debugging Tool
**Status**: ✅ Done
**Effort**: High
**Impact**: High

Implemented `profileQuery` MCP tool with multi-level analysis optimized for LLM/human readability.

**What it provides**:
- **Level 1 (Fast, always)**: Query structure analysis, term statistics (IDF, rarity), cost estimates, query rewrites
- **Level 2 (Opt-in)**: Filter impact analysis with selectivity metrics (requires N+1 queries)
- **Level 3 (Opt-in)**: Document scoring explanations parsed from Lucene's Explanation API into version-independent semantic structures
- **Level 4 (Opt-in)**: Facet cost analysis

**Key decisions**:
- Made expensive operations opt-in (default: fast ~5-10ms analysis)
- Parsed Lucene's `Explanation.toString()` into structured DTOs instead of exposing raw format (version-independent)
- Added human-readable categorizations ("very common term", "high selectivity filter")
- Generated actionable optimization recommendations
- Focused on semantic structure over Lucene internals

**What it enables**:
- Understanding why queries return certain results
- Debugging scoring and ranking
- Identifying which terms/filters are most impactful
- Query optimization without deep Lucene knowledge
- LLMs can now help users tune their queries based on profiling data

**Files**: 15 new DTOs in `mcp/dto/`, updates to `LuceneSearchTools.java` and `LuceneIndexService.java`

**Known limitations** (from Lucene's Explanation API):
1. **Cannot explain non-matches**: Only explains documents that *did* match; can't debug why expected documents didn't appear
2. **Automaton internals opaque**: Wildcard/regex queries compile to finite automata; can't trace which substring matched `*vertrag*` in "Arbeitsvertrag"
3. **No passage-level explanation**: UnifiedHighlighter computes passage scores internally but doesn't expose explanation
4. **Filter impact requires measurement**: Must run queries incrementally to measure filter selectivity (Lucene's cost() API is insufficient)
5. **Cross-document comparison manual**: Each explanation is independent; requires custom logic to compare "why doc A > doc B"

**Future enhancements** (see Tier 3 below):
- "Why didn't this match?" tool (explainNonMatch)
- Query comparison tool (compareQueries)
- Passage-level scoring explanation
- Enhanced optimization suggestions

**Key files**: `ProfileQueryRequest.java`, `ProfileQueryResponse.java`, `QueryAnalysis.java`, `DocumentScoringExplanation.java`, and 11 other DTOs

#### 10. Context Pollution Reduction via MCP Resources
**Status**: ✅ Done
**Effort**: Medium
**Impact**: High

Implemented MCP Resources pattern to move verbose documentation out of tool descriptions, reducing initial context load by ~70%.

**What was implemented**:
- Shortened tool descriptions from ~3,000 to ~900 characters (-70%)
- Shortened parameter descriptions by ~50%
- Created two comprehensive MCP Resources:
  - `lucene://docs/query-syntax` - 350+ line Lucene query syntax guide
  - `lucene://docs/profiling-guide` - 150+ line profiling analysis guide
- LLM can access detailed docs on-demand via `Read` tool

**Pattern established**:
- **Tool descriptions**: What it does, when to use it, critical warnings, reference to resource
- **Parameter descriptions**: Type and purpose only
- **MCP Resources**: Complete syntax, examples, best practices, edge cases

**Benefits**:
- Reduced MCP handshake context by ~73%
- Maintained all essential information
- Better documentation organization
- LLM-friendly on-demand details

**Key files**: `LuceneSearchTools.java` (resource specifications and handlers), all DTO files (shortened descriptions)

**Future applications**:
- Create resources for crawler configuration guide
- Create resource for index field schema documentation
- Create resource for troubleshooting common issues
- Pattern should be used for all future complex tools

#### 10.5. Search Result Visualization Enhancements
**Status**: Not started
**Effort**: Medium
**Impact**: Medium-High

Enhance visual presentation of search results in Claude Desktop through rich markdown formatting and optional document previews.

**Proposed enhancements:**

**Tier 1 (High Value, Low Cost) - Implement First:**
1. **Rich Markdown Formatting** - Format search results with proper markdown structure:
   - Headers for document titles
   - Code blocks for file paths
   - Bold/italic for metadata
   - Structured result cards with visual hierarchy
   - Syntax-highlighted code snippets for code files

2. **Structured Result Cards** - Clear visual layout:
   ```
   ### 📄 Document Title
   **Path:** `/path/to/file.pdf`
   **Modified:** 2024-01-15 | **Size:** 2.3 MB

   **Preview:**
   The **budget** for Q4 2024 was exceeded...
   ```

3. **Better Snippets** - Enhanced passage formatting:
   - Already using markdown bold for matched terms ✅
   - Context around matches
   - Syntax highlighting for code

**Tier 2 (Medium Value, Medium Cost) - Optional:**
4. **File Type Icons** - Embed tiny base64 icons (5-10 common types, ~50 KB total)
5. **Search Statistics** - Formatted facet counts, distribution charts as text

**Tier 3 (High Value, High Cost) - Make Optional:**
6. **Document Thumbnails** - MCP supports base64-encoded images in responses
   - PDF first page preview
   - Image file thumbnails
   - Office document previews
   - **Constraint:** 1 MB maximum per image (MCP limit)
   - **Performance:** Generation during indexing vs. on-demand
   - **Configuration:** Make opt-in via `thumbnails.enabled: false` (default)

**MCP Support:**
MCP tool responses support multiple content types:
- `{"type": "text", "text": "..."}` - Markdown-formatted text
- `{"type": "image", "data": "base64...", "mimeType": "image/jpeg"}` - Images

**Rationale:**
- Claude Desktop renders markdown natively - leverage it
- Visual hierarchy improves result scanning
- Thumbnails provide immediate document recognition
- All enhancements are additive (no breaking changes)

**Key files:** `LuceneSearchTools.java`, new `ThumbnailGenerator.java` (if implementing thumbnails), `SearchResponse.java`

**Implementation priority:** Start with Tier 1 (zero cost, immediate value), add thumbnails only if users request it.

#### 11. Automatic Phrase Proximity Expansion
**Status**: ✅ Done
**Effort**: Low-Medium
**Impact**: Medium-High

Implemented automatic expansion of exact phrase queries to include proximity matching, improving recall while maintaining precision through differential scoring.

**What was implemented:**
- Created `ProximityExpandingQueryParser` extending Lucene's QueryParser
- Automatically expands multi-word exact phrases: `"Domain Design"` → `("Domain Design")^2.0 OR ("Domain Design"~3)`
- Exact matches score highest (2.0x boost), proximity matches score lower
- User-specified slop honored (no expansion if user already used ~N)
- Single-word phrases not expanded (no benefit)
- 10 unit tests + 7 integration tests
- All 359 tests pass (no regressions)

**Rationale:**
- Users/Claude don't need to know slop syntax
- Finds "Domain-driven Design" when searching "Domain Design"
- Exact matches still rank highest via boost (typically 2-3x higher score)
- Deterministic, predictable behavior
- No external dependencies (pure Lucene)
- Solves the original use case without adding Solr ComplexPhraseQueryParser

**Real-world example:**
```
Query: "Domain Design"
Results by score:
1. "Domain Design" (exact) - Score: 0.6981 ⭐⭐⭐
2. "Domain-driven Design" - Score: 0.1360 ⭐⭐
3. "Domain Effective Design" - Score: 0.1360 ⭐⭐
4. "Domain Very Effective Design" - Score: 0.0864 ⭐
```

**Configuration:**
- Default slop: 3 words (configurable via constructor)
- Default exact boost: 2.0x (configurable via constructor)
- Future: Could make configurable via application.yaml

**Key decisions:**
- Only multi-word phrases get expanded (single words have no benefit)
- User-specified slop always honored (backward compatible)
- Boost ensures exact matches always rank highest
- Slop of 3 balances recall (find variations) vs precision (avoid noise)

**Key files:** `ProximityExpandingQueryParser.java`, `LuceneIndexService.java`, `ProximityExpandingQueryParserTest.java`, `AutomaticPhraseExpansionIntegrationTest.java`

**Alternative considered:** Solr ComplexPhraseQueryParser - rejected due to Solr dependency, less control over scoring, and SpanQuery limitations with stopwords

---

## Missing Features & Gaps

### Critical Gaps (Should be added to Tier 1)

#### A. Index Backup & Restore
**Status**: Not implemented
**Priority**: High
**Impact**: Critical for production use

Currently no way to backup or restore the index. Users risk data loss on corruption or system failure.

**Proposed tools**:
- `backupIndex` - Create snapshot of index to specified location
- `restoreIndex` - Restore index from backup
- `listBackups` - List available backups with timestamps

**Considerations**:
- Index must be locked during backup
- Incremental backups vs full snapshots
- Backup verification/integrity checks
- Storage location configuration

**Key files**: New tools in `LuceneSearchTools.java`, new backup service

#### B. Duplicate Document Detection
**Status**: Not implemented
**Priority**: Medium-High
**Impact**: High

Index has `content_hash` field but no tools to find duplicate documents.

**Proposed tool**: `findDuplicates`
```json
{
  "method": "content_hash",  // or "fuzzy_content", "title_similarity"
  "threshold": 0.95,
  "groupBy": "content_hash"
}
```

**Returns groups of duplicate documents** with suggestions for which to keep/remove.

**Use cases**:
- Cleanup after indexing multiple document sources
- Detect near-duplicates (different versions of same document)
- Index optimization (remove redundant documents)

**Key files**: New tool, new analysis method in `LuceneIndexService.java`

### Tier 2 Additions (Medium Impact)

#### C. Batch/Bulk Operations
**Status**: Not implemented
**Priority**: Medium
**Impact**: Medium

No support for batch operations - each operation requires separate tool call.

**Proposed features**:
- `batchSearch` - Run multiple queries in one call, return combined results
- `batchProfileQuery` - Profile multiple queries for comparison
- `batchGetDocuments` - Retrieve multiple documents by file path

**Benefits**:
- Reduced MCP round-trips
- More efficient for comparative analysis
- Better for reporting/export scenarios

#### D. Export & Report Generation
**Status**: Not implemented
**Priority**: Medium
**Impact**: Medium

No way to export search results, profiling data, or statistics for external use.

**Proposed tool**: `exportResults`
```json
{
  "source": "last_search",  // or "profile_results", "index_stats"
  "format": "csv",          // or "json", "markdown"
  "fields": ["file_path", "score", "language"],
  "destination": "/path/to/export.csv"
}
```

**Use cases**:
- Generate reports for stakeholders
- Export for external analysis (Excel, BI tools)
- Archive search results

#### E. Multi-Language Snowball Stemming (Per-Language Shadow Fields)
**Status**: ✅ **DONE** — implemented in SCHEMA_VERSION 3
**Priority**: Medium-High
**Impact**: High (especially for German)

**Implemented approach**: Per-language lemma shadow fields using OpenNLP (replaced original Snowball stemming plan). All documents are indexed with both German and English lemma fields (`content_lemma_de`, `content_lemma_en`) regardless of detected language. At query time, weighted OR queries combine exact matching on `content` (boost 2.0) with lemmatized matching on language-specific fields (dynamic boost based on language distribution). Exact matches always rank highest. Highlighting stays on the unstemmed `content` field.

See [PIPELINE.md](../../PIPELINE.md) for complete analyzer chain documentation, token examples, and query pipeline details.

**Key implementation decisions**:
- Dynamic boost weights derived from index language distribution: `boost = 0.3 + 0.7 * (langCount / totalDocs)`
- Language distribution cached and refreshed on NRT searcher refresh
- If explicit `language eq "xx"` filter present, only that language's lemma field is included at boost 1.0
- Highlighting unaffected (uses unstemmed `content` field)
- Documents found only via lemma fields get fallback passages (no bold markers)

#### E1b. OpenNLP Lemmatizer Token Cleanup
**Status**: Done (SCHEMA_VERSION 8)
**Effort**: Low
**Impact**: Medium — eliminates index noise, improves term observability tools

**Problem**: OpenNLPTokenizer retains punctuation as separate tokens, and German UD-GSD lemmatizer produces compound lemmas for contractions (`im` → `in+der`).

**Solution**: Added `TypeTokenFilter` (drops punctuation) and `CompoundLemmaSplittingFilter` (splits on `+`) to the lemmatizer chain.

See [PIPELINE.md](../../PIPELINE.md) for complete analyzer chain details and examples.

#### E2. Irregular Verb Stemming (Extends Snowball Stemming)
**Status**: Superseded — OpenNLP lemmatization handles irregular verbs correctly
**Priority**: N/A
**Impact**: N/A (problem solved by lemmatizer)
**Depends on**: Candidate E (Snowball stemming — replaced by OpenNLP lemmatization)

**Problem**: Snowball is an algorithmic suffix-stripper. It handles regular morphology well but fails on irregular forms that involve vowel changes (Ablaut), suppletion, or prefix patterns:

*English gaps:*
- `ran` → "ran" vs `run`/`running` → "run" (vowel change)
- `went` → "went" vs `go`/`going` → "go" (suppletion)
- `saw` → "saw" vs `see`/`seen` → "see" (vowel change)
- `wrote` → "wrote" vs `write`/`written` → "write"
- `analysis` → "analysi" vs `analyses` → "analys" (confirmed in P/R tests — different stems)

*German gaps (more severe due to Ablaut + ge- prefix):*
- `ging` → "ging" vs `gehen`/`gegangen` → different stems
- `fuhr` → "fuhr" vs `fahren`/`gefahren` → different stems
- `sprach` → "sprach" vs `sprechen`/`gesprochen` → different stems
- `war` → "war" vs `sein`/`gewesen` → completely unrelated forms (suppletion)
- `lief` → "lief" vs `laufen`/`gelaufen` → different stems

**Current P/R state**: P=0.975, R=1.000, F1=0.975 on the 45-doc test corpus. R=1.000 because the test corpus is small and queries are carefully chosen. In a real corpus, verb-based searches like "who ran the project" or "was entschieden wurde" would hit these gaps.

---

**Approach A: Hunspell Dictionary Stemming**

Use Lucene's built-in `HunspellStemFilter` with `.dic`/`.aff` dictionary files. Dictionary-based stemming correctly maps irregular forms to their lemma because it uses lookup tables rather than suffix rules.

*Implementation*: Additional shadow fields using HunspellStemFilter. See [PIPELINE.md](../../PIPELINE.md) for analyzer chain details.

*Pros:*
- Handles all irregulars via dictionary lookup — no manual maintenance of verb lists
- Well-maintained dictionaries exist (LibreOffice/SCOWL communities)
- Lucene-native — `HunspellStemFilter` is in `lucene-analysis-common` (already a dependency)
- Covers not just irregular verbs but also irregular noun plurals, adjective inflections, etc.
- Would also improve Snowball's edge cases (e.g., `analysis`/`analyses`)

*Cons:*
- **License problem for German**: The de_DE Hunspell dictionary ([igerman98](https://www.j3e.de/ispell/igerman98/)) is **GPL-2.0 OR GPL-3.0**. Per the [ASF 3rd Party License Policy](https://www.apache.org/legal/resolved.html), GPL is **Category X — prohibited** for bundling in Apache 2.0 projects. This applies to both code and data files.
- English dictionary (SCOWL) is **MIT + BSD** — Apache Category A, fully compatible.
- Slower than Snowball (dictionary lookups vs rule application)
- Dictionary files add ~5-10 MB per language
- Can produce multiple stems per token (ambiguity: "saw" → "see" AND "saw")
- Dictionary updates need tracking

*License workaround options:*
1. **User-provided dictionaries**: Ship without dictionaries, load from config path (`hunspell-dictionaries-path` in application.yaml). User downloads and places dictionaries themselves. GPL applies to the dictionary files, not to code that reads them.
2. **English only**: Bundle only the MIT/BSD English dictionary; German stays Snowball-only.
3. **Wait for re-licensing**: Monitor igerman98 — if it ever moves to LGPL or more permissive license.

*If user-provided dictionaries approach:*
```yaml
# application.yaml
hunspell:
  enabled: false  # opt-in
  dictionaries-path: ~/.mcplucene/dictionaries/
  # User places de_DE.dic, de_DE.aff, en_US.dic, en_US.aff there
```

---

**Approach B: StemmerOverrideFilter (Explicit Mapping)**

Lucene's `StemmerOverrideFilter` runs before Snowball and provides a hard override map. Only irregular forms need entries; regular forms fall through to Snowball unchanged.

*Implementation*: Insert `StemmerOverrideFilter` into the existing `StemmedUnicodeNormalizingAnalyzer` chain, before `SnowballFilter`:

```
StandardTokenizer → LowerCaseFilter → ICUFoldingFilter → StemmerOverrideFilter(map) → SnowballFilter
```

*Override map examples:*
```java
// English (~200 irregular verbs, ~5-6 forms each ≈ ~1000 entries)
"ran" → "run", "went" → "go", "gone" → "go",
"saw" → "see", "seen" → "see",
"wrote" → "write", "written" → "write",
"paid" → "pay", "analyses" → "analysis", ...

// German (~170 strong/mixed verbs, ~5-6 forms each ≈ ~1000 entries)
"ging" → "gehen", "gegangen" → "gehen",
"fuhr" → "fahren", "gefahren" → "fahren",
"sprach" → "sprechen", "gesprochen" → "sprechen",
"war" → "sein", "gewesen" → "sein",
"lief" → "laufen", "gelaufen" → "laufen", ...
```

*Pros:*
- **No license issues** — override maps are our own code, Apache 2.0
- Precise — no false conflations beyond what we explicitly define
- Composable — sits in front of existing Snowball chain, no new fields needed
- Tiny overhead (HashMap lookup per token)
- No new dependencies
- Easy to test — each override is a deterministic mapping

*Cons:*
- Manual maintenance of override maps (~2000 entries total for DE+EN)
- Incomplete coverage — only catches forms we enumerate
- Doesn't scale to new languages without per-language work
- The "long tail" of irregulars is large: English ~200 verbs × ~5 forms, German ~170 verbs × ~6 forms
- Doesn't cover irregular noun plurals or adjective forms (only verbs, unless we add those too)
- Override map targets must match Snowball's output stem for regular words (e.g., "went" → "go", but Snowball stems "go" to "go", "goes" to "goe" — we'd need "went" → "go" AND update "goes" → "go" to normalize Snowball's irregular output)

*Key consideration*: The override target must be the Snowball stem of the base form, not the dictionary lemma. E.g., if Snowball stems "house" → "hous", then override "houses" → "hous" (not "house"). Since `StemmerOverrideFilter` runs BEFORE `SnowballFilter`, overridden tokens skip Snowball entirely — so the target must be the final desired stem. This means the override map is Snowball-version-dependent.

---

**Approach C: OpenNLP Lemmatizer (Lucene-integrated, Apache 2.0)** — IMPLEMENTED

Lucene's `OpenNLPLemmatizerFilter` provides true lemmatization via trained models. This approach was chosen and implemented.

*Implementation*: Shadow fields `content_lemma_de` / `content_lemma_en` using OpenNLP pipeline with sentence detection, POS tagging, and lemmatization. See [PIPELINE.md](../../PIPELINE.md) for complete analyzer chain documentation and token examples.

**Critical difference from Snowball**: Requires complete analyzer chain with OpenNLPTokenizer (sentence detection + tokenization) + POS tagging before lemmatization.

*Two modes available:*
1. **Dictionary-only**: `DictionaryLemmatizer` — HashMap lookup from `word[tab]postag[tab]lemma` file. Fast (O(1) per token), but requires POS tags and a comprehensive dictionary file.
2. **MaxEnt model**: `LemmatizerME` — statistical model (Maximum Entropy). Handles unseen words. Can be combined with dictionary (dictionary tried first, model for OOV fallback).

*Available models (all Apache 2.0):*

| Model | Source | Size | Notes |
|-------|--------|------|-------|
| EN lemmatizer | [OpenNLP models](https://opennlp.apache.org/models.html) | ~1-5 MB (est.) | `opennlp-en-ud-ewt-lemmas-1.3-2.5.4.bin` |
| EN POS tagger | OpenNLP models | ~5-15 MB (est.) | `opennlp-en-ud-ewt-pos-1.3-2.5.4.bin` |
| EN sentence detector | OpenNLP models | ~1 MB (est.) | `opennlp-en-ud-ewt-sentence-1.3-2.5.4.bin` |
| EN tokenizer | OpenNLP models | ~1 MB (est.) | `opennlp-en-ud-ewt-tokens-1.3-2.5.4.bin` |
| DE lemmatizer (small) | [DE-Lemma](https://github.com/mawiesne/DE-Lemma) | 861 KB | UD-GSD based, Apache 2.0 |
| DE lemmatizer (large) | DE-Lemma | 14 MB | UD-HDT based, Apache 2.0 |
| DE lemmatizer (huge) | DE-Lemma | 131 MB | Wikipedia-trained (36.1M sentences), Apache 2.0 |
| DE POS tagger | OpenNLP models | ~5-15 MB (est.) | `opennlp-de-ud-gsd-pos-1.3-2.5.4.bin` |
| DE sentence detector | OpenNLP models | ~1 MB (est.) | `opennlp-de-ud-gsd-sentence-1.3-2.5.4.bin` |
| DE tokenizer | OpenNLP models | ~1 MB (est.) | `opennlp-de-ud-gsd-tokens-1.3-2.5.4.bin` |

*Memory and performance analysis:*

**Startup impact:**
- Each language requires 4 models loaded into memory: sentence detector, tokenizer, POS tagger, lemmatizer
- Estimated heap per language: ~20-50 MB for small/medium models, ~150+ MB for DE Wikipedia model
- Models are loaded once at startup and shared across threads (MaxentModel is thread-safe)
- Current server startup: ~2 seconds. Model loading could add 1-3 seconds per language
- **Total memory impact**: ~40-100 MB additional heap for both languages with medium-sized models. This is significant for a server that currently runs lean (~50-100 MB heap)

**Index-time impact (critical path):**
- POS tagging is the bottleneck: MaxEnt model inference per token, not just a lookup
- Estimated throughput: ~10,000-50,000 tokens/second (vs ~500,000+ for Snowball)
- For a 10-page PDF (~5,000 tokens): Snowball ~10ms, OpenNLP ~100-500ms
- **Full reindex** of 10,000 documents: could add 15-60 minutes to crawl time
- POS tagging accuracy affects lemmatization: wrong POS tag → wrong lemma

**Query-time impact:**
- Query strings are short (typically 1-5 tokens) — OpenNLP pipeline overhead is negligible per query
- But the analyzer must be invoked for each stemmed field query (same as current Snowball approach)
- Estimated: ~1-5ms additional per query (acceptable)

**Index size impact:**
- Lemmatized tokens are typically shorter than or equal to surface forms (same as stemming)
- No significant additional index size vs Snowball shadow fields

*Pros:*
- **All Apache 2.0** — library, Lucene integration, EN models, DE models (DE-Lemma)
- True lemmatization: handles ALL irregulars (verbs, nouns, adjectives) correctly
- Handles unseen words via MaxEnt model (not limited to dictionary entries)
- German models trained on 36.1M sentences (Wikipedia) — extensive vocabulary coverage
- Lucene-native `TokenFilter` — same integration pattern as Snowball
- Dictionary + model hybrid: fast lookup for known words, statistical fallback for unknown

*Cons:*
- **Heavy pipeline**: requires sentence detector + tokenizer + POS tagger + lemmatizer (4 models per language vs 0 for Snowball)
- **Memory overhead**: ~40-100 MB additional heap for both languages (doubles current footprint)
- **Index-time performance**: 10-50x slower than Snowball per token due to POS tagging
- **Startup time**: +1-3 seconds for model loading
- **New dependency**: `lucene-analysis-opennlp` module + model files bundled or downloaded
- **Model files**: must be bundled in JAR or downloaded on first run (~20-60 MB total for medium models)
- **POS accuracy dependency**: lemmatization quality depends on POS tagging accuracy. Wrong POS → wrong lemma (e.g., "saw" tagged as NN → "saw", tagged as VBD → "see")
- **Complexity**: separate complete analyzer (can't just add a filter to existing chain), language detection must route to correct analyzer
- Contradicts "fast, simple server" principle more than other approaches

---

**Comparison**

| Aspect | Hunspell | StemmerOverrideFilter | OpenNLP Lemmatizer |
|--------|----------|----------------------|--------------------|
| Irregular verb coverage | Complete (dictionary) | ~90% of common verbs (manual) | Complete (trained model) |
| Irregular noun/adj coverage | Yes | Only if manually added | Yes |
| German license | **GPL — blocked** | Apache 2.0 (our code) | **Apache 2.0** |
| English license | MIT/BSD — OK | Apache 2.0 (our code) | **Apache 2.0** |
| Maintenance burden | Dictionary updates (community) | Manual map curation | Model updates (community) |
| Performance (index time) | Medium (dict lookup) | Minimal (HashMap) | **Slow (10-50x vs Snowball)** |
| Performance (query time) | Fast | Fastest | Fast (short queries) |
| Memory overhead | ~5-10 MB/lang (dict files) | None | **~20-50 MB/lang (models)** |
| Startup impact | Minimal | None | **+1-3 seconds** |
| New dependencies | Dictionary files only | None | `lucene-analysis-opennlp` + 4 models/lang |
| False conflations | Possible (ambiguous words) | Only what we define | POS-dependent (mostly correct) |
| Complexity | Low (single filter) | Low (single filter) | **High (full NLP pipeline)** |
| Effort | Medium | Medium | High |

**Recommendation**: Start with **Approach B (StemmerOverrideFilter)** for both languages — no license issues, no dependencies, minimal performance impact, precise control. Cover the ~50 most common irregular verbs per language first (~90% of practical recall gap). The AI client handles the remaining long tail through query expansion.

**If broader coverage is needed later**: **Approach C (OpenNLP)** is the strongest fully-Apache-2.0 option for complete irregular form handling. The memory and performance costs are significant but may be acceptable if the server handles large corpora where irregular verb recall matters. Consider making it opt-in via configuration (`lemmatizer.enabled: true`, `lemmatizer.engine: opennlp`) with model files loaded on demand.

**English-only Hunspell (Approach A)** remains viable for English specifically (MIT/BSD license), with user-provided dictionaries for German as a power-user option.

**Client-side complementary approach**: Regardless of server-side choice, the AI client can always expand irregular forms via OR queries ("ran OR run OR running"). This is already the approach for synonyms and works well for the irregular verbs that server-side stemming doesn't cover.

---

#### OpenNLP as a Platform Investment: NLP-vs-LLM Boundary Analysis

**Note:** This analysis was written for server deployment scenarios. For the **desktop/Claude Desktop use case**, most OpenNLP features beyond basic lemmatization are rejected due to resource constraints (see "Explicitly Rejected" section). The architectural principle remains valid, but the desktop context shifts the boundary heavily toward client-side LLM processing.

If the OpenNLP pipeline were committed to in a **server deployment** (not the current desktop use case), it becomes a **platform investment** — not just "better stemming" but a foundation for multiple features. The key insight: paying the startup/memory cost once unlocks capabilities beyond lemmatization.

**The Principle: "NLP enriches the index, LLM enriches the query"**

NLP processing at index time adds **structure to unstructured data** — this structure lives permanently in the index and benefits every future query. LLM processing at query time adds **intelligence from context** — understanding user intent, expanding queries semantically, iterating on results. These are complementary, not competing.

The boundary criterion: **If a capability produces deterministic, cacheable, document-level structure, it belongs in the NLP layer. If it requires contextual reasoning, user intent, or cross-document synthesis, it belongs in the LLM layer.**

---

**Feature 2: Sentence Detection for Document Chunking**

OpenNLP's `SentenceDetectorME` identifies sentence boundaries — a critical primitive for document chunking (Tier 1 candidate #3).

*Why it matters:*
- Current chunking would split at arbitrary character boundaries → sentences cut mid-word
- Sentence-aware chunking produces semantically coherent passages
- Better chunks → better search passages → more useful results for the AI client

*Why NLP, not LLM:*
- Sentence detection is a well-solved NLP problem (>99% accuracy for EN/DE)
- Deterministic, fast, runs once at index time
- LLM-based sentence splitting would be absurdly expensive for every document
- The model is already loaded for the lemmatization pipeline (zero marginal cost)

*Synergy with existing chunking candidate:*
- Chunking at sentence boundaries with overlap → each chunk is 5-10 sentences
- Paragraphs detected via whitespace + sentence boundaries
- Chunk metadata includes sentence count, position in document

*Impact assessment:*
- **Value**: High (prerequisite for quality chunking)
- **Effort**: Near-zero if OpenNLP pipeline exists (sentence model already loaded)
- **Memory**: 0 additional (already loaded for lemmatizer tokenizer)
- **Index time**: Included in tokenizer pass

---

**Feature 3: POS-Based Field Indexing**

With POS tags available, index only specific parts of speech into specialized fields.

*Examples:*
- `content_nouns` — only nouns, for concept-level search
- `content_verbs` — only verbs, for action-level search

*Assessment: Marginal value*
- The AI client can already focus searches via query formulation
- BM25 naturally weights content-bearing terms (nouns) higher than function words
- Additional fields increase index size without clear user benefit
- The search interface has no natural way to express "search only nouns"

*Verdict:* Not recommended. The complexity-to-benefit ratio is too low. If specific use cases emerge (e.g., domain-specific terminology extraction), reconsider.

---

**Feature 4: Noun Phrase Extraction**

Extract multi-word noun phrases (e.g., "Arbeitsvertrag", "supply chain management") as atomic units.

*Potential value:*
- Index "supply chain management" as a single term → exact phrase matching without proximity operators
- Extract compound nouns that are written as separate words in English but would be single words in German

*Assessment: Moderate value, but overlaps with LLM capabilities*
- The AI client already handles phrase queries ("supply chain management")
- OpenNLP chunker models have moderate accuracy for complex phrases
- German compound nouns are already single tokens (no extraction needed)
- English multi-word terms benefit, but the AI can formulate phrase queries

*Verdict:* Interesting but not high-priority. Could add value for automated keyword extraction / tag generation at index time.

---

**NLP-vs-LLM Boundary Summary**

| Capability | Server-Side (NLP) | Client-Side (LLM) | Winner |
|-----------|-------------------|-------------------|--------|
| **Lemmatization** | Index-time, deterministic, all docs once | Per-query OR expansion ("ran OR run") | **NLP** — scales better |
| **NER** | Index-time extraction → facets | Read each doc, extract on demand | **NLP** — pre-extraction essential |
| **Sentence detection** | Index-time chunking boundaries | Not applicable | **NLP** — no alternative |
| **POS-based fields** | Separate noun/verb indexes | Query formulation targets concepts naturally | **LLM** — existing behavior sufficient |
| **Noun phrase extraction** | Index compound terms as units | Phrase queries, proximity operators | **Draw** — LLM already handles well |
| **Synonym expansion** | Static synonym files (brittle) | Context-aware OR queries | **LLM** — superior quality |
| **Query refinement** | Server can't understand intent | Iterative search-read-refine cycle | **LLM** — requires reasoning |
| **Cross-document synthesis** | Can't do at all | "Find documents about topic X and summarize" | **LLM** — inherently cross-doc |
| **Language detection** | Tika/OpenNLP at index time | Could guess from query text | **NLP** — per-document, deterministic |
| **Relevance ranking** | BM25 + boost weights | AI reads results, re-ranks by understanding | **Both** — BM25 for initial, LLM for refinement |

**Key takeaway**: NLP and LLM are complementary layers, not alternatives. NLP adds permanent structure to the index that every query benefits from. The LLM adds per-query intelligence that no static analysis can match. The highest-value NLP features are those that **create new searchable dimensions** (NER → entity facets) rather than those that **duplicate LLM capabilities** (synonym expansion, query refinement).

---

**OpenNLP Platform Decision Framework**

If committing to OpenNLP, the cost-benefit changes:

| Scenario | Lemmatization only | Lemma + NER | Lemma + NER + Chunking |
|----------|-------------------|-------------|------------------------|
| Models loaded | 8 (4/lang) | 14-20 | 14-20 (same) |
| Memory overhead | ~40-100 MB | ~70-190 MB | ~70-190 MB |
| Startup time | +1-3 sec | +2-5 sec | +2-5 sec |
| Index-time overhead | 10-50x per token | +50-200ms/doc | Negligible additional |
| New searchable dimensions | 0 (recall improvement) | 3-6 entity facets | Better passages |
| Value multiplier | 1x | **3-5x** | **5-7x** |

The marginal cost of adding NER and sentence-aware chunking **on top of** an existing OpenNLP pipeline is low relative to the marginal value. This is why OpenNLP should be evaluated as a platform, not just a lemmatizer.

**Recommendation**: If Approach C (OpenNLP Lemmatizer) is ever pursued, plan for NER and sentence-aware chunking from the start. Design the pipeline, model loading, and configuration to support all three from day one, even if NER and chunking are initially disabled. The incremental cost of "ready for NER" is near zero; retrofitting later is much harder.

#### F. Query Autocomplete/Suggestions
**Status**: Not implemented
**Priority**: Medium
**Impact**: Medium

Builds on #2 (Index Observability). Help users discover terms as they type.

**Proposed tool**: `suggestQuery`
```json
{
  "partial": "cont",
  "field": "content",
  "limit": 10
}
// Returns: ["contract", "content", "contractor", "contribute", ...]
```

**Integration**: Works well with `suggestTerms` (item #2) but optimized for prefix matching.

**Key files**: New tool, uses Lucene's `TermsEnum.seekCeil()`

#### H. Index Statistics Over Time
**Status**: Not implemented
**Priority**: Low-Medium
**Impact**: Medium

Currently `getIndexStats` is point-in-time. No historical tracking.

**Proposed enhancement**:
- Track index size, document count, query performance over time
- Store in lightweight time-series format (CSV or embedded DB)
- New tool: `getIndexTrends` returns growth charts, query performance trends

**Use cases**:
- Capacity planning
- Performance regression detection
- Usage analytics

### Tier 3: Future Considerations

#### I. Document Versioning & History
**Status**: Not implemented
**Priority**: Low
**Impact**: Low-Medium

No support for tracking document versions or history.

**Proposed approach**:
- Store document versions with version field
- Link versions via `content_hash` or custom ID
- Track modification history

**Use cases**:
- "Find all versions of this contract"
- "Show me what changed between versions"
- Compliance/audit requirements

**Challenges**:
- Increases index size significantly
- Complex query requirements for version comparison
- May conflict with incremental crawler (updates vs versions)

#### J. Streaming/Incremental Results
**Status**: Not implemented
**Priority**: Low
**Impact**: Low

Currently all results returned at once. Large result sets could benefit from streaming.

**Challenge**: MCP STDIO doesn't support streaming well. Would need chunked response pattern.

**Alternative**: Use pagination effectively (already implemented).

#### K. Regex Search Support
**Status**: Not explicitly supported/documented
**Priority**: Low
**Impact**: Low

Lucene supports regex queries but not mentioned in documentation.

**Action needed**:
- Document in query syntax guide if supported
- Or explicitly reject if too expensive/dangerous

#### L. Custom Field Extractors
**Status**: Not implemented
**Priority**: Low
**Impact**: Low

Currently uses Tika's default metadata extraction. No way to add custom fields.

**Proposed approach**:
- Plugin system for custom metadata extractors
- Configuration to map extracted fields to index fields

**Use cases**:
- Extract custom metadata from filenames (e.g., `PROJECT-123-contract.pdf`)
- Domain-specific field extraction

**Complexity**: High - requires plugin architecture, security sandboxing

---

## Renumbered Tier 3 Items

#### 11. "Why Didn't This Match?" Analysis
**Status**: Not started
**Effort**: Medium
**Impact**: High

Currently `profileQuery` only explains documents that *did* match. Can't debug why an expected document didn't appear in results.

**Proposed tool**: `explainNonMatch` or extend `profileQuery` with `explainNonMatch` parameter:
```json
{
  "query": "contract signed",
  "filters": [...],
  "explainNonMatch": "/path/to/expected/doc.pdf"
}
```

**Returns**:
```json
{
  "documentPath": "/path/to/expected/doc.pdf",
  "matched": false,
  "reasons": [
    {
      "type": "filter_excluded",
      "filter": {"field": "language", "value": "en"},
      "actualValue": "de",
      "explanation": "Document has language 'de', but filter requires 'en'"
    },
    {
      "type": "missing_term",
      "term": "signed",
      "explanation": "Term 'signed' does not appear in document content"
    }
  ],
  "whatIfAnalysis": {
    "withoutFilters": {"wouldMatch": true, "score": 2.3},
    "withAllTermsOptional": {"wouldMatch": true, "score": 1.5}
  }
}
```

**Implementation**: Retrieve document by file path, test each query component individually, test filters one-by-one, run "what-if" scenarios.

**Benefit**: Massive debugging improvement for complex boolean queries with filters.

#### 12. Query Comparison Tool
**Status**: Not started
**Effort**: Medium-High
**Impact**: Medium

Hard to understand differences between similar queries or why results changed.

**Proposed tool**: `compareQueries`
```json
{
  "queryA": {"query": "*vertrag", "filters": [...]},
  "queryB": {"query": "vertrag*", "filters": [...]}
}
```

**Returns**:
```json
{
  "differences": {
    "totalHits": {"queryA": 450, "queryB": 380},
    "uniqueToA": 70,
    "uniqueToB": 0
  },
  "queryStructure": {
    "queryA": "WildcardQuery(content_reversed:gartrev*)",
    "queryB": "WildcardQuery(content:vertrag*)",
    "difference": "Query A searches reversed field for leading wildcard"
  },
  "topDocumentChanges": [
    {
      "document": "/path/to/doc.pdf",
      "rankInA": 5,
      "rankInB": 1,
      "explanation": "Ranks higher in B due to higher TF"
    }
  ]
}
```

**Benefit**: A/B testing queries, understanding query behavior differences.

#### 13. Passage-Level Scoring Explanation
**Status**: Not started
**Effort**: Medium
**Impact**: Medium

`profileQuery` explains document-level BM25 scoring, but not why specific passages were selected or scored.

**Current gap**: `UnifiedHighlighter` computes passage scores internally but doesn't expose explanation. Can't answer "Why is passage 2 scored higher than passage 1?"

**Proposed solution**: Instrument `IndividualPassageFormatter` or create custom highlighter to capture:
- Term matches within each passage
- Passage-level term frequency
- Passage length normalization
- Relative passage scoring

**Benefit**: Complete scoring transparency from query → document → passage.

#### 14. Enhanced Query Optimization Suggestions
**Status**: Not started (basic recommendations implemented)
**Effort**: Low-Medium
**Impact**: Medium

Current `profileQuery` provides basic recommendations. Could be enhanced with:
- Detection of redundant boolean clauses
- Suggestions for filter reordering
- Alternative query formulations with estimated impact
- Common anti-patterns (stop words, very common terms)

**Example output**:
```json
{
  "recommendations": [
    {
      "type": "remove_common_term",
      "severity": "high",
      "issue": "Term 'the' appears in 95% of documents",
      "suggestion": "Remove 'the' from query",
      "estimatedImprovement": "50% faster execution"
    }
  ]
}
```

#### 15. Search Template / Query Builder Tool
**Status**: Not started
**Effort**: Low
**Impact**: Medium

Lucene query syntax is complex; users struggle with escaping, boolean logic, wildcard placement.

**Proposed tool**: `buildQuery` - constructs valid Lucene queries from structured input:
```json
{
  "operation": "AND",
  "clauses": [
    {"type": "phrase", "text": "signed contract", "proximity": 5},
    {"type": "wildcard", "text": "vertrag", "position": "contains"},
    {"type": "fuzzy", "text": "agreement", "maxEdits": 2}
  ]
}
```

**Returns**: `{"generatedQuery": "\"signed contract\"~5 AND *vertrag* AND agreement~2", ...}`

**Benefit**: Lower barrier to entry, fewer syntax errors.

#### 16. Saved Searches / Alerts
**Status**: Not started
**Priority**: Low
**Impact**: Medium

Named queries with optional MCP notifications when new documents match. Turns the server from reactive search into a proactive knowledge assistant.

**Challenges**:
- Requires persistent state (contradicts stateless MCP philosophy)
- Notification delivery mechanism unclear
- Who owns the saved searches (multi-user considerations)

#### 17. Search History / Analytics
**Status**: Not started
**Priority**: Low
**Impact**: Low-Medium

Track queries, result counts, and opened documents. The AI could use this data to improve search strategies over time.

**Challenges**:
- Requires persistent state and storage
- Privacy considerations
- Multi-user tracking complexity

#### 18. Configurable Result Fields
**Status**: Not started
**Priority**: Low
**Impact**: Low

A `fields` parameter to select which fields appear in results. Reduces response size for narrow use cases.

**Current workaround**: Response size is already reasonable; passages are the bulk of data, already controllable via pageSize.

---

## Design Decisions & Insights

### Query Profiling: Opt-In Expensive Operations

**Decision**: Made filter impact, document scoring, and facet cost analysis opt-in (default: false)

**Trade-offs**:
- ✅ Fast default behavior (~5-10ms)
- ✅ Users choose their performance/detail trade-off
- ⚠️ More parameters to understand
- ⚠️ Basic analysis might miss important insights

**Rationale**: Profiling is a debugging tool, not a production feature. Most users want quick feedback; power users can enable deep analysis.

### Explanation API: Parse to Semantic DTOs

**Decision**: Parse Lucene's `Explanation` tree into structured DTOs instead of exposing `toString()`

**Trade-offs**:
- ✅ Version-independent (survives Lucene upgrades)
- ✅ LLM-friendly structured data
- ✅ Can add custom fields (contributionPercent, summary)
- ⚠️ Parsing logic is complex
- ⚠️ May miss some Lucene internals that advanced users want

**Rationale**: Explanation format has changed across Lucene versions. Parsing gives us control over output format and future-proofs the API.

### Filter Impact: Incremental Measurement

**Decision**: Measure filter impact by running queries incrementally (no filter → filter 1 → filter 1+2 → ...)

**Trade-offs**:
- ✅ Accurate measurement of actual impact
- ✅ Works for any filter type
- ✅ Shows cumulative effect
- ⚠️ Requires N+1 queries (expensive for many filters)
- ⚠️ Order-dependent (filter A then B ≠ B then A)

**Alternative considered**: Lucene's cost() API - too inaccurate for filters

**Rationale**: Accuracy is critical for debugging; performance cost is acceptable since it's opt-in.

### Term Statistics: Categorization Over Raw Numbers

**Decision**: Categorize term rarity ("rare", "common", "very common") in addition to raw document frequency

**Trade-offs**:
- ✅ More intuitive for humans/LLMs
- ✅ Actionable ("very common term" → consider removing)
- ⚠️ Categorization thresholds are somewhat arbitrary

**Rationale**: Raw numbers like "docFreq=4500, totalDocs=10000" require mental math. Categories are immediately actionable.

### NLP-vs-LLM Boundary: Where Server Processing Adds Value

**Decision**: NLP enriches the index (structure from unstructured data), LLM enriches the query (intelligence from context). Features are evaluated against this boundary.

**Boundary criterion**: If a capability produces deterministic, cacheable, document-level structure → NLP layer. If it requires contextual reasoning, user intent, or cross-document synthesis → LLM layer.

**High-value NLP features** (create new searchable dimensions):
- NER → entity facets (person, organization, location)
- Sentence detection → quality document chunking
- Lemmatization → irregular form recall

**LLM-superior features** (require reasoning):
- Synonym expansion (context-aware)
- Query refinement (iterative, intent-driven)
- Cross-document synthesis
- POS-based field indexing (BM25 already handles naturally)

**Rationale**: The highest-value server-side NLP features are those that create new searchable dimensions (like NER creating entity facets) — dimensions that don't exist without NLP processing. Features that merely duplicate what the LLM already does well (synonym expansion, query refinement) should stay client-side. See Candidate E2 for full analysis.

### Lucene Explanation API Limitations (Discovered During Implementation)

These are **Lucene design limitations**, not implementation choices:

1. **Automaton internals are opaque**: Wildcard/regex/fuzzy queries compile to finite automata (DFA/NFA). Once compiled, semantic meaning is lost. Cannot trace which specific substring matched in `*vertrag*` → "Arbeitsvertrag" vs "Mietvertrag". The automaton just has state transitions, no semantic labels.

2. **Cannot explain non-matches**: Explanation API requires a document ID from search results. Cannot explain why a document *didn't* match without manually testing query components.

3. **No passage-level explanation**: UnifiedHighlighter scores passages internally but doesn't expose explanation tree. Passage.score values are shown but not explained.

4. **Performance overhead**: Explanation is expensive (10-100x slower than just scoring). Uses reflection, creates deep object trees. Not suitable for all results or real-time use.

5. **Explains rewritten queries**: Shows the query *after* rewrites (e.g., `content_reversed:gartrev*` not `*vertrag`). Requires additional tracking to correlate with user's original query.

6. **No filter selectivity metrics**: Doesn't separately quantify filter contribution. Must run additional queries to measure filter impact.

7. **Cross-document comparison**: Each explanation is independent. Requires custom logic to compare "why doc A ranked higher than doc B".

**Workarounds implemented**:
- Show both original and rewritten queries in `queryAnalysis`
- Run incremental queries to measure filter impact
- Parse Explanation tree into semantic components with contribution percentages
- Accept that automaton internals can't be traced (show matched terms from highlighting instead)

**Future work**: Items 10-12 above address some of these gaps.

---

## Explicitly Rejected (With Reasoning)

### Named Entity Recognition (NER) at Index Time
**Decision**: Rejected for desktop/Claude Desktop use case
**Reasoning**:

**The proposal:** Extract entities (persons, organizations, locations) during indexing using OpenNLP NER models, store in faceted fields for searchable entity dimensions.

**Why rejected:**
1. **Desktop hardware constraints** - This MCP tool runs on consumer desktops, not servers:
   - Transformer NER models: 1-2 GB disk, 2-4 GB RAM
   - 30-second startup time (unacceptable for Claude Desktop integration)
   - 10-50x slower indexing than current pipeline
   - Users expect fast, lightweight tools, not multi-day indexing

2. **Claude already does excellent NER** - The AI client handles entity recognition on search results:
   - Reading 10 result documents vs indexing 100,000 documents
   - Context-aware entity disambiguation
   - No model files or memory overhead
   - Works for all languages (not just EN/DE)

3. **Division of labor** - Fast search retrieval (server) + intelligent understanding (Claude)
   - Server: "Find documents containing 'Schmidt' and 'Munich'"
   - Claude: "These mention Dr. Schmidt of Munich, not the city or other Schmidts"

4. **Battery and performance** - Laptop users care about:
   - Not spinning up fans constantly
   - Not draining battery during indexing
   - Responsive system while indexing runs in background

**When this would make sense:**
- Server deployment with dedicated resources
- GPU available for model inference
- Large corpus where pre-extraction provides significant value
- Multi-user environment amortizing indexing cost

**Alternative approach:** Let Claude do entity extraction on the top N search results (already happens naturally). The server focuses on fast, accurate text retrieval.

**Related:** The broader OpenNLP platform analysis (lemmatization, sentence detection, POS tagging) also needs reevaluation for desktop constraints. Snowball stemming (Candidate E - completed) provides good recall improvement without the OpenNLP overhead.

### Real-Time Query Performance Profiling (Per-Component Timing)

**Decision**: Rejected
**Reasoning**: Lucene doesn't expose internal query execution timing at component level. The cost() API provides estimates, but actual execution time isn't broken down by query clause.

**Alternative**: Use overall execution time + cost estimates + incremental queries (implemented in `profileQuery`).

### Embedding Lucene Explanation.toString() Directly in Response

**Decision**: Rejected
**Reasoning**:
- Lucene's Explanation string format changes between versions
- Not LLM-friendly (deeply nested, verbose, jargon-heavy)
- Hard for humans to parse
- Contains internal implementation details users don't care about

**Alternative**: Parse Explanation into semantic DTOs (implemented).

### Integrating Debug Tool into Main `search` Tool

**Decision**: Rejected
**Reasoning**:
- Would clutter search responses
- Performance overhead for all queries (even when debugging not needed)
- Harder to evolve APIs independently

**Alternative**: Separate `profileQuery` tool (implemented).

### Automaton State Machine Visualization

**Decision**: Rejected
**Reasoning**:
- Wildcard/regex queries compile to finite automata (DFA/NFA)
- Once compiled, semantic meaning is lost (just state transitions)
- Can't meaningfully trace "why this matched"
- Visualization would be complex and not actionable

**Alternative**: Show original query + rewritten query; accept automaton internals are opaque.

### Server-Side Embeddings / Vector Search
**Decision**: Rejected for now
**Reasoning**: Contradicts the core "client-side intelligence" architecture.

The AI client already handles semantic understanding. Adding embeddings would:
- Introduce model hosting dependencies (ONNX runtime, model files, GPU considerations)
- Double the retrieval logic (lexical + vector paths to maintain and test)
- Increase index size and memory requirements significantly
- Add complexity to the indexing pipeline (embedding computation per document/chunk)

**The gap vector search fills**: finding documents where the concept matches but zero terms overlap, and the AI can't predict the document's vocabulary. Example: searching for "reducing energy costs" when the relevant document discusses "HVAC retrofit" and "LED conversion" without ever mentioning "energy" or "cost."

**Why the gap is narrow in practice**:
1. The AI iterates — it reads results, discovers vocabulary, and refines queries
2. `getDocumentDetails` lets the AI explore individual documents for terminology
3. Facets help narrow the space without semantic matching
4. Personal/corporate document collections have bounded vocabulary

**Revisit conditions**: Consider if (a) the corpus grows beyond ~10,000 documents, (b) users report frequent "can't find it" scenarios despite AI expansion, or (c) Lucene's KNN API matures to the point where adding vectors is trivially simple.

### Server-Side Stemming / Language-Specific Analyzers (Single-Field Approach)
**Decision**: Rejected (single-field stemming only — multi-field approach is now a candidate, see Tier 2 item E)
**Reasoning**: Stemming the `content` field directly was rejected because it causes highlighting offset issues (partial-word highlights), requires asymmetric index/search analyzers, and conflates exact and stemmed matches in scoring. The AI's wildcard + OR approach partially compensates but misses many German morphological variants (e.g., `vertrag*` does not match "Verträge" → "vertrage" after ICU folding).

**Revised approach**: Per-language stemmed shadow fields (`content_stemmed_de`, `content_stemmed_en`) — NOT stored, no term vectors, search only — combined with weighted OR queries at query time. Highlighting stays on the unstemmed `content` field. This follows the existing `content_reversed` pattern and avoids all original objections. See improvement candidate E for full details.

### HTTP/SSE Transport
**Decision**: Deferred
**Reasoning**: STDIO is required for Claude Desktop. Adding SSE would broaden compatibility but adds security considerations (authentication, CORS) for no immediate benefit. Revisit if browser-based MCP clients become mainstream.

### Geographic/Geospatial Search
**Decision**: Rejected
**Reasoning**: Out of scope for a document search server. Lucene supports geo queries, but document collections rarely have meaningful geospatial data. Would add complexity for minimal benefit.

**If needed**: Use filters on location metadata fields instead.

### Access Control / User Authentication
**Decision**: Rejected (handled by MCP client)
**Reasoning**: MCP server runs locally via STDIO - security is handled by:
- File system permissions (index directory)
- MCP client authentication (Claude Desktop handles user auth)
- Process isolation (server runs as user's process)

Adding server-side auth would be redundant and complex for local STDIO use case.

### Collaborative Filtering / "Users who viewed this also viewed..."
**Decision**: Rejected
**Reasoning**:
- Contradicts stateless architecture
- Personal/corporate doc search != e-commerce recommendation
- Requires multi-user tracking and analytics infrastructure
- The AI can already ask follow-up questions to refine search

**Alternative**: AI-driven query refinement based on conversation context.

### Tool Grouping / Consolidated Admin Tools
**Decision**: Deferred
**Reasoning**:
- Considered grouping `pauseCrawler`, `resumeCrawler`, `getCrawlerStatus` into single `manageCrawler` tool
- Would reduce tool count but make tool less discoverable
- MCP Resources approach (context reduction) is more maintainable
- Keep separate tools for clarity; use Resources for documentation

**Revisit if**: MCP protocol adds native tool categorization/namespacing.

---

## Implementation Guidelines

When implementing any improvement from this roadmap:

1. **Evaluate against the core principle** — Does this belong in the server, or can the AI client handle it?
2. **Keep the server fast** — Startup must stay under ~2 seconds, search under ~100ms for typical queries
3. **Bump `SCHEMA_VERSION`** if any indexed field changes (see `DocumentIndexer.java`)
4. **Update README.md** for any user-facing change (per CLAUDE.md rules)
5. **Test with real documents** — synthetic tests aren't sufficient for search quality
6. **Prefer additive changes** — new tools > modifying existing tools; new optional parameters > breaking changes
7. **Follow existing patterns** — Request/Response DTOs, `fromMap()` factories, `success()`/`error()` static methods

### Additional Guidelines for Profiling/Debug Tools

When implementing debugging or profiling tools (like `profileQuery`):

1. **Make expensive operations opt-in** — Default behavior should be fast (<10ms); deep analysis should require explicit parameter (e.g., `analyzeFilterImpact=true`)

2. **Avoid exposing Lucene internals** — Parse internal structures (like Explanation.toString()) into semantic, version-independent DTOs. Don't expose raw Lucene API objects.

3. **Focus on LLM/human readability**:
   - Include human-readable summaries and categorizations ("very common", "high selectivity")
   - Provide context (percentages alongside counts)
   - Use clear field names
   - Add actionable recommendations

4. **Document performance costs** — Be explicit about execution time and number of queries executed for each analysis level

5. **Structure for composability** — Profiling data should be usable by LLMs to generate insights, not just displayed to users

6. **Accept Lucene limitations gracefully** — Document what cannot be explained (automaton internals, non-matches, passage scoring) rather than trying to work around fundamental limitations

7. **Consider versioning** — If depending on Lucene-version-specific behavior, isolate it so future upgrades are easier

### Additional Guidelines for MCP Resources Pattern

When adding detailed documentation to avoid context pollution:

1. **Keep tool descriptions concise** — 1-3 sentences max; focus on what, when, and critical warnings

2. **Parameter descriptions minimal** — Type and purpose only, no examples or edge cases

3. **Create MCP Resources for details** — Comprehensive guides with examples, syntax tables, troubleshooting

4. **Use consistent URIs** — `lucene://docs/{topic}` for documentation, `lucene://admin/{feature}` for admin UIs

5. **Structure resources as markdown** — Easy to read, LLM-friendly, supports tables and code blocks

6. **Include navigation** — Table of contents, clear sections, cross-references to related resources

7. **Test discoverability** — Ensure LLM can find resources via tool descriptions referencing them

---

## Current Status Overview

### ✅ Completed (13 items)

| Item | Impact | Notes |
|------|--------|-------|
| Structured Multi-Filter Support | High | Tier 1 #1 |
| Sort Options | Medium-High | Tier 1 #4 |
| Multi-Language Snowball Stemming | High | Tier 2 #E — SCHEMA_VERSION 3, DE+EN shadow fields |
| Date-Friendly Query Parameters | Medium | Tier 2 #6 (via filters) |
| Expanded File Format Support | Low-Medium | Tier 2 #8 |
| Markdown Bold Highlighting | Medium | New #8.5 — Changed from `<em>` to `**` for Claude Desktop rendering |
| Query Profiling & Debugging | High | Tier 2 #9 |
| Context Pollution Reduction | High | New #10 |
| Automatic Phrase Proximity Expansion | Medium-High | New #11 — Auto-expands `"Domain Design"` to include variations |

### 🚀 High Priority (Next to implement)

| Item | Effort | Impact | Justification |
|------|--------|--------|---------------|
| ~~Index Observability Tools~~ | ~~Low-Medium~~ Done | ~~High~~ | Tier 1 #2 - Done: `suggestTerms` + `getTopTerms` |
| **Index Backup & Restore** | Medium | Critical | Missing Gap A - Production necessity |
| **Duplicate Detection** | Medium | High | Missing Gap B - Common user need |

### 🎯 Medium Priority (Valuable enhancements)

| Item | Effort | Impact | Justification |
|------|--------|--------|---------------|
| **Irregular Verb Stemming** | ~~Medium~~ Done | ~~Medium~~ | E2 - Superseded by OpenNLP lemmatization |
| Document Chunking | High | High | Tier 1 #3 - Solves long doc problem |
| "More Like This" | Medium | Medium | Tier 2 #5 - AI-friendly feature |
| OCR Support | Medium-High | Medium | Tier 2 #7 - Depends on user docs |
| Batch Operations | Medium | Medium | Missing Gap C - Efficiency |
| Export/Reports | Medium | Medium | Missing Gap D - Common request |
| Query Autocomplete | Medium | Medium | Missing Gap F - UX enhancement |

### 🔮 Future Considerations (Nice to have)

| Item | Priority | Notes |
|------|----------|-------|
| "Why Didn't This Match?" | Low-Medium | Tier 3 #11 - Complements profileQuery |
| Query Comparison Tool | Low-Medium | Tier 3 #12 - A/B testing |
| Passage-Level Scoring | Low-Medium | Tier 3 #13 - Deep debugging |
| Enhanced Optimization | Low | Tier 3 #14 - Incremental improvement |
| Query Builder | Low | Tier 3 #15 - Syntax assistance |
| Saved Searches | Low | Tier 3 #16 - Requires state |
| Search History | Low | Tier 3 #17 - Requires state |
| Document Versioning | Low | Missing Gap I - Complex |
| Streaming Results | Low | Missing Gap J - MCP limitation |
| Regex Search | Low | Missing Gap K - Documentation gap |

### ❌ Explicitly Rejected (6 items)

- Per-component timing profiling (Lucene doesn't expose)
- Raw Explanation.toString() (version-dependent)
- Debug in search tool (context pollution)
- Automaton visualization (not actionable)
- Vector search (contradicts architecture)
- Single-field stemming (replaced by multi-field approach — see candidate E)
- HTTP/SSE transport (deferred)
- Geographic search (out of scope)
- Access control (handled by MCP client)
- Collaborative filtering (contradicts stateless)
- Tool grouping (deferred - Resources approach better)

---

## Recommended Implementation Order

Based on impact, effort, and dependencies:

### Phase 1: Core Infrastructure (Next 1-2 months)
1. **Index Backup & Restore** (Critical) - Production readiness
2. **Index Observability Tools** (High ROI) - Enables better AI queries
3. **Duplicate Detection** (High value) - Common user pain point

### Phase 2: Search Enhancements (2-4 months)
4. **"More Like This"** (AI-friendly) - Fits MCP architecture well
5. **Batch Operations** (Efficiency) - Better LLM integration
6. **Export/Reports** (Productivity) - Stakeholder requests

### Phase 3: Advanced Features (4-6 months)
7. **Document Chunking** (High impact, high effort) - Solves long doc problem
8. **OCR Support** (Conditional) - If users have scanned PDFs
9. **Query Autocomplete** (UX) - Builds on observability tools

### Phase 4: Profiling Enhancements (6+ months)
10. **"Why Didn't This Match?"** (Debug power) - Completes profiling suite
11. **Query Comparison Tool** (A/B testing) - Advanced debugging
12. **Passage-Level Scoring** (Deep debugging) - Complete scoring transparency

### Ongoing
- Create MCP Resources for existing features (crawler config, field schema, troubleshooting)
- Enhance optimization recommendations in `profileQuery`
- Monitor user requests for priority adjustments

---

## Notes for Future Maintainers

### What This Skill Tracks

- ✅ Completed improvements with implementation notes
- 🚀 High-priority roadmap items
- 🎯 Medium-priority enhancements
- 🔮 Future possibilities
- ❌ Explicitly rejected ideas with reasoning
- 📐 Design decisions and trade-offs
- 🐛 Known limitations and workarounds

### When to Update This Skill

- After implementing any feature (move to Completed, add lessons learned)
- When discovering new limitations (add to Known Limitations)
- When rejecting a proposed feature (add to Explicitly Rejected with reasoning)
- When user feedback reveals new priorities (adjust tier placement)
- When dependencies change (e.g., Lucene upgrade enables new features)
- After design decisions (document in Design Decisions section)

### How to Evaluate New Feature Proposals

1. **Does it align with "Client-Side Intelligence" principle?** - If AI can do it, server shouldn't
2. **What's the effort vs impact?** - Use existing tier framework
3. **Does it contradict existing design decisions?** - Check Explicitly Rejected section
4. **Is there a simpler alternative?** - Prefer composability over monolithic features
5. **Does it require breaking changes?** - Prefer additive changes
6. **Is it truly needed or just "nice to have"?** - Validate with user requests

---

## Summary

The MCP Lucene Server has matured from a basic search tool to a comprehensive, production-ready search platform with:

- ✅ **13 completed major features** including profiling, sorting, markdown highlighting, automatic phrase expansion, and context optimization
- 🚀 **3 high-priority items** ready for implementation
- 🎯 **7 medium-priority enhancements** with clear value propositions
- 🔮 **9 future possibilities** for long-term roadmap
- ❌ **12 rejected ideas** with documented reasoning

**Key architectural strengths to preserve**:
- Client-side intelligence (AI does semantics, server does lexical)
- Stateless MCP tools (no server-side state beyond index)
- Fast defaults with opt-in complexity (like `profileQuery`)
- LLM-optimized output structures
- MCP Resources pattern for documentation

**Desktop-first architecture decisions**:
- Rejected heavy NLP models (NER, full OpenNLP pipeline) for desktop deployment
- Focus on fast, lightweight search with client-side AI intelligence
- Resource constraints: <100 MB RAM, <2 sec startup, no multi-day indexing

**Next recommended focus**: Index backup/restore (critical) + Index observability tools (high ROI) + Duplicate detection (common user need)
