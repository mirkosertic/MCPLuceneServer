# MCP Lucene Server - AI Assistant Guide

This document provides comprehensive information about the MCP Lucene Server project for AI assistants working on this codebase.

## Table of Contents

- [Project Overview](#project-overview)
- [Architecture](#architecture)
- [Technical Implementation](#technical-implementation)
- [Configuration System](#configuration-system)
- [MCP Tools Design Philosophy](#mcp-tools-design-philosophy)
- [Performance Considerations](#performance-considerations)
- [Limitations and Constraints](#limitations-and-constraints)
- [Development Guidelines](#development-guidelines)
- [Common Tasks](#common-tasks)

---

## Project Overview

### Purpose

MCP Lucene Server is a **Model Context Protocol (MCP) server** that exposes Apache Lucene's full-text search capabilities through a conversational interface. It allows AI assistants (like Claude) to help users search, index, and manage document collections without requiring technical knowledge of Lucene or search engines.

### Key Capabilities

1. **Automatic Document Indexing**: Crawls configured directories and indexes PDFs, Office documents, OpenOffice files, and plain text files
2. **Full-Text Search**: Provides Lucene query syntax with field-specific filtering and faceted search
3. **Real-Time Monitoring**: Watches directories for changes and automatically updates the index
4. **Runtime Configuration**: Allows users to manage crawlable directories through conversational MCP tools
5. **Rich Metadata**: Extracts author, title, language, dates, and other metadata from documents

### Target Users

- Non-technical users who want to search their document collections
- Power users who need advanced search capabilities without managing a search server
- Users who prefer conversational interfaces over CLI/GUI tools

---

## Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────┐
│           Claude Desktop / MCP Client               │
└────────────────┬────────────────────────────────────┘
                 │ STDIO Transport
                 │ (JSON-RPC over stdin/stdout)
                 ▼
┌─────────────────────────────────────────────────────┐
│         Spring AI MCP Server Framework              │
│  ┌──────────────────────────────────────────────┐   │
│  │        LuceneSearchTools (MCP Tools)         │   │
│  └──────────────────────────────────────────────┘   │
└─────────┬───────────────────────────┬───────────────┘
          │                           │
          ▼                           ▼
┌──────────────────────┐    ┌──────────────────────┐
│  LuceneIndexService  │    │ DocumentCrawler      │
│  - Search            │    │ Service              │
│  - Indexing          │    │ - File Discovery     │
│  - Field Schema      │    │ - Content Extract    │
│  - NRT Manager       │    │ - Batch Processing   │
└──────────┬───────────┘    └──────────┬───────────┘
           │                           │
           ▼                           ▼
┌─────────────────────────────────────────────────────┐
│              Apache Lucene 10.1                     │
│  - IndexWriter                                      │
│  - IndexSearcher (NRT)                              │
│  - StandardAnalyzer                                 │
│  - FacetsCollector                                  │
└─────────────────────────────────────────────────────┘
```

### Component Responsibilities

#### 1. **LuceneSearchTools** (`LuceneSearchTools.java`)
- **Role**: MCP tool endpoints - the interface between Claude and the system
- **Responsibilities**:
  - Expose MCP tools (`@McpTool` annotations)
  - Validate user inputs
  - Format responses for MCP clients
  - Handle errors and return user-friendly messages
- **Key Methods**:
  - `search()`: Full-text search with pagination and filtering
  - `startCrawl()`: Trigger directory crawling
  - `getCrawlerStats()`: Real-time crawler progress
  - `listCrawlableDirectories()`: Show configured directories
  - `addCrawlableDirectory()`: Add directory at runtime
  - `removeCrawlableDirectory()`: Remove directory at runtime

#### 2. **LuceneIndexService** (`LuceneIndexService.java`)
- **Role**: Core Lucene operations manager
- **Responsibilities**:
  - Manage IndexWriter and IndexSearcher lifecycle
  - Provide NRT (Near Real-Time) search
  - Handle commits and refreshes
  - Manage faceted search configuration
- **Key Concepts**:
  - **NRT Refresh**: Controls how quickly new documents appear in search results
  - **Dynamic NRT Interval**: Slows refresh during bulk indexing to reduce overhead
  - **Facets**: Efficient category-based filtering using SortedSetDocValues

#### 3. **DocumentCrawlerService** (`DocumentCrawlerService.java`)
- **Role**: Document discovery and indexing orchestrator
- **Responsibilities**:
  - Walk directory trees to discover files
  - Filter files by include/exclude patterns
  - Queue documents for batch processing
  - Coordinate parallel crawling across directories
  - Manage crawler state (IDLE, CRAWLING, PAUSED, WATCHING)
  - Handle directory watching for real-time updates
- **Design Pattern**: Producer-Consumer with batch processing
  - **Producers**: Directory walker threads
  - **Queue**: LinkedBlockingQueue for documents
  - **Consumer**: Batch processor thread

#### 4. **FileContentExtractor** (`FileContentExtractor.java`)
- **Role**: Document parsing and content extraction
- **Responsibilities**:
  - Use Apache Tika to extract text from binary formats
  - Detect document language automatically
  - Extract metadata (author, title, creation date, etc.)
  - Compute content hash (SHA-256) for change detection
- **Supported Formats**: PDF, DOC, DOCX, XLS, XLSX, PPT, PPTX, ODT, ODS, ODP, TXT

#### 5. **DocumentIndexer** (`DocumentIndexer.java`)
- **Role**: Lucene document builder
- **Responsibilities**:
  - Create standardized Lucene documents with consistent field schema
  - Handle document updates (delete old, index new) using content hash
  - Configure facet fields for filtering
- **Field Schema**: See [Index Field Schema](#index-field-schema) below

#### 6. **CrawlerConfigurationManager** (`CrawlerConfigurationManager.java`)
- **Role**: Persistent configuration storage
- **Responsibilities**:
  - Load/save directories from `~/.mcplucene/config.yaml`
  - Handle configuration priority chain (env var > config file > default)
  - Thread-safe operations with synchronized methods
  - Graceful error handling with fallback to empty list

#### 7. **DirectoryWatcherService** (`DirectoryWatcherService.java`)
- **Role**: File system change monitoring
- **Responsibilities**:
  - Use Java WatchService to monitor directories
  - Detect file create, modify, delete events
  - Trigger incremental index updates
  - Handle recursive directory watching

---

## Technical Implementation

### Index Field Schema

Every indexed document contains these fields:

| Field | Type | Stored | Indexed | Faceted | Description |
|-------|------|--------|---------|---------|-------------|
| `content` | TextField | YES | YES | NO | Full document text content |
| `snippet` | StringField | YES | NO | NO | Search result excerpt (max 300 chars) |
| `file_path` | StringField | YES | YES | NO | Absolute file path (unique ID) |
| `file_name` | TextField | YES | YES | NO | File name only |
| `file_extension` | StringField | YES | YES | YES | Extension (pdf, docx, etc.) |
| `file_type` | StringField | YES | YES | YES | MIME type |
| `file_size` | LongPoint | YES | YES | NO | File size in bytes |
| `title` | TextField | YES | YES | NO | Document title |
| `author` | TextField | YES | YES | YES | Author name (multi-valued) |
| `creator` | TextField | YES | YES | YES | Creator application (multi-valued) |
| `subject` | TextField | YES | YES | YES | Document subject (multi-valued) |
| `keywords` | TextField | YES | YES | NO | Keywords/tags |
| `language` | StringField | YES | YES | YES | ISO 639-1 language code |
| `created_date` | LongPoint | YES | YES | NO | Creation timestamp (ms) |
| `modified_date` | LongPoint | YES | YES | NO | Modification timestamp (ms) |
| `indexed_date` | LongPoint | YES | YES | NO | Indexing timestamp (ms) |
| `content_hash` | StringField | YES | YES | NO | SHA-256 hash for change detection |

**Field Types Explained**:
- **TextField**: Analyzed (tokenized, lowercased), supports full-text search
- **StringField**: Not analyzed (exact matching), efficient for filtering
- **LongPoint**: Numeric field, supports range queries

### Lucene Analyzer Configuration

**StandardAnalyzer** is used for all text fields:
- ✅ **Tokenization**: Splits on whitespace and punctuation
- ✅ **Lowercasing**: Converts to lowercase for case-insensitive search
- ✅ **Stopword Filtering**: Removes common words (the, a, is, etc.)
- ❌ **NO Stemming**: "running" won't match "run"
- ❌ **NO Synonyms**: "car" won't match "automobile"
- ❌ **NO Phonetic**: "Smith" won't match "Smyth"

**Implications for Search**:
- Users must explicitly provide synonyms: `(car OR automobile OR vehicle)`
- Wildcards needed for variations: `contract*` matches contracts, contracting
- Phrase queries require exact word order: `"machine learning"`

### NRT (Near Real-Time) Optimization

**Normal Operation** (< 1000 files):
- Refresh interval: 100ms
- New documents appear in search within ~100ms after indexing
- Minimal latency, slight CPU overhead

**Bulk Indexing** (≥ 1000 files):
- Automatically switches to 5000ms refresh interval
- Reduces CPU overhead during large crawls
- Restores 100ms interval after crawl completes

**Why This Matters**:
- Prevents excessive CPU usage during bulk operations
- Maintains responsiveness for interactive search during normal operation
- Automatically adapts without user configuration

### Batch Processing Architecture

**Design Goals**:
- Minimize commit overhead (commits are expensive)
- Keep memory usage bounded
- Provide progress feedback

**Implementation**:
```
Directory Walker Thread 1 ──┐
Directory Walker Thread 2 ──┤
Directory Walker Thread 3 ──┼──> LinkedBlockingQueue ──> Batch Processor
Directory Walker Thread 4 ──┘      (bounded queue)        (single thread)
```

**Batch Processor Logic**:
1. Collect documents into batch (default: 100 documents)
2. Commit batch when either:
   - Batch size reached (100 documents)
   - Timeout expired (5000ms since last commit)
3. Continue until queue empty and all walkers finished

**Configuration Knobs**:
- `thread-pool-size`: Number of parallel directory walkers (default: 4)
- `batch-size`: Documents per commit (default: 100)
- `batch-timeout-ms`: Max time between commits (default: 5000)

### Configuration Priority Chain

The system supports multiple configuration sources with clear precedence:

```
1. Environment Variable (highest priority)
   LUCENE_CRAWLER_DIRECTORIES=/path1,/path2
   ↓ (if not set)

2. User Config File (runtime manageable)
   ~/.mcplucene/config.yaml
   ↓ (if empty or not exists)

3. Application Default (lowest priority)
   src/main/resources/application.yaml
```

**Why This Design?**:
- **Environment Variable**: Allows deployment-specific overrides (Docker, CI/CD)
- **User Config File**: Enables runtime management via MCP tools without restarts
- **Application Default**: Provides sensible defaults for development

**Important Constraint**:
When `LUCENE_CRAWLER_DIRECTORIES` is set, the MCP tools `addCrawlableDirectory` and `removeCrawlableDirectory` return an error. This prevents confusion where users make changes that are immediately overridden.

---

## Configuration System

### Spring Boot Profiles

| Profile | Purpose | Config File | Logging | Web Server |
|---------|---------|-------------|---------|------------|
| **default** | Development | `application.yaml` | Full | Enabled |
| **deployed** | Production | `application-deployed.yaml` | Disabled | Disabled |

**Critical for MCP**: The `deployed` profile disables console logging, which is required for clean STDIO communication. Without this, JSON-RPC messages get corrupted by log output.

### External Configuration File

**Location**: `~/.mcplucene/config.yaml`

**Format**:
```yaml
lucene:
  crawler:
    directories:
      - /path/to/documents
      - /path/to/other/docs
```

**Managed By**: `CrawlerConfigurationManager`

**Thread Safety**: All operations synchronized to prevent concurrent modification

**Error Handling**: Graceful degradation - returns empty list on parse errors

### Configuration Properties

See `CrawlerProperties.java` for all available settings. Key properties:

**Performance Tuning**:
- `thread-pool-size`: 4 (parallel crawlers)
- `batch-size`: 100 (documents per commit)
- `batch-timeout-ms`: 5000 (max time between commits)

**NRT Optimization**:
- `bulk-index-threshold`: 1000 (files before NRT slowdown)
- `slow-nrt-refresh-interval-ms`: 5000 (NRT interval during bulk)

**Content Extraction**:
- `max-content-length`: -1 (unlimited, or max characters)
- `extract-metadata`: true (extract author, title, etc.)
- `detect-language`: true (auto-detect language)

**File Patterns**:
- `include-patterns`: File globs to include (*.pdf, *.docx, etc.)
- `exclude-patterns`: File globs to exclude (**/node_modules/**, **/.git/**)

---

## MCP Tools Design Philosophy

### 1. **User-Centric Design**

**Principle**: Tools should work the way users think, not the way the system works.

**Example**: Instead of exposing low-level Lucene concepts, we provide:
- `search()`: "Search for documents about X"
- `startCrawl()`: "Index my documents"
- `addCrawlableDirectory()`: "Add this folder to search"

**Anti-Pattern**: Exposing internal concepts like "commitIndexWriter" or "optimizeSegments"

### 2. **Conversational Interaction**

**Principle**: Tools should support natural language requests through AI mediation.

**Example**:
- User: "Find all PDFs about machine learning"
- Claude interprets as: `search(query="machine learning", filterField="file_extension", filterValue="pdf")`

**Why This Works**:
- Claude understands user intent
- Claude knows which parameters to use
- User gets results without knowing Lucene syntax

### 3. **Progressive Disclosure**

**Principle**: Simple things should be simple, complex things should be possible.

**Example - Simple**:
```
User: "Search for contract"
Claude: search(query="contract")
```

**Example - Complex**:
```
User: "Find German PDFs about technology from 2024"
Claude: search(
  query="(Technologie OR Technology)",
  filterField="language",
  filterValue="de",
  filterField2="file_extension",
  filterValue2="pdf"
)
```

### 4. **Self-Documenting Responses**

**Principle**: Responses should include context that helps users understand and refine their queries.

**Example - Facets**:
```json
{
  "facets": {
    "language": [
      {"value": "en", "count": 150},
      {"value": "de", "count": 45},
      {"value": "fr", "count": 12}
    ]
  }
}
```

This allows Claude to suggest: "I found mostly English documents. Would you like to filter to German documents only?"

### 5. **Graceful Degradation**

**Principle**: Tools should work even when inputs are imperfect.

**Examples**:
- Missing optional parameters: Use sensible defaults
- Invalid query syntax: Return error with helpful message
- Directory doesn't exist: Validate before adding, return clear error
- Index empty: Return 0 results, not an error

### 6. **Idempotency Where Possible**

**Principle**: Running the same operation twice should be safe.

**Examples**:
- `addCrawlableDirectory("/path")`: If already added, returns success with "already configured" message
- `startCrawl(fullReindex=false)`: Re-indexes only changed files (via content hash)

---

## Performance Considerations

### Indexing Performance

**Bottlenecks** (in order of impact):
1. **Disk I/O**: Reading large files from disk
2. **Content Extraction**: Apache Tika parsing PDFs, Office docs
3. **Language Detection**: CPU-intensive text analysis
4. **Lucene Indexing**: Writing to index, merging segments

**Optimization Strategies**:

**1. Parallel Directory Crawling**
- Each directory crawled by separate thread
- Configured via `thread-pool-size` (default: 4)
- Increase for multi-core CPUs, decrease for slow disks

**2. Batch Processing**
- Commits are expensive (flush to disk + merge segments)
- Batching reduces commit frequency
- Configure via `batch-size` (default: 100)

**3. NRT Interval Adjustment**
- Normal: 100ms refresh (responsive search during interactive use)
- Bulk: 5000ms refresh (reduces CPU during large crawls)
- Automatic switch at `bulk-index-threshold` (default: 1000 files)

**4. Content Limits**
- Very large files can cause OOM errors
- Set `max-content-length` to truncate (e.g., 5MB = 5242880 characters)
- Trade-off: Truncation may miss content at end of documents

**5. Feature Toggles**
- Disable `detect-language` if language filtering not needed
- Disable `extract-metadata` if author/title not needed
- Each feature adds CPU overhead

### Search Performance

**Fast Operations**:
- ✅ Simple term queries: `contract` (~5-10ms)
- ✅ Field filters: `file_extension:pdf` (~2-5ms)
- ✅ Faceted search: Counted from result set, very fast
- ✅ Phrase queries: `"exact phrase"` (~10-20ms)

**Slow Operations**:
- ⚠️ Prefix wildcards: `*test` (requires full scan)
- ⚠️ Complex boolean: `(A AND B) OR (C AND D) AND ...` (many sub-queries)
- ⚠️ Large result sets: Returning 1000s of results takes time to serialize

**Optimization Tips**:
- Use suffix wildcards instead of prefix: `test*` not `*test`
- Leverage facets for filtering instead of complex boolean queries
- Use pagination: Default 10 results, max 100 per page

### Memory Usage

**Index Size**: ~5-10% of original document size (compressed)

**Example**:
- 10GB of PDFs → ~500MB-1GB index
- 100GB of documents → ~5-10GB index

**Runtime Memory**:
- **Minimum**: 512MB heap (`-Xmx512m`)
- **Recommended**: 1-2GB heap (`-Xmx2g`)
- **Large Collections**: 4GB+ for 100k+ documents

**Memory Pressure Points**:
- Document batch queue: Bounded by `batch-size`
- NRT SearcherManager: Caches recent queries
- Apache Tika: Can use significant memory for large files

---

## Limitations and Constraints

### 1. **Lexical Search Only**

**Limitation**: No semantic search, no automatic synonym expansion

**Impact**:
- "car" won't match "automobile"
- "running" won't match "run"
- User must provide synonyms explicitly

**Workaround**:
- Use wildcards: `run*` matches run, running, runner
- Use OR for synonyms: `(car OR automobile OR vehicle)`
- Educate users via Claude's guidance

**Why Not Fix?**:
- Semantic search requires embedding models (large, slow, complex)
- Stemming can cause false matches (organize → organ)
- Project goal is simple, fast, dependency-light solution

### 2. **Single-Node Only**

**Limitation**: No distributed search, no horizontal scaling

**Impact**:
- Limited to single machine's disk, CPU, RAM
- No high availability
- No load balancing

**Workaround**:
- Vertical scaling: More RAM, faster CPU, SSD
- Index partitioning: Separate indices for different document sets

**Why Not Fix?**:
- Distributed Lucene (Solr, Elasticsearch) adds significant complexity
- Target use case is personal document collections, not web-scale search

### 3. **Text-Only Search**

**Limitation**: No image search, no audio transcription

**Impact**:
- Images in PDFs ignored
- Screenshots can't be searched
- Audio/video files not supported

**Workaround**:
- Extract text from images using OCR before indexing (external tool)
- Transcribe audio/video with Whisper or similar (external tool)

**Why Not Fix?**:
- OCR and speech recognition are complex, slow, and require ML models
- Apache Tika doesn't provide these capabilities out of the box

### 4. **Limited File Format Support**

**Supported**: PDF, Office (DOC, DOCX, XLS, XLSX, PPT, PPTX), OpenOffice (ODT, ODS, ODP), Plain text (TXT)

**Not Supported**: Markdown, HTML, source code, custom formats

**Impact**:
- README.md files ignored
- HTML pages ignored
- Source code files without .txt extension ignored

**Workaround**:
- Add include patterns: `*.md`, `*.html`
- Tika can parse many formats, just not enabled by default

**Why Not Fix?**:
- Target use case is "documents", not code or web content
- Can be easily extended by modifying `include-patterns`

### 5. **STDIO Transport Only**

**Limitation**: Only works with MCP clients that support STDIO (like Claude Desktop)

**Impact**:
- Can't use HTTP/SSE transport
- Can't access via web browser
- Can't use with clients that only support HTTP

**Workaround**:
- Spring AI MCP Server supports SSE transport - could be added
- Would require separate configuration profile

**Why Not Fix?**:
- STDIO is the primary MCP transport for desktop clients
- HTTP/SSE adds complexity and security considerations

### 6. **No Access Control**

**Limitation**: No user authentication, no permission checking

**Impact**:
- Anyone with access to the MCP server can search/modify index
- No multi-user support
- No audit logging

**Workaround**:
- Run server in user's local environment (sandboxed by OS)
- Don't expose server over network
- File system permissions provide baseline security

**Why Not Fix?**:
- Target deployment is single-user desktop environment
- Adding auth would complicate setup significantly

---

## Development Guidelines

### When Making Changes

**ALWAYS use the `implement-review-loop` agent for code changes**:
- Ensures code review before implementation
- Maintains code quality standards
- Prevents accidental breaking changes

### Code Style

**Java Conventions**:
- Use `final` for parameters and local variables where possible
- Prefer composition over inheritance
- Use constructor injection for dependencies
- Annotate nullability with `@Nullable` / `@NotNull` if using

**Logging**:
- Use SLF4J: `private static final Logger logger = LoggerFactory.getLogger(ClassName.class);`
- Log levels:
  - `ERROR`: System errors, exceptions that affect functionality
  - `WARN`: Unexpected situations, degraded functionality
  - `INFO`: Important state changes, user actions
  - `DEBUG`: Detailed execution flow (not used much currently)

**Error Handling**:
- Catch specific exceptions, not `Exception`
- Always include error message in MCP tool responses
- Log exceptions with full stack trace
- Graceful degradation: Return partial results instead of failing completely

### Testing Strategy

**Current State**: Minimal tests (project is early stage)

**Test Priorities**:
1. **MCP Tool Integration Tests**: Ensure tools return correct format
2. **Configuration Loading**: Test priority chain, file parsing
3. **Search Query Parsing**: Test various Lucene query syntaxes
4. **Content Extraction**: Test with sample PDFs, Office docs

**Testing Challenges**:
- Lucene requires real index on disk (can't easily mock)
- Tika parsing is slow (use small sample files)
- Directory watching requires real file system

### Adding New MCP Tools

**Checklist**:
1. Add method to `LuceneSearchTools.java`
2. Annotate with `@McpTool(name="...", description="...")`
3. Use `@McpToolParam` for all parameters with clear descriptions
4. Return `Map<String, Object>` with consistent structure:
   - Always include `"success": boolean`
   - On error, include `"error": string` message
   - On success, include relevant data fields
5. Add comprehensive logging (INFO for calls, ERROR for failures)
6. Handle all exceptions and return user-friendly errors
7. Update README.md with tool documentation
8. Update CLAUDE.md with design rationale

**Example Template**:
```java
@McpTool(name = "myNewTool", description = "Clear description of what this does")
public Map<String, Object> myNewTool(
    @McpToolParam(description = "What this parameter does", required = true)
    String param1
) {
    logger.info("My new tool called with param1={}", param1);

    Map<String, Object> response = new HashMap<>();

    try {
        // Implementation
        response.put("success", true);
        response.put("result", someResult);

    } catch (Exception e) {
        logger.error("Error in myNewTool", e);
        response.put("success", false);
        response.put("error", "User-friendly error: " + e.getMessage());
    }

    return response;
}
```

### Extending File Format Support

**To add new file types** (e.g., Markdown, HTML):

1. **Update `CrawlerProperties.java`**:
   ```yaml
   include-patterns:
     - "*.md"
     - "*.html"
   ```

2. **No code changes needed** - Apache Tika automatically handles most text formats

3. **For custom formats**:
   - Extend `FileContentExtractor.java`
   - Add custom parser before Tika fallback

### Performance Tuning Guidelines

**Before Optimizing**:
1. Measure actual performance (don't guess)
2. Identify bottleneck (CPU? Disk I/O? Memory?)
3. Profile with JVisualVM or similar
4. Have a performance target (e.g., "index 10k files in X minutes")

**Common Optimization Areas**:

**1. Too Slow Indexing**:
- Increase `thread-pool-size` (if CPU not maxed)
- Increase `batch-size` (reduces commit frequency)
- Disable `detect-language` or `extract-metadata`
- Set `max-content-length` to skip large portions

**2. Too Slow Searching**:
- Reduce `pageSize` in responses
- Check query complexity (avoid prefix wildcards)
- Consider index optimization: `indexWriter.forceMerge(1)` (expensive!)

**3. Memory Issues**:
- Reduce `thread-pool-size` (fewer concurrent operations)
- Reduce `batch-size` (smaller in-memory queue)
- Set `max-content-length` to limit document size
- Increase JVM heap: `-Xmx4g`

---

## Common Tasks

### Task: Add a New Facet Field

**Example**: Add "file_creator" as a facet for filtering by creation software

1. **Update `DocumentIndexer.java`**:
   ```java
   // In createDocument() method, after existing facets:
   facetsConfig.setMultiValued("file_creator", true);
   Document facetDoc = facetsConfig.build(taxoWriter, doc);
   ```

2. **Update field schema** in `CLAUDE.md` and `README.md`

3. **Test** with documents that have creator metadata

### Task: Change Default Batch Size

1. **Update `CrawlerProperties.java`**:
   ```java
   private int batchSize = 200; // Changed from 100
   ```

2. **Update `application.yaml`** default configuration

3. **Update documentation** to reflect new default

### Task: Add Support for New File Type

**Example**: Add `.html` files

1. **Update `application.yaml`**:
   ```yaml
   include-patterns:
     - "*.html"
   ```

2. **Test** that Tika correctly parses HTML (it should)

3. **If Tika doesn't handle well**:
   - Add custom parser in `FileContentExtractor.java`
   - Use JSoup or similar library

### Task: Debug STDIO Communication Issues

**Symptoms**:
- Server shows "running" but tools don't work
- Garbled responses in Claude Desktop

**Debugging Steps**:

1. **Check profile**: Must use `deployed` profile
   ```json
   "args": ["-Dspring.profiles.active=deployed", "-jar", "..."]
   ```

2. **Check console logging**: Must be disabled in `application-deployed.yaml`
   ```yaml
   logging:
     level:
       root: OFF
   ```

3. **Test manually**:
   ```bash
   java -Dspring.profiles.active=deployed -jar target/luceneserver-*.jar
   # Should see only MCP initialization message, no Spring logs
   ```

4. **Check for stdout pollution**:
   - Search codebase for `System.out.println`
   - Ensure no libraries write to stdout

### Task: Optimize Index for Production

**After initial bulk indexing**, optimize the index:

1. **Add optimization tool** (currently missing):
   ```java
   @McpTool(name = "optimizeIndex",
       description = "Optimize index for faster searches (slow operation)")
   public Map<String, Object> optimizeIndex() {
       // Call indexWriter.forceMerge(1)
       // This merges all segments into one, improving search speed
   }
   ```

2. **Warning**: Very slow for large indices, blocks indexing during operation

---

## Architectural Decisions and Rationale

### Why Spring Boot?

**Pros**:
- Dependency injection simplifies component wiring
- Configuration management (profiles, properties)
- Auto-configuration reduces boilerplate
- Well-documented, widely used

**Cons**:
- Heavyweight for a simple search server
- Startup time ~2-3 seconds
- Large JAR size (~50MB)

**Decision**: Accepted trade-off. Developer productivity and maintainability > binary size

### Why STDIO Transport?

**Pros**:
- Simple to implement (stdin/stdout)
- No network security concerns
- Works perfectly with Claude Desktop
- Low latency (no HTTP overhead)

**Cons**:
- Only works with subprocess-based MCP clients
- Can't access via browser
- Debug logging must be disabled (can't use stdout)

**Decision**: Target use case (Claude Desktop) makes STDIO ideal

### Why StandardAnalyzer?

**Pros**:
- Simple, well-understood
- Fast
- Good for English and Western languages
- No external dependencies

**Cons**:
- No stemming (run/running different terms)
- No synonyms
- Poor for non-Western languages

**Decision**: Simplicity and performance > advanced NLP. Users can add OR clauses for synonyms.

### Why Batch Processing?

**Pros**:
- Reduces commit overhead (commits are expensive)
- Bounded memory usage (queue size = batch size)
- Simple to reason about

**Cons**:
- Delay before documents appear in search (up to batch timeout)
- More complex than simple sequential processing

**Decision**: Performance for large crawls justifies complexity

### Why NRT Interval Adjustment?

**Pros**:
- Best of both worlds: responsive search + efficient bulk indexing
- Automatic (no user configuration needed)
- Significant CPU savings during crawls

**Cons**:
- Adds complexity
- Magic threshold (1000) might not be right for all use cases

**Decision**: Performance gains worth the complexity, threshold is configurable

---

## Future Enhancements (Not Implemented)

### 1. Incremental Crawling
**Current**: Full directory scan on every crawl
**Proposed**: Track last crawl time, only check files modified since then
**Benefit**: Much faster for large, mostly-unchanged directories
**Complexity**: Medium - need to persist crawl timestamps

### 2. OCR Support
**Current**: Images in PDFs ignored
**Proposed**: Extract text from images using Tesseract
**Benefit**: Search scanned documents, screenshots
**Complexity**: High - requires external dependency, very slow

### 3. Semantic Search
**Current**: Lexical matching only
**Proposed**: Vector search using embeddings (e.g., sentence-transformers)
**Benefit**: "car" matches "automobile", conceptual similarity
**Complexity**: Very High - requires ML models, vector index, significant memory

### 4. Multi-Language Analyzers
**Current**: StandardAnalyzer for all languages
**Proposed**: Language-specific analyzers (e.g., GermanAnalyzer with stemming)
**Benefit**: Better search for non-English languages
**Complexity**: Medium - detect language, use appropriate analyzer per field

### 5. HTTP/SSE Transport
**Current**: STDIO only
**Proposed**: Add HTTP/SSE transport option
**Benefit**: Use from web browsers, HTTP-only MCP clients
**Complexity**: Low - Spring AI MCP Server already supports it

### 6. Index Statistics Dashboard
**Current**: Basic document count
**Proposed**: Detailed stats (index size, segment count, field distribution)
**Benefit**: Better understanding of index health
**Complexity**: Low - mostly exposing existing Lucene metrics

---

## Glossary

**Term** | **Definition**
---------|---------------
**MCP** | Model Context Protocol - standard for AI assistants to interact with external tools
**STDIO** | Standard Input/Output - communication via stdin/stdout pipes
**NRT** | Near Real-Time - technique for making indexed documents searchable within milliseconds
**Facet** | Category-based filter (e.g., language, file type) with document counts
**Lexical Search** | Exact word matching without semantic understanding
**IndexWriter** | Lucene component that writes documents to the index
**IndexSearcher** | Lucene component that searches the index
**Segment** | Lucene's unit of index storage - multiple segments are merged over time
**Analyzer** | Lucene component that converts text to searchable tokens
**TextField** | Lucene field type that is analyzed (tokenized) for full-text search
**StringField** | Lucene field type stored as-is for exact matching
**SortedSetDocValues** | Lucene's efficient storage for faceted search
**Apache Tika** | Library for extracting text and metadata from binary documents
**SnakeYAML** | Java library for parsing YAML configuration files

---

## Contact and Contributions

**For AI Assistants Working on This Project**:

1. **Use the `implement-review-loop` agent** for all code changes
2. **Read this document** before making architectural changes
3. **Update this document** when adding features or changing design decisions
4. **Maintain backward compatibility** in MCP tool interfaces
5. **Test with real documents** - don't rely only on unit tests
6. **Consider user experience** - tools should feel conversational, not technical

**Key Principles**:
- Simple > Complex
- User-friendly > Feature-rich
- Fast > Perfect
- Working > Theoretically optimal
