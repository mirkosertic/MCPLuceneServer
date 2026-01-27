# MCP Lucene Server

[![Build and Release](https://github.com/mirkosertic/MCPLuceneServer/actions/workflows/build.yml/badge.svg)](https://github.com/mirkosertic/MCPLuceneServer/actions/workflows/build.yml)

A Model Context Protocol (MCP) server that exposes Apache Lucene fulltext search capabilities with automatic document crawling and indexing. This server uses STDIO transport for communication and can be integrated with Claude Desktop or any other MCP-compatible client.

## Features

✨ **Automatic Document Crawling**
- Automatically indexes PDFs, Microsoft Office, and OpenOffice documents
- Multi-threaded crawling for fast indexing
- Real-time directory monitoring for automatic updates

🔍 **Powerful Search**
- Full Lucene query syntax support (wildcards, boolean operators, phrase queries)
- Field-specific filtering (by author, language, file type, etc.)
- Highlighted search snippets showing terms in context
- Paginated results with filter suggestions

📄 **Rich Metadata Extraction**
- Automatic language detection
- Author, title, creation date extraction
- File type and size information
- SHA-256 content hashing for change detection

⚡ **Performance Optimized**
- Batch processing for efficient indexing
- NRT (Near Real-Time) search with dynamic optimization
- Configurable thread pools for parallel processing
- Progress notifications during bulk operations

🔧 **Easy Integration**
- STDIO transport for seamless MCP client integration
- Comprehensive MCP tools for search and crawler control
- Flexible configuration via YAML
- Cross-platform notifications (macOS Notification Center, Windows Toast, Linux notify-send)

## Table of Contents

- [Quick Start](#quick-start)
- [Prerequisites](#prerequisites)
- [Building the Server](#building-the-server)
- [Configuration Options](#configuration-options)
  - [Document Crawler Configuration](#document-crawler-configuration)
- [Integrating with Claude Desktop](#integrating-with-claude-desktop)
- [Available MCP Tools](#available-mcp-tools)
- [Index Field Schema](#index-field-schema)
- [Document Crawler Features](#document-crawler-features)
- [Usage Examples](#usage-examples)
- [Troubleshooting](#troubleshooting)
- [Development](#development)

## Quick Start

### Option 1: Configure via MCP Tools (Recommended)

```bash
# 1. Build the server
./mvnw clean package -DskipTests

# 2. Configure in Claude Desktop (see below)

# 3. Use Claude to configure directories via MCP tools:
# "Add /Users/yourname/Documents as a crawlable directory"
# Claude will call addCrawlableDirectory() for you
```

The configuration is automatically saved to `~/.mcplucene/config.yaml` and persists across restarts.

### Option 2: Manual Configuration

```bash
# 1. Configure directories to index
# Edit src/main/resources/application.yaml:
lucene:
  crawler:
    directories:
      - "/path/to/your/documents"

# 2. Build the server
./mvnw clean package -DskipTests

# 3. The JAR file is created at target/luceneserver-0.0.1-SNAPSHOT.jar
```

Then configure your MCP client (see [Claude Desktop Integration](#integrating-with-claude-desktop) below).

The crawler will automatically start indexing your documents when the server starts. You can then search through them using Claude or any MCP-compatible client.

## Prerequisites

- Java 21 or later
- Maven 3.9+ (or use the included Maven wrapper)

## Getting the Server

### Option 1: Download Pre-built JAR (Recommended)

Download the latest pre-built JAR from GitHub Actions:

1. Go to the [Actions tab](https://github.com/mirkosertic/MCPLuceneServer/actions/workflows/build.yml)
2. Click on the most recent successful workflow run
3. Scroll down to "Artifacts" and download `luceneserver-X.X.X-SNAPSHOT`
4. Extract the ZIP file to get the JAR

For tagged releases, you can also download from the [Releases page](https://github.com/mirkosertic/MCPLuceneServer/releases).

### Option 2: Build from Source

```bash
./mvnw clean package -DskipTests
```

This creates an executable JAR at `target/luceneserver-0.0.1-SNAPSHOT.jar`.

## Configuration Options

The server can be configured via environment variables, `application.yaml`, and Spring Boot profiles:

### Spring Boot Profiles

The server supports two profiles:

| Profile | Usage | Configuration File |
|---------|-------|-------------------|
| **default** | Development in IDE | `application.yaml` |
| **deployed** | Production/Claude Desktop | `application-deployed.yaml` |

**Default profile (no profile specified):**
- Full logging enabled
- Spring Boot banner visible
- Suitable for debugging and development

**Deployed profile (`-Dspring.profiles.active=deployed`):**
- Console logging disabled (required for STDIO transport)
- Spring Boot banner disabled
- Web server disabled
- Used when running under Claude Desktop or other MCP clients

### Environment Variables

| Environment Variable | Default                                | Description |
|---------------------|----------------------------------------|-------------|
| `LUCENE_INDEX_PATH` | `${user.home}/.mcplucene/luceneindex}` | Path to the Lucene index directory |
| `LUCENE_CRAWLER_DIRECTORIES` | none                                   | Comma-separated list of directories to crawl (overrides config file) |
| `SPRING_PROFILES_ACTIVE` | none (default)                         | Set to `deployed` for production use |

**Note on `LUCENE_CRAWLER_DIRECTORIES`:**
When this environment variable is set, it takes precedence over `~/.mcplucene/config.yaml` and `application.yaml`. The MCP configuration tools (`addCrawlableDirectory`, `removeCrawlableDirectory`) will refuse to modify configuration while this override is active. To use runtime configuration, remove this environment variable.

### Document Crawler Configuration

The crawler directories can be configured in three ways, with the following priority (highest to lowest):

1. **Environment Variable**: `LUCENE_CRAWLER_DIRECTORIES` (comma-separated paths)
2. **Runtime Configuration**: `~/.mcplucene/config.yaml` (managed via MCP tools)
3. **Application Default**: `src/main/resources/application.yaml`

#### Runtime Configuration via MCP Tools (Recommended)

The server provides MCP tools to manage crawlable directories at runtime without editing configuration files:

**`listCrawlableDirectories`** - List all configured directories
```
Ask Claude: "What directories are being crawled?"
```

**`addCrawlableDirectory`** - Add a new directory to crawl
```
Ask Claude: "Add /Users/yourname/Documents as a crawlable directory"
Ask Claude: "Add /path/to/folder and start crawling it immediately"
```

**`removeCrawlableDirectory`** - Remove a directory from crawling
```
Ask Claude: "Stop crawling /Users/yourname/Downloads"
```

**Benefits of Runtime Configuration:**
- No need to rebuild the JAR or restart the server
- Configuration persists across restarts in `~/.mcplucene/config.yaml`
- Easy to distribute pre-built JARs
- Conversational interface via Claude

**Configuration File Location:**
```
~/.mcplucene/config.yaml
```

**Example config.yaml:**
```yaml
lucene:
  crawler:
    directories:
      - /Users/yourname/Documents
      - /Users/yourname/Downloads
```

#### Static Configuration via application.yaml

Configure the document crawler in `src/main/resources/application.yaml`:

```yaml
lucene:
  index:
    path: ${LUCENE_INDEX_PATH:./lucene-index}
  crawler:
    # Directories to crawl and index
    directories:
      - "/path/to/your/documents"
      - "/another/path/to/index"

    # File patterns to include
    include-patterns:
      - "*.pdf"
      - "*.doc"
      - "*.docx"
      - "*.odt"
      - "*.ppt"
      - "*.pptx"
      - "*.xls"
      - "*.xlsx"
      - "*.ods"

    # File patterns to exclude
    exclude-patterns:
      - "**/node_modules/**"
      - "**/.git/**"
      - "**/target/**"
      - "**/build/**"

    # Performance settings
    thread-pool-size: 4                    # Parallel crawling threads
    batch-size: 100                        # Documents per batch
    batch-timeout-ms: 5000                 # Batch processing timeout

    # Directory watching
    watch-enabled: true                    # Monitor directories for changes
    watch-poll-interval-ms: 2000          # Watch polling interval

    # NRT optimization
    bulk-index-threshold: 1000            # Files before NRT slowdown
    slow-nrt-refresh-interval-ms: 5000    # NRT interval during bulk indexing

    # Content extraction
    max-content-length: -1                 # -1 = unlimited, or max characters
    extract-metadata: true                 # Extract author, title, etc.
    detect-language: true                  # Auto-detect document language

    # Auto-crawl
    crawl-on-startup: true                 # Start crawling on server startup

    # Progress notifications
    progress-notification-files: 100       # Notify every N files
    progress-notification-interval-ms: 30000  # Or every N milliseconds
```

**Supported File Formats:**
- PDF documents (`.pdf`)
- Microsoft Office: Word (`.doc`, `.docx`), Excel (`.xls`, `.xlsx`), PowerPoint (`.ppt`, `.pptx`)
- OpenOffice/LibreOffice: Writer (`.odt`), Calc (`.ods`), Impress (`.odp`)

**Complete Configuration Example:**

```yaml
lucene:
  index:
    path: /Users/yourname/lucene-index
  crawler:
    # Add your document directories here
    directories:
      - "/Users/yourname/Documents"
      - "/Users/yourname/Downloads"
      - "/Volumes/ExternalDrive/Archive"

    # Include only these file types
    include-patterns:
      - "*.pdf"
      - "*.docx"
      - "*.xlsx"

    # Exclude these directories
    exclude-patterns:
      - "**/node_modules/**"
      - "**/.git/**"

    # Performance tuning
    thread-pool-size: 8              # Use more threads for faster indexing
    batch-size: 200                  # Larger batches for better throughput

    # Auto-start crawler
    crawl-on-startup: true

    # Real-time monitoring
    watch-enabled: true

    # No content limit (index full documents)
    max-content-length: -1
```

## Integrating with Claude Desktop

This MCP server uses STDIO (standard input/output) transport, which means Claude Desktop launches the server as a subprocess and communicates via stdin/stdout.

### 1. Build the Server

```bash
./mvnw clean package -DskipTests
```

### 2. Locate the Claude Desktop Configuration File

- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

### 3. Edit the Configuration File

Add the Lucene MCP server to the `mcpServers` section:

```json
{
  "mcpServers": {
    "lucene-search": {
      "command": "java",
      "args": [
        "-Dspring.profiles.active=deployed",
        "-jar",
        "/absolute/path/to/luceneserver-0.0.1-SNAPSHOT.jar"
      ],
      "env": {
        "LUCENE_INDEX_PATH": "/path/to/your/lucene-index"
      }
    }
  }
}
```

**Important:**
- Replace `/absolute/path/to/luceneserver-0.0.1-SNAPSHOT.jar` with the actual absolute path to your built JAR file
- You can configure directories at runtime using MCP tools (recommended) or via `application.yaml` (see [Document Crawler Configuration](#document-crawler-configuration))
- The crawler will start automatically if `crawl-on-startup: true` is set

**Zero-Configuration Setup:**

The server can be distributed and run without any configuration file changes:

```json
{
  "mcpServers": {
    "lucene-search": {
      "command": "java",
      "args": [
        "-Xmx2g",
        "-Dspring.profiles.active=deployed",
        "-jar",
        "/absolute/path/to/luceneserver-0.0.1-SNAPSHOT.jar"
      ]
    }
  }
}
```

After starting the server, simply tell Claude:
```
"Add /Users/yourname/Documents as a crawlable directory and start crawling"
```

The configuration will be saved to `~/.mcplucene/config.yaml` and automatically loaded on future restarts.

**Spring Profile Configuration:**
- `-Dspring.profiles.active=deployed` - Activates the "deployed" profile for STDIO transport
  - Disables the embedded web server
  - Disables the Spring Boot startup banner
  - Disables console logging (critical for clean STDIO communication)
- When running in the IDE for debugging, omit this argument to get full logging output

### 4. Restart Claude Desktop

After saving the configuration file, fully quit and restart Claude Desktop for the changes to take effect.

### 5. Verify the Connection

Once Claude Desktop restarts, check the MCP server status in Claude Desktop's developer settings. The server should show as "running". You can verify the tools are available by asking Claude to search or get index stats.

## Available MCP Tools

### `search`

Search the Lucene fulltext index using **lexical matching** (exact word forms only).

**Parameters:**
- `query` (required): The search query using Lucene query syntax
- `filterField` (optional): Field name to filter results (use facets to discover available values)
- `filterValue` (optional): Value for the filter field
- `page` (optional): Page number, 0-based (default: 0)
- `pageSize` (optional): Results per page (default: 10, max: 100)

**⚠️ Important Search Limitations:**

The index uses Lucene's `StandardAnalyzer`, which provides:
- ✅ Tokenization and lowercasing
- ✅ Standard stopword filtering
- ❌ **NO automatic synonym expansion** (e.g., "car" won't match "automobile")
- ❌ **NO phonetic matching** (e.g., "Smith" won't match "Smyth")
- ❌ **NO stemming** (e.g., "running" won't match "run")

**💡 Best Practices for Better Results:**

1. **Generate Synonyms Yourself:** Use OR to combine related terms:
   - Instead of: `contract`
   - Use: `(contract OR agreement OR deal)`

2. **Use Wildcards for Variations:** Handle different word forms:
   - Instead of: `contract`
   - Use: `contract*` (matches contracts, contracting, contracted)

3. **Leverage Facets:** Use the returned facet values to discover exact terms in the index:
   - Check `facets.author` to find exact author names
   - Check `facets.language` to see available languages
   - Use these exact values for filtering

4. **Combine Techniques:**
   ```
   (contract* OR agreement*) AND (sign* OR execut*) AND author:"John Doe"
   ```

**Supported Query Syntax:**
- Simple terms: `hello world` (implicit AND between terms)
- Phrase queries: `"exact phrase"` (preserves word order)
- Boolean operators: `term1 AND term2`, `term1 OR term2`, `NOT term`
- Wildcards: `test*` (suffix), `te?t` (single char), `*test` (prefix, slow!)
- Field-specific search: `title:hello content:world`
- Grouping: `(contract OR agreement) AND signed`
- Range queries: `modified_date:[1609459200000 TO 1640995200000]` (timestamps in milliseconds)

**Returns:**
- Paginated document results with highlighted snippets
- Relevance scores
- Facets with actual values and counts from the result set
- Search execution time in milliseconds (`searchTimeMs`)

### `getIndexStats`

Get statistics about the Lucene index.

**Returns:**
- `documentCount`: Total number of documents in the index
- `indexPath`: Path to the index directory

### `startCrawl`

Start crawling configured directories to index documents.

**Parameters:**
- `fullReindex` (optional): If true, clears the index before crawling (default: false)

**Features:**
- Automatically extracts content from PDFs, Office documents, and OpenOffice files
- Detects document language
- Extracts metadata (author, title, creation date, etc.)
- Multi-threaded processing for fast indexing
- Progress notifications during crawling

### `getCrawlerStats`

Get real-time statistics about the crawler progress.

**Returns:**
- `filesFound`: Total files discovered
- `filesProcessed`: Files processed so far
- `filesIndexed`: Files successfully indexed
- `filesFailed`: Files that failed to process
- `bytesProcessed`: Total bytes processed
- `filesPerSecond`: Processing throughput
- `megabytesPerSecond`: Data throughput
- `elapsedTimeMs`: Time elapsed since crawl started
- `perDirectoryStats`: Statistics breakdown per directory

### `listIndexedFields`

List all field names present in the Lucene index.

**Returns:**
- `fields`: Array of field names available for searching and filtering

**Example response:**
```json
{
  "success": true,
  "fields": [
    "file_name",
    "file_path",
    "title",
    "author",
    "content",
    "language",
    "file_extension",
    "file_type",
    "created_date",
    "modified_date"
  ]
}
```

### `pauseCrawler`

Pause an ongoing crawl operation. The crawler can be resumed later with `resumeCrawler`.

### `resumeCrawler`

Resume a paused crawl operation.

### `getCrawlerStatus`

Get the current state of the crawler.

**Returns:**
- `state`: One of `IDLE`, `CRAWLING`, `PAUSED`, or `WATCHING`

### `listCrawlableDirectories`

List all configured crawlable directories.

**Returns:**
- `success`: Boolean indicating operation success
- `directories`: List of absolute directory paths currently configured
- `totalDirectories`: Count of configured directories
- `configPath`: Path to the configuration file (`~/.mcplucene/config.yaml`)
- `environmentOverride`: Boolean indicating if `LUCENE_CRAWLER_DIRECTORIES` env var is set

**Example response:**
```json
{
  "success": true,
  "directories": [
    "/Users/yourname/Documents",
    "/Users/yourname/Downloads"
  ],
  "totalDirectories": 2,
  "configPath": "/Users/yourname/.mcplucene/config.yaml",
  "environmentOverride": false
}
```

### `addCrawlableDirectory`

Add a directory to the crawler configuration.

**Parameters:**
- `path` (required): Absolute path to the directory to crawl
- `crawlNow` (optional): If true, immediately starts crawling the new directory (default: false)

**Returns:**
- `success`: Boolean indicating operation success
- `message`: Confirmation message
- `totalDirectories`: Updated count of configured directories
- `directories`: Updated list of all directories
- `crawlStarted` (optional): Present if `crawlNow=true`, indicates crawl was triggered

**Validation:**
- Directory must exist and be accessible
- Path must be a directory (not a file)
- Duplicate directories are prevented
- Fails if `LUCENE_CRAWLER_DIRECTORIES` environment variable is set

**Example:**
```
Ask Claude: "Add /Users/yourname/Documents as a crawlable directory"
Ask Claude: "Add /path/to/research and crawl it now"
```

**Configuration Persistence:**
The directory is immediately saved to `~/.mcplucene/config.yaml` and will be automatically crawled on future server restarts.

### `removeCrawlableDirectory`

Remove a directory from the crawler configuration.

**Parameters:**
- `path` (required): Absolute path to the directory to remove

**Returns:**
- `success`: Boolean indicating operation success
- `message`: Confirmation message
- `totalDirectories`: Updated count of configured directories
- `directories`: Updated list of remaining directories

**Important Notes:**
- This does NOT remove already-indexed documents from the removed directory
- To remove indexed documents, use `startCrawl(fullReindex=true)` after removing directories
- Fails if `LUCENE_CRAWLER_DIRECTORIES` environment variable is set
- The directory must exist in the current configuration

**Example:**
```
Ask Claude: "Stop crawling /Users/yourname/Downloads"
Ask Claude: "Remove /path/to/old/archive from the crawler"
```

## Index Field Schema

When documents are indexed by the crawler, the following fields are automatically extracted and stored:

### Content Fields
- **`content`**: Full text content of the document (analyzed, searchable)
- **`snippet`**: Highlighted excerpt from search results (max 300 characters)

### File Information
- **`file_path`**: Full path to the file (unique ID)
- **`file_name`**: Name of the file
- **`file_extension`**: File extension (e.g., `pdf`, `docx`)
- **`file_type`**: MIME type (e.g., `application/pdf`)
- **`file_size`**: File size in bytes

### Document Metadata
- **`title`**: Document title (extracted from metadata)
- **`author`**: Author name
- **`creator`**: Creator/application that created the document
- **`subject`**: Document subject
- **`keywords`**: Document keywords/tags

### Language & Dates
- **`language`**: Auto-detected language code (e.g., `en`, `de`, `fr`)
- **`created_date`**: File creation timestamp
- **`modified_date`**: File modification timestamp
- **`indexed_date`**: When the document was indexed

### Technical
- **`content_hash`**: SHA-256 hash for change detection

### Search Response Format

Search results are optimized for MCP responses (< 1 MB) and include:

```json
{
  "success": true,
  "documents": [
    {
      "score": 0.85,
      "file_name": "example.pdf",
      "file_path": "/path/to/example.pdf",
      "title": "Example Document",
      "author": "John Doe",
      "language": "en",
      "snippet": "...relevant <em>search term</em> highlighted in context..."
    }
  ],
  "totalHits": 42,
  "page": 0,
  "pageSize": 10,
  "totalPages": 5,
  "hasNextPage": true,
  "hasPreviousPage": false,
  "searchTimeMs": 12,
  "facets": {
    "language": [
      { "value": "en", "count": 25 },
      { "value": "de", "count": 12 },
      { "value": "fr", "count": 5 }
    ],
    "file_extension": [
      { "value": "pdf", "count": 30 },
      { "value": "docx", "count": 8 },
      { "value": "xlsx", "count": 4 }
    ],
    "file_type": [
      { "value": "application/pdf", "count": 30 },
      { "value": "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "count": 8 }
    ],
    "author": [
      { "value": "John Doe", "count": 15 },
      { "value": "Jane Smith", "count": 10 }
    ]
  }
}
```

**Key Features:**

- **Search Performance Metrics:** Every search response includes `searchTimeMs` showing the exact execution time in milliseconds, enabling performance monitoring and optimization.

- **Snippets with Highlighting:** The full `content` field is NOT included in search results to keep response sizes manageable. Instead, a contextual `snippet` with `<em>` tags highlighting search terms is provided.

- **Lucene Faceting:** The `facets` object uses **Lucene's SortedSetDocValues** for efficient faceted search. It shows actual facet values and document counts from the search results, not just available fields. Only facet dimensions that have values in the result set are returned.

- **Facet Dimensions:** The following fields are indexed as facets:
  - `language` - Detected document language (ISO 639-1 code)
  - `file_extension` - File extension (pdf, docx, etc.)
  - `file_type` - MIME type
  - `author` - Document author (multi-valued)
  - `creator` - Document creator (multi-valued)
  - `subject` - Document subject (multi-valued)

### Faceted Search Examples

Use facets to build drill-down queries and refine search results:

```
# Filter by file type using facet values
filterField: "file_extension"
filterValue: "pdf"

# Filter by language using facet values
filterField: "language"
filterValue: "de"

# Filter by author using facet values
filterField: "author"
filterValue: "John Doe"

# Combine search query with facet filter
queryString: "contract agreement"
filterField: "file_extension"
filterValue: "pdf"
```

**Facet-Driven Workflow:**

1. Perform initial search with broad query
2. Review `facets` in response to see available refinement options
3. Apply filters using facet values to narrow results
4. Iterate to drill down into specific subsets

## Document Crawler Features

### Automatic Crawling

The crawler starts automatically on server startup (if `crawl-on-startup: true`) and:

1. **Discovers files** matching include patterns in configured directories
2. **Extracts content** using Apache Tika (supports 100+ file formats)
3. **Detects language** automatically for each document
4. **Extracts metadata** (author, title, dates, etc.)
5. **Indexes documents** in batches for optimal performance
6. **Monitors directories** for changes (create, modify, delete)

### Real-time Monitoring

With directory watching enabled (`watch-enabled: true`):

- **New files** are automatically indexed when added
- **Modified files** are re-indexed with updated content
- **Deleted files** are removed from the index

### Performance Optimization

**Multi-threading:**
- Crawls multiple directories in parallel (configurable thread pool)
- Each directory is processed by a separate thread

**Batch Processing:**
- Documents are indexed in batches (default: 100 documents)
- Reduces I/O overhead and improves indexing speed

**NRT (Near Real-Time) Optimization:**
- Normal operation: 100ms refresh interval for fast search updates
- Bulk indexing (>1000 files): Automatically slows to 5s to reduce overhead
- Restores to 100ms after bulk operation completes

**Progress Notifications:**
- Updates every 100 files OR every 30 seconds (whichever comes first)
- Shows throughput (files/sec, MB/sec) and progress
- Non-blocking: Appear in system notification area without interrupting workflow
  - **macOS**: Notifications appear in Notification Center (top-right corner)
  - **Windows**: Toast notifications in system tray area
  - **Linux**: Uses notify-send for desktop notifications

### Error Handling

- Failed files are logged but don't stop the crawl
- Statistics track successful vs. failed files
- Large documents are fully indexed (no truncation by default)
- Corrupted or inaccessible files are skipped gracefully

## Troubleshooting

### Server shows as "running" but tools don't work

This usually indicates STDIO communication issues:

1. Ensure the `-Dspring.profiles.active=deployed` argument is present in the config
2. Check that no other output is being written to stdout
3. Verify the JAR path is an absolute path, not relative
4. If you modified the configuration, ensure the "deployed" profile settings are correct

### Claude Desktop doesn't show the server

1. Verify the JAR file path in the configuration is correct and absolute
2. Check that Java 21+ is installed: `java -version`
3. Validate the JSON syntax in the config file
4. Check Claude Desktop logs for error messages
5. Try running the JAR manually to check for startup errors:
   ```bash
   java -jar /path/to/luceneserver-0.0.1-SNAPSHOT.jar
   ```

### Server fails to start

1. Ensure the Lucene index directory path is valid
2. Check that no other process is locking the index directory
3. Verify sufficient disk space for the index

### Empty search results

The index may be empty for several reasons:

1. **No directories configured**: Add directories to `application.yaml` under `lucene.crawler.directories`
2. **Crawler not started**: Use the `startCrawl` MCP tool or enable `crawl-on-startup: true`
3. **No matching files**: Check that your directories contain files matching the include patterns
4. **Files failed to index**: Check the logs for errors, use `getCrawlerStats` to see failed file count

### Crawler not indexing files

1. **Check directory paths**: Ensure paths in `application.yaml` are absolute and exist
2. **Verify file permissions**: The server needs read access to all files
3. **Check include patterns**: Files must match at least one include pattern
4. **Check exclude patterns**: Files must not match any exclude pattern
5. **Monitor crawler status**: Use `getCrawlerStatus` and `getCrawlerStats` MCP tools
6. **Check logs**: Look for parsing errors or I/O exceptions

### Out of memory errors during indexing

If you encounter OOM errors with very large documents:

1. **Set content limit**: Change `max-content-length` in `application.yaml` (e.g., `5242880` for 5MB)
2. **Increase JVM heap**: Add `-Xmx2g` to JVM arguments in Claude Desktop config
3. **Reduce thread pool**: Lower `thread-pool-size` to reduce concurrent processing
4. **Reduce batch size**: Lower `batch-size` to commit more frequently

### Slow indexing performance

1. **Increase thread pool**: Raise `thread-pool-size` (default: 4)
2. **Increase batch size**: Raise `batch-size` for fewer commits (default: 100)
3. **Disable language detection**: Set `detect-language: false` if not needed
4. **Disable metadata extraction**: Set `extract-metadata: false` if not needed
5. **Check disk I/O**: Slow disk can bottleneck indexing

## Usage Examples

### Example 1: Index Your Documents Folder

1. Edit `application.yaml`:
```yaml
lucene:
  crawler:
    directories:
      - "/Users/yourname/Documents"
    crawl-on-startup: true
```

2. Start the server:
```bash
java -jar target/luceneserver-0.0.1-SNAPSHOT.jar
```

3. The crawler automatically starts and indexes all supported documents in your Documents folder.

### Example 2: Search with Filtering

Ask Claude:
```
Search for "machine learning" in PDF documents only
```

Claude will use:
```
query: "machine learning"
filterField: "file_extension"
filterValue: "pdf"
```

### Example 3: Find Documents by Author

Ask Claude:
```
Find all documents written by John Doe
```

Claude will use:
```
query: "*"
filterField: "author"
filterValue: "John Doe"
```

### Example 4: Monitor Crawler Progress

Ask Claude:
```
Show me the crawler statistics
```

Claude calls `getCrawlerStats()` and shows:
- Files processed: 1,234 / 5,000
- Throughput: 85 files/sec
- Indexed: 1,200 (98%)
- Failed: 34 (2%)

### Example 5: Manual Crawl with Full Reindex

Ask Claude:
```
Reindex all documents from scratch
```

Claude calls `startCrawl(fullReindex: true)`, which:
1. Clears the existing index
2. Re-crawls all configured directories
3. Indexes all documents fresh

### Example 6: Language-Specific Search

Ask Claude:
```
Find German documents about "Technologie"
```

Claude uses:
```
query: "Technologie"
filterField: "language"
filterValue: "de"
```

### Example 7: Search with Context Snippets

Search results include highlighted snippets showing the search term in context:

```json
{
  "file_name": "report.pdf",
  "snippet": "...discusses the impact of <em>machine learning</em> on modern software development. The study shows..."
}
```

This allows you to see relevant excerpts without downloading the full document.

### Example 8: Managing Crawlable Directories at Runtime

Ask Claude to manage directories without editing configuration files:

```
"What directories are currently being crawled?"
# Claude calls listCrawlableDirectories()
# Response: Shows all configured directories and config file location

"Add /Users/yourname/Research as a crawlable directory"
# Claude calls addCrawlableDirectory(path="/Users/yourname/Research")
# Directory is added to ~/.mcplucene/config.yaml

"Add /Users/yourname/Projects and start crawling it now"
# Claude calls addCrawlableDirectory(path="/Users/yourname/Projects", crawlNow=true)
# Directory is added and crawl starts immediately

"Stop crawling /Users/yourname/Downloads"
# Claude calls removeCrawlableDirectory(path="/Users/yourname/Downloads")
# Directory is removed from config (indexed documents remain)
```

**Configuration Persistence:**

The directories you add via MCP tools are saved to `~/.mcplucene/config.yaml`:

```yaml
lucene:
  crawler:
    directories:
      - /Users/yourname/Documents
      - /Users/yourname/Research
      - /Users/yourname/Projects
```

This configuration persists across server restarts - no need to reconfigure each time.

**Environment Variable Override:**

If you set the `LUCENE_CRAWLER_DIRECTORIES` environment variable, it takes precedence:

```json
{
  "mcpServers": {
    "lucene-search": {
      "command": "java",
      "args": ["-Dspring.profiles.active=deployed", "-jar", "/path/to/jar"],
      "env": {
        "LUCENE_CRAWLER_DIRECTORIES": "/path1,/path2"
      }
    }
  }
}
```

When this is set, `addCrawlableDirectory` and `removeCrawlableDirectory` will return an error message indicating the environment override is active.

### Example 9: Working with Lexical Search (Synonyms and Variations)

Since the search engine performs **exact lexical matching** without automatic synonym expansion, you need to explicitly include synonyms and word variations in your query:

**❌ Basic search (might miss relevant results):**
```
query: "car"
```
This will ONLY match documents containing the exact word "car", missing documents with "automobile", "vehicle", etc.

**✅ Better: Include synonyms with OR:**
```
query: "(car OR automobile OR vehicle)"
```

**✅ Best: Combine synonyms with wildcards for variations:**
```
query: "(car* OR automobile* OR vehicle*)"
```
This matches: car, cars, automobile, automobiles, vehicle, vehicles, etc.

**Real-world example - Finding contracts:**
```
query: "(contract* OR agreement* OR deal*) AND (sign* OR execut* OR finali*)"
filterField: "file_extension"
filterValue: "pdf"
```

This will find documents containing variations like:
- "contract signed", "agreement executed", "deal finalized"
- "contracts signing", "agreements execute", "deals finalizing"

**💡 Tip:** Use the `facets` in the search response to discover the exact terms used in your documents, then refine your query accordingly.

## Development

### Running for Development

When developing and debugging in your IDE, run the server **without** the "deployed" profile to get full logging:

**In your IDE (IntelliJ, Eclipse, VS Code):**
```bash
# Just run the main class directly - no profile needed
# You'll see full Spring Boot logs, banner, and debug output
java -jar target/luceneserver-0.0.1-SNAPSHOT.jar
```

**Or set environment variable:**
```bash
SPRING_PROFILES_ACTIVE=default java -jar target/luceneserver-0.0.1-SNAPSHOT.jar
```

This gives you:
- ✅ Full Spring Boot startup banner
- ✅ Complete logging output for debugging
- ✅ Web server enabled (if needed for testing)
- ✅ All debug information visible

**For production/Claude Desktop deployment:**
```bash
# Use the deployed profile for clean STDIO
java -Dspring.profiles.active=deployed -jar target/luceneserver-0.0.1-SNAPSHOT.jar
```

### Adding Documents to the Index

**Recommended approach:** Use the document crawler by configuring directories in `application.yaml`. The crawler automatically handles content extraction, metadata, and language detection.

**Programmatic approach:** For custom document types or direct indexing:

```java
@Autowired
private LuceneIndexService indexService;

public void addDocument(String title, String content) throws IOException {
    Document doc = new Document();
    doc.add(new TextField("title", title, Field.Store.YES));
    doc.add(new TextField("content", content, Field.Store.YES));
    doc.add(new StringField("file_path", "/custom/path", Field.Store.YES));
    indexService.getIndexWriter().addDocument(doc);
    indexService.getIndexWriter().commit();
}
```

For the full field schema, see the [Index Field Schema](#index-field-schema) section.

### Technology Stack

- **Spring Boot 3.5** - Application framework
- **Spring AI MCP Server** - Model Context Protocol implementation
- **Apache Lucene 10.1** - Full-text search engine
- **Apache Tika 3.0** - Document content extraction
- **Guava 33.0** - Utility libraries
- **Java 21** - Runtime environment

### Crawler Architecture

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
