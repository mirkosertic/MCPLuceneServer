---
name: architecture
description: Detailed architecture documentation including design decisions, processing patterns, and system internals. Use when discussing architecture, understanding why something was built a certain way, or planning significant changes.
allowed-tools: Read, Glob, Grep
---

# Architecture Deep Dive

## System Overview

```
┌─────────────────────────────────────────────────────┐
│           Claude Desktop / MCP Client               │
└────────────────┬────────────────────────────────────┘
                 │ STDIO Transport (JSON-RPC)
                 ▼
┌─────────────────────────────────────────────────────┐
│           MCP Java SDK (STDIO Transport)            │
│  ┌──────────────────────────────────────────────┐   │
│  │        LuceneSearchTools (MCP Tools)         │   │
│  └──────────────────────────────────────────────┘   │
└─────────┬───────────────────────────┬───────────────┘
          │                           │
          ▼                           ▼
┌──────────────────────┐    ┌──────────────────────┐
│  LuceneIndexService  │    │ DocumentCrawler      │
│  - Search & Index    │    │ Service              │
│  - NRT Manager       │    │ - File Discovery     │
│  - Admin Operations  │    │ - Content Extraction │
└──────────┬───────────┘    └──────────┬───────────┘
           │                           │
           ▼                           ▼
┌─────────────────────────────────────────────────────┐
│              Apache Lucene 10.3 + Apache Tika       │
└─────────────────────────────────────────────────────┘
```

## Design Decisions

### Why Plain Java (no Spring)?
- Fast startup (~1 second) - critical for MCP subprocess
- Smaller JAR (~45MB)
- Direct control over lifecycle
- Trade-off: Manual dependency wiring in `LuceneserverApplication.java`

### Why STDIO Transport?
- Required for Claude Desktop integration
- No network security concerns
- **Consequence**: Console logging MUST be disabled in production (`deployed` profile)

### Why UnicodeNormalizingAnalyzer (ICUFoldingFilter)?
- Replaces the previous `StandardAnalyzer` to handle real-world document text correctly
- ICUFoldingFilter performs NFKC normalization, diacritic folding, and ligature expansion
- Critical for PDF content: ligatures (fi, fl) extracted by Tika are invisible Unicode code-points that break exact-match search without folding
- **Trade-off**: Adds `lucene-analysis-icu` dependency; still no stemming/synonyms -- AI assistants compensate with OR queries
- See README.md "Lexical Search" section for user guidance

### Why Reverse Token Field (`content_reversed`)?
- Enables efficient leading wildcard queries (e.g., `*vertrag` to find German compound words like "Arbeitsvertrag")
- Without this, Lucene must scan every term in the index for leading wildcards -- extremely slow on large indices
- Uses `ReverseUnicodeNormalizingAnalyzer` (same chain as `UnicodeNormalizingAnalyzer` + `ReverseStringFilter`)
- `PerFieldAnalyzerWrapper` routes the `content_reversed` field to the reverse analyzer automatically
- `rewriteLeadingWildcards()` in `LuceneIndexService` transparently rewrites queries before execution
- The original (non-rewritten) query is still used for highlighting and term extraction, so `<em>` tags appear correctly
- **Trade-off**: Doubles the token count in the index (content indexed twice); `Store.NO` minimizes disk overhead
- **Breaking change**: Requires full reindex -- existing documents lack the `content_reversed` field

## Processing Patterns

### Batch Processing
```
Directory Walkers (N threads) ──> LinkedBlockingQueue ──> Batch Processor (1 thread)
```
- Reduces Lucene commit overhead (commits are expensive)
- Configurable via `batch-size` and `batch-timeout-ms`

### NRT (Near Real-Time) Optimization
- Normal: 100ms refresh interval
- Bulk indexing (>=1000 files): Auto-switches to 5000ms
- Prevents CPU thrashing during large crawls

### Configuration Priority
```
Environment Variable > ~/.mcplucene/config.yaml > application.yaml
```
When `LUCENE_CRAWLER_DIRECTORIES` env var is set, MCP config tools return errors.

### Admin Operations Pattern
- Long-running ops (optimize, purge) run async in single-threaded executor
- Tools return immediately with `operationId`
- Clients poll with `getIndexAdminStatus()`
- Only one admin operation can run at a time

## Crawler Architecture

The document crawler uses a multi-layered architecture:

1. **DocumentCrawlerService** - Main orchestrator
   - Manages crawl lifecycle (start, pause, resume, stop)
   - Coordinates parallel directory processing
   - Handles batch queuing and processing
   - Manages NRT optimization

2. **FileContentExtractor** - Apache Tika integration
   - Extracts text content from documents
   - Detects document language
   - Extracts metadata (author, title, dates, etc.)

3. **DocumentIndexer** - Lucene document builder
   - Creates standardized Lucene documents
   - Handles document updates via content hash
   - Manages field schema

4. **DirectoryWatcherService** - File system monitoring
   - Uses Java WatchService for efficient monitoring
   - Handles file create, modify, delete events
   - Supports recursive directory watching

5. **CrawlExecutorService** - Thread pool management
   - Configurable worker threads
   - Bounded queue with backpressure handling

6. **CrawlStatisticsTracker** - Progress tracking
   - Thread-safe statistics collection
   - Automatic progress notifications
   - Per-directory breakdown

7. **IndexReconciliationService** - Incremental indexing
   - Compares index snapshot with filesystem in memory
   - Computes ADD / UPDATE / DELETE / SKIP sets
   - Applies bulk orphan deletions before new content is indexed
   - Designed to be fast: no content extraction during the reconciliation phase

## Limitations (Design Constraints)

| Limitation | Reason | Workaround |
|------------|--------|------------|
| Lexical search only | Simplicity, no ML dependencies | AI generates OR queries for synonyms |
| Single-node only | Target: personal document collections | Vertical scaling |
| STDIO only | Claude Desktop requirement | Could add SSE transport |
| No auth | Single-user desktop deployment | OS-level sandboxing |

## Future Enhancement Ideas

- **OCR support**: Tesseract for scanned documents (high complexity)
- **Semantic search**: Vector embeddings (very high complexity)
- **Multi-language analyzers**: Language-specific stemming
- **HTTP/SSE transport**: For browser-based clients
