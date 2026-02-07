# MCP Lucene Server - AI Assistant Guide

For user-facing documentation, see **README.md**.

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
6. **ALWAYS bump `SCHEMA_VERSION`** in `DocumentIndexer.java` when changing the index schema
   (adding/removing/renaming fields, changing analyzers, or modifying field indexing options)

---

## Key Components

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

## Code Conventions

- Use `final` for parameters and local variables
- **Constructor injection only** - no field injection
- Catch specific exceptions, not `Exception`
- Return user-friendly errors in MCP responses
- Logger: `private static final Logger logger = LoggerFactory.getLogger(ClassName.class);`
- MCP responses always include `success` (boolean) and `error` (nullable String)
- Request DTOs in `mcp/dto/` with `fromMap()` static factory
- Response DTOs with `success()` / `error()` static factory methods
- Use `@Description` for schema generation, `@Nullable` for optional fields

---

## Skills (On-Demand Context)

The following skills provide detailed guidance that is loaded only when needed. Use them as slash commands or let Claude invoke them automatically based on the task.

| Skill | Command | When to use |
|-------|---------|-------------|
| **Add MCP Tool** | `/add-mcp-tool` | Step-by-step guide for creating new MCP tool endpoints |
| **Architecture** | `/architecture` | Design decisions, processing patterns, system internals, limitations |
| **Dev Tasks** | `/dev-tasks` | Recipes for facets, file formats, STDIO debugging, performance tuning, testing |
| **Improvements** | `/improvements` | Roadmap, feature priorities, trade-off analysis, rejected ideas with reasoning |

Skills are defined in `.claude/skills/` and checked into Git. They should be **actively maintained**: update or extend them whenever new patterns, decisions, or procedures emerge during development.

---

## Key Principles

- **Simple > Complex**
- **User-friendly > Feature-rich**
- **Fast > Perfect**
- **Working > Theoretically optimal**

When in doubt, keep it simple and update README.md.
