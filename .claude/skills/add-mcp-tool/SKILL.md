---
name: add-mcp-tool
description: Step-by-step guide for adding a new MCP tool endpoint. Use when creating a new tool that Claude Desktop or other MCP clients can call.
allowed-tools: Read, Write, Edit, Bash, Glob, Grep, Task
---

# Adding a New MCP Tool

Follow these steps in order when adding a new MCP tool to the server.

## Step 1: Create Request DTO (if tool has parameters)

Create a new class in `src/main/java/com/ohmydigital/mcpluceneserver/mcp/dto/`.

Pattern:
```java
public record MyToolRequest(
    @Description("Description of the parameter")
    String requiredParam,
    @Nullable @Description("Optional parameter")
    String optionalParam
) {
    public static MyToolRequest fromMap(final Map<String, Object> map) {
        return new MyToolRequest(
            (String) map.get("requiredParam"),
            (String) map.get("optionalParam")
        );
    }
}
```

- Use `@Description` annotation for MCP schema generation
- Use `@Nullable` for optional fields
- Always provide a `fromMap()` static factory method

## Step 2: Create Response DTO

Create in the same `mcp/dto/` directory.

Pattern:
```java
public record MyToolResponse(
    boolean success,
    @Nullable String error,
    // ... data fields
) {
    public static MyToolResponse success(/* data params */) {
        return new MyToolResponse(true, null, /* data */);
    }

    public static MyToolResponse error(final String message) {
        return new MyToolResponse(false, message, /* nulls */);
    }
}
```

All responses MUST include `success` (boolean) and `error` (nullable String).

## Step 3: Register Tool Specification

In `LuceneSearchTools.getToolSpecifications()`, add a new `ToolSpecification`:

```java
new ToolSpecification("myToolName", "Human-readable description", schemaJson)
```

- Tool name: camelCase
- Description: concise, explains what it does and when to use it
- Schema: JSON Schema for the request parameters

## Step 4: Implement Handler

Add a handler method in `LuceneSearchTools`:

```java
private CallToolResult myToolName(final Map<String, Object> arguments) {
    try {
        final var request = MyToolRequest.fromMap(arguments);
        // ... implementation
        final var response = MyToolResponse.success(/* data */);
        return new CallToolResult(List.of(new TextContent(objectMapper.writeValueAsString(response))));
    } catch (final SpecificException e) {
        logger.error("myToolName failed", e);
        return new CallToolResult(List.of(new TextContent(
            objectMapper.writeValueAsString(MyToolResponse.error(e.getMessage())))));
    }
}
```

Wire the handler in the `callTool()` method's switch/if-else chain.

## Step 5: Update README.md

**MANDATORY** - Add tool documentation to README.md:
- Tool name, description, parameters
- Example request/response
- Add to the tools overview table

## Step 6: Test

1. Write unit tests for the DTO `fromMap()` methods
2. Write integration tests if the tool interacts with the index
3. Test with Claude Desktop manually

## Reference Examples

Look at these existing tools for patterns:
- Simple query tool: `search()` in `LuceneSearchTools.java`
- Admin operation (async): `optimizeIndex()` in `LuceneSearchTools.java`
- Configuration tool: `setCrawlerDirectories()` in `LuceneSearchTools.java`
