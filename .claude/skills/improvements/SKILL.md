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
**Status**: Not started
**Effort**: Medium
**Impact**: High

Currently only one `filterField`/`filterValue` pair is supported. Real-world queries need:
- `language:de AND file_extension:pdf AND author:"John Doe"`
- Date range filtering: "documents modified in the last 30 days"
- Numeric ranges on `file_size`

The AI currently has to encode all filters into the Lucene query string, which is fragile and error-prone.

**Approach**: Add a `filters` array parameter to the `search` tool:
```json
{
  "query": "contract*",
  "filters": [
    {"field": "language", "value": "de"},
    {"field": "file_extension", "value": "pdf"},
    {"field": "modified_date", "from": "2025-01-01", "to": "2025-12-31"}
  ]
}
```

**Key files**: `SearchRequest.java`, `LuceneIndexService.search()`, `LuceneSearchTools.SEARCH_DESCRIPTION`

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
**Status**: Not started
**Effort**: Low
**Impact**: Medium-High

No `sortBy` parameter exists. Users frequently want "most recent first" or "largest first."

**Approach**: Add `sortBy` and `sortOrder` parameters:
```json
{"query": "report*", "sortBy": "modified_date", "sortOrder": "desc"}
```

Supported sort fields: `modified_date`, `created_date`, `file_size`, `_score` (default).

**Key files**: `SearchRequest.java`, `LuceneIndexService.search()`

### Tier 2: Medium Impact — Expand Capabilities

#### 5. "More Like This" / Similar Document Search
**Status**: Not started
**Effort**: Medium
**Impact**: Medium

Lucene has `MoreLikeThis` built in. A `findSimilar` tool that takes a `file_path` and returns related documents would be useful for the AI ("find documents similar to this contract").

**Key files**: New tool in `LuceneSearchTools.java`, new method in `LuceneIndexService.java`

#### 6. Date-Friendly Query Parameters
**Status**: Not started
**Effort**: Low
**Impact**: Medium

Date range queries exist in Lucene syntax but require epoch milliseconds, which is unfriendly even for an AI. Add `dateFrom`/`dateTo` parameters with ISO-8601 support to the `search` tool.

**Key files**: `SearchRequest.java`, `LuceneIndexService.search()`

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

### Tier 3: Future Considerations

#### 9. Query Explanation / Debug Tool
A `explainQuery` tool showing the parsed Lucene query tree, rewriting applied (leading wildcards -> reversed), and zero-hit terms. Helps the AI self-debug poor search results.

#### 10. Saved Searches / Alerts
Named queries with optional MCP notifications when new documents match. Turns the server from reactive search into a proactive knowledge assistant.

#### 11. Search History / Analytics
Track queries, result counts, and opened documents. The AI could use this data to improve search strategies over time.

#### 12. Configurable Result Fields
A `fields` parameter to select which fields appear in results. Reduces response size for narrow use cases.

---

## Explicitly Rejected (With Reasoning)

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
