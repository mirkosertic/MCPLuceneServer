# MCP Lucene Server - AI Assistant Guide

This document contains instructions and context for AI assistants working on this codebase. For user-facing documentation (installation, usage, configuration, troubleshooting), see **README.md**.

---

## Critical Rules

1. **ALWAYS use the `implement-review-loop` agent** for code changes
2. **ALWAYS update README.md** when adding, removing, or changing:
   - MCP tools (add tool documentation)
   - Configuration options
   - User-facing behavior
   - Troubleshooting information
3. **NEVER duplicate README content here** - this file is for development context only
4. **Test with real documents** before considering a feature complete
5. Always use Context7 MCP when I need library/API documentation, code generation, setup or configuration steps without me having to explicitly ask.


---

## Architecture Overview

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

### Key Components

| Component | File | Responsibility |
|-----------|------|----------------|
| **LuceneSearchTools** | `LuceneSearchTools.java` | MCP tool endpoints, request/response handling |
| **LuceneIndexService** | `LuceneIndexService.java` | Lucene IndexWriter/Searcher, NRT, admin ops |
| **DocumentCrawlerService** | `DocumentCrawlerService.java` | Directory walking, batch processing, state management |
| **FileContentExtractor** | `FileContentExtractor.java` | Apache Tika integration, metadata extraction |
| **DocumentIndexer** | `DocumentIndexer.java` | Lucene document creation, field schema |
| **CrawlerConfigurationManager** | `CrawlerConfigurationManager.java` | Config file persistence (`~/.mcplucene/config.yaml`) |
| **DirectoryWatcherService** | `DirectoryWatcherService.java` | File system monitoring for real-time updates |

---

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

### Why StandardAnalyzer?
- Simple, fast, no external dependencies
- **Limitation**: No stemming/synonyms - AI assistants compensate with OR queries
- See README.md "Lexical Search" section for user guidance

### Batch Processing Pattern
```
Directory Walkers (N threads) ──> LinkedBlockingQueue ──> Batch Processor (1 thread)
```
- Reduces Lucene commit overhead (commits are expensive)
- Configurable via `batch-size` and `batch-timeout-ms`

### NRT (Near Real-Time) Optimization
- Normal: 100ms refresh interval
- Bulk indexing (≥1000 files): Auto-switches to 5000ms
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

---

## Code Conventions

### Java Style
- Use `final` for parameters and local variables
- **Constructor injection only** - no field injection
- Catch specific exceptions, not `Exception`
- Return user-friendly errors in MCP responses

### Logging
```java
private static final Logger logger = LoggerFactory.getLogger(ClassName.class);
```
- `ERROR`: Exceptions affecting functionality
- `WARN`: Unexpected situations, degraded functionality
- `INFO`: State changes, user actions
- `DEBUG`: Detailed flow (sparingly used)

### MCP Tool Response Format
Always include:
```json
{"success": true/false, "error": "message if failed", ...data fields}
```

### DTO Pattern
- Request DTOs in `mcp/dto/` with `fromMap()` static factory
- Response DTOs with static factory methods (`success()`, `error()`)
- Use `@Description` annotation for schema generation
- Use `@Nullable` for optional fields

---

## Adding New MCP Tools

1. Create request DTO in `mcp/dto/` (if tool has parameters)
2. Create response DTO in `mcp/dto/`
3. Add tool registration in `LuceneSearchTools.getToolSpecifications()`
4. Implement handler method in `LuceneSearchTools`
5. **Update README.md** with tool documentation
6. Test with Claude Desktop

Example pattern - see existing tools like `search()` or `optimizeIndex()`.

---

## Common Development Tasks

### Adding a New Facet Field
1. Update `DocumentIndexer.java` - add to `FacetsConfig`
2. Update README.md field schema table

### Adding File Format Support
1. Add pattern to `application.yaml` `include-patterns`
2. Apache Tika handles most formats automatically
3. For custom parsing: extend `FileContentExtractor.java`

### Debugging STDIO Issues
- Must use `deployed` profile (disables console logging)
- Check for `System.out.println` in codebase
- Test: `java -Dspring.profiles.active=deployed -jar target/luceneserver-*.jar`

### Performance Tuning
- Indexing slow? Increase `thread-pool-size`, `batch-size`
- Search slow? Check for prefix wildcards (`*test`), reduce `pageSize`
- OOM? Set `max-content-length`, increase `-Xmx`

---

## Limitations (Design Constraints)

| Limitation | Reason | Workaround |
|------------|--------|------------|
| Lexical search only | Simplicity, no ML dependencies | AI generates OR queries for synonyms |
| Single-node only | Target: personal document collections | Vertical scaling |
| STDIO only | Claude Desktop requirement | Could add SSE transport |
| No auth | Single-user desktop deployment | OS-level sandboxing |

---

## Future Enhancement Ideas

- **Incremental crawling**: Track last crawl time, skip unchanged files
- **OCR support**: Tesseract for scanned documents (high complexity)
- **Semantic search**: Vector embeddings (very high complexity)
- **Multi-language analyzers**: Language-specific stemming
- **HTTP/SSE transport**: For browser-based clients

---

## Key Principles

- **Simple > Complex**
- **User-friendly > Feature-rich**
- **Fast > Perfect**
- **Working > Theoretically optimal**

When in doubt, keep it simple and update README.md.
