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
- The AI handles multilingual query formulation — no per-language analyzers needed
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
**Status**: Not started
**Effort**: Low-Medium
**Impact**: High

Give the AI better visibility into what's actually in the index, so it can formulate better queries.

**`suggestTerms` tool** — Given a field and prefix, return the N most frequent terms:
```json
{"field": "content", "prefix": "vertrag", "limit": 20}
// Returns: ["vertrag", "vertragsklausel", "vertragsbedingungen", ...]
```
This lets the AI discover vocabulary it wouldn't have guessed.

**`getTopTerms` tool** — For a given field, return the N most frequent terms:
```json
{"field": "author", "limit": 50}
// Returns: [{"term": "John Doe", "count": 42}, ...]
```
Useful for author/subject/keyword exploration beyond facets.

**Key files**: New tool methods in `LuceneSearchTools.java`, new index reader methods in `LuceneIndexService.java`

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

#### E. Query Autocomplete/Suggestions
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

#### F. Index Statistics Over Time
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

#### G. Document Versioning & History
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

#### H. Streaming/Incremental Results
**Status**: Not implemented
**Priority**: Low
**Impact**: Low

Currently all results returned at once. Large result sets could benefit from streaming.

**Challenge**: MCP STDIO doesn't support streaming well. Would need chunked response pattern.

**Alternative**: Use pagination effectively (already implemented).

#### I. Regex Search Support
**Status**: Not explicitly supported/documented
**Priority**: Low
**Impact**: Low

Lucene supports regex queries but not mentioned in documentation.

**Action needed**:
- Document in query syntax guide if supported
- Or explicitly reject if too expensive/dangerous

#### J. Custom Field Extractors
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

### Server-Side Stemming / Language-Specific Analyzers
**Decision**: Rejected
**Reasoning**: The AI compensates with wildcard queries (`contract*` covers contracts, contracting, contracted). Language-specific stemmers add complexity, require per-field language detection, and can hurt precision (aggressive stemming conflates unrelated terms). The AI's wildcard + OR approach is more controllable.

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

### ✅ Completed (11 items)

| Item | Impact | Notes |
|------|--------|-------|
| Structured Multi-Filter Support | High | Tier 1 #1 |
| Sort Options | Medium-High | Tier 1 #4 |
| Date-Friendly Query Parameters | Medium | Tier 2 #6 (via filters) |
| Expanded File Format Support | Low-Medium | Tier 2 #8 |
| Query Profiling & Debugging | High | Tier 2 #9 |
| Context Pollution Reduction | High | New #10 |

### 🚀 High Priority (Next to implement)

| Item | Effort | Impact | Justification |
|------|--------|--------|---------------|
| **Index Observability Tools** | Low-Medium | High | Tier 1 #2 - Helps AI discover vocabulary |
| **Index Backup & Restore** | Medium | Critical | Missing Gap A - Production necessity |
| **Duplicate Detection** | Medium | High | Missing Gap B - Common user need |

### 🎯 Medium Priority (Valuable enhancements)

| Item | Effort | Impact | Justification |
|------|--------|--------|---------------|
| Document Chunking | High | High | Tier 1 #3 - Solves long doc problem |
| "More Like This" | Medium | Medium | Tier 2 #5 - AI-friendly feature |
| OCR Support | Medium-High | Medium | Tier 2 #7 - Depends on user docs |
| Batch Operations | Medium | Medium | Missing Gap C - Efficiency |
| Export/Reports | Medium | Medium | Missing Gap D - Common request |
| Query Autocomplete | Medium | Medium | Missing Gap E - UX enhancement |

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
| Document Versioning | Low | Missing Gap G - Complex |
| Streaming Results | Low | Missing Gap H - MCP limitation |
| Regex Search | Low | Missing Gap I - Documentation gap |

### ❌ Explicitly Rejected (6 items)

- Per-component timing profiling (Lucene doesn't expose)
- Raw Explanation.toString() (version-dependent)
- Debug in search tool (context pollution)
- Automaton visualization (not actionable)
- Vector search (contradicts architecture)
- Stemming/language analyzers (AI handles it)
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

- ✅ **11 completed major features** including profiling, sorting, and context optimization
- 🚀 **3 high-priority items** ready for implementation
- 🎯 **7 medium-priority enhancements** with clear value propositions
- 🔮 **9 future possibilities** for long-term roadmap
- ❌ **11 rejected ideas** with documented reasoning

**Key architectural strengths to preserve**:
- Client-side intelligence (AI does semantics, server does lexical)
- Stateless MCP tools (no server-side state beyond index)
- Fast defaults with opt-in complexity (like `profileQuery`)
- LLM-optimized output structures
- MCP Resources pattern for documentation

**Next recommended focus**: Index backup/restore (critical) + Index observability tools (high ROI) + Duplicate detection (common user need)
