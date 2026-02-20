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

### Why Multi-Analyzer Pipeline?
The server uses multiple analyzer chains optimized for different search patterns. Each document is indexed with several shadow fields, each using a different analyzer:
- `UnicodeNormalizingAnalyzer`: Primary content field with NFKC normalization, diacritic folding, ligature expansion
- `ReverseUnicodeNormalizingAnalyzer`: Reversed tokens for efficient leading wildcard queries (`*vertrag`)
- `OpenNLPLemmatizingAnalyzer`: Dictionary-based lemmatization for German and English (handles irregular forms)
- `GermanTransliteratingAnalyzer`: Maps ASCII digraphs to umlauts (Mueller→Müller)

See [PIPELINE.md](../../PIPELINE.md) for complete analyzer chain documentation and query pipeline details.

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

## MCP Response Token Budget

Every byte returned by an MCP tool is a token consumed by the AI client. This is a **first-class design constraint** — treat response size the same way you would treat latency or memory.

**Rules for MCP tool responses:**
- Never return full document content in search results. Return focused passages instead.
- Passages are truncated to `max-passage-char-length` (default: 200) using a **highlight-centred window** that trims irrelevant leading/trailing text while preserving `<em>` tags and surrounding context.
- Default `max-passages` is 3 per document — enough for relevance judgement, not exhaustive coverage. Users who need more can retrieve the full document via `getDocument`.
- When adding new fields to tool responses, ask: "Does the AI client actually need this in every response, or only on demand?" Prefer separate detail-fetching tools over bloating the default response.
- When designing new MCP tools, estimate the worst-case response size: `(fields per result) × (avg field size) × (max results) × (max passages)`. Keep it under ~10 KB per tool call as a guideline.
- Prefer structured, machine-readable output (short field values, numeric scores) over verbose human-readable text.

**Example**: The passage system was redesigned to reduce worst-case per-search token load from ~25,000 chars (5 passages × 500 chars × 10 results) to ~6,000 chars (3 passages × 200 chars × 10 results) — a 76% reduction with *better* precision because passages are now individually extracted and windowed around highlights.

## Limitations (Design Constraints)

| Limitation | Reason | Workaround |
|------------|--------|------------|
| Lexical search only | Simplicity, no ML dependencies | AI generates OR queries for synonyms |
| Single-node only | Target: personal document collections | Vertical scaling |
| STDIO only | Claude Desktop requirement | Could add SSE transport |
| No auth | Single-user desktop deployment | OS-level sandboxing |

## Future Enhancement Ideas

See the **`/improvements`** skill for the full prioritized roadmap, trade-off analysis, and rejected ideas with reasoning.
