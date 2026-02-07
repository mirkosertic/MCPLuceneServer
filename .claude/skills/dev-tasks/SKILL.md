---
name: dev-tasks
description: Guides for common development tasks like adding facet fields, file format support, STDIO debugging, and performance tuning. Use when working on specific development recipes.
allowed-tools: Read, Write, Edit, Bash, Glob, Grep, Task
---

# Common Development Tasks

## Adding a New Facet Field

1. Update `DocumentIndexer.java` - add the field to `FacetsConfig`
2. Add the field to the Lucene document in the indexing method
3. **Bump `SCHEMA_VERSION`** in `DocumentIndexer.java` (triggers automatic reindex on upgrade)
4. Update README.md field schema table

## Adding File Format Support

1. Add the file extension pattern to `application.yaml` under `include-patterns`
2. Apache Tika handles most formats automatically via auto-detection
3. For custom parsing beyond Tika: extend `FileContentExtractor.java`
4. Test with real documents of the new format
5. Update README.md supported formats section

## Debugging STDIO Issues

The MCP server uses STDIO transport. Any output to stdout breaks the JSON-RPC protocol.

Checklist:
- Must use `deployed` profile: `java -Dspring.profiles.active=deployed -jar target/luceneserver-*.jar`
- The `deployed` profile disables all console logging appenders
- Search for `System.out.println` in codebase -- there must be NONE
- Search for `System.err.println` -- also problematic
- Logger output goes to file only in deployed mode
- Test command: `java -Dspring.profiles.active=deployed -jar target/luceneserver-*.jar`

## Performance Tuning

### Indexing is slow
- Increase `thread-pool-size` in config (more parallel file walkers)
- Increase `batch-size` (fewer Lucene commits)
- Increase `batch-timeout-ms` (larger batches)
- Check if `max-content-length` is causing excessive content truncation

### Search is slow
- Check for leading wildcard queries (`*term`) -- these are expensive even with `content_reversed` optimization
- Reduce `pageSize` in search requests
- Check total index size -- very large indices may benefit from `optimizeIndex()`

### Out of Memory (OOM)
- Set `max-content-length` to limit per-document content size
- Increase JVM heap: `-Xmx2g` or higher
- Reduce `thread-pool-size` (each thread holds document content in memory)
- Check for very large files being indexed (e.g., multi-GB archives)

## Adding a New Indexed Field

Steps:
1. Add field in `DocumentIndexer.java` document creation
2. If field needs special analysis: update `PerFieldAnalyzerWrapper` in `LuceneIndexService`
3. If field should be searchable: update query parsing in `LuceneIndexService`
4. If field should be highlighted: ensure it has `Store.YES` and term vectors
5. **Bump `SCHEMA_VERSION`** in `DocumentIndexer.java` (triggers automatic reindex on upgrade)
6. Update README.md field schema table
7. Test with `SearchHighlightingIntegrationTest` patterns

## Testing Patterns

- Integration tests use `@TempDir` for isolated Lucene indices
- Mock `ApplicationConfig` for configuration
- Helper `indexDocument(Path)` extracts, creates doc, indexes, commits, refreshes
- Run all tests: `mvn test` (~88 tests, ~10 seconds)
- Run specific test: `mvn test -Dtest=SearchHighlightingIntegrationTest`
