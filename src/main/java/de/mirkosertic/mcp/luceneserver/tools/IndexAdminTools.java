package de.mirkosertic.mcp.luceneserver.tools;

import com.google.common.io.Resources;
import de.mirkosertic.mcp.luceneserver.crawler.DocumentCrawlerService;
import de.mirkosertic.mcp.luceneserver.index.LuceneIndexService;
import de.mirkosertic.mcp.luceneserver.mcp.SchemaGenerator;
import de.mirkosertic.mcp.luceneserver.mcp.ToolResultHelper;
import de.mirkosertic.mcp.luceneserver.mcp.dto.IndexAdminStatusResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.OptimizeIndexRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.OptimizeIndexResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.PurgeIndexRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.PurgeIndexResponse;
import de.mirkosertic.mcp.luceneserver.mcp.dto.UnlockIndexRequest;
import de.mirkosertic.mcp.luceneserver.mcp.dto.UnlockIndexResponse;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for index administration: indexAdmin, optimizeIndex, purgeIndex, unlockIndex, getIndexAdminStatus.
 * Also provides the resource specification for the admin UI.
 */
public class IndexAdminTools implements McpToolProvider {

    private static final Logger logger = LoggerFactory.getLogger(IndexAdminTools.class);

    private static final String ADMIN_APP_RESOURCE_ID = "ui://indexadmin/index.html";

    private final LuceneIndexService indexService;
    private final DocumentCrawlerService crawlerService;

    public IndexAdminTools(final LuceneIndexService indexService, final DocumentCrawlerService crawlerService) {
        this.indexService = indexService;
        this.crawlerService = crawlerService;
    }

    public List<McpServerFeatures.SyncResourceSpecification> getResourceSpecifications() {
        final List<McpServerFeatures.SyncResourceSpecification> resources = new ArrayList<>();

        resources.add(new McpServerFeatures.SyncResourceSpecification(
                McpSchema.Resource.builder()
                        .uri(ADMIN_APP_RESOURCE_ID)
                        .mimeType("text/html;profile=mcp-app")
                        .name("Index Administration")
                        .meta(Map.of("ui", Map.of("border", "true")))
                        .build(),
                this::indexAdminResource
        ));

        return resources;
    }

    private McpSchema.ReadResourceResult indexAdminResource(final McpSyncServerExchange exchange, final McpSchema.ReadResourceRequest request) {
        final URL url = Resources.getResource("indexadmin-app.html");
        try {
            final String htmlcpde = Resources.toString(url, StandardCharsets.UTF_8);

            return new McpSchema.ReadResourceResult(
                    List.of(new McpSchema.TextResourceContents(
                            ADMIN_APP_RESOURCE_ID,
                            "text/html;profile=mcp-app",
                            htmlcpde)));
        } catch (final Exception e) {
            logger.error("Error loading index admin app", e);
            return new McpSchema.ReadResourceResult(
                    List.of(new McpSchema.TextResourceContents(
                            ADMIN_APP_RESOURCE_ID,
                            "text/html;profile=mcp-app",
                            "")));
        }
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> getToolSpecifications() {
        final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        // Index admin MCP App (https://github.com/modelcontextprotocol/ext-apps/blob/main/specification/2026-01-26/apps.mdx)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("indexAdmin")
                        .description("MCP App for Lucene index administrative tasks and maintenance")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .meta(Map.of("ui", Map.of("resourceUri", ADMIN_APP_RESOURCE_ID),
                                "ui/resourceUri", ADMIN_APP_RESOURCE_ID))
                        .build())
                .callHandler((exchange, request) -> indexAdmin(request.arguments()))
                .build());

        // Unlock index tool (dangerous recovery operation)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("unlockIndex")
                        .description("Remove the write.lock file from the Lucene index directory. " +
                                "WARNING: This is a dangerous recovery operation. Only use if you are CERTAIN no other process is using the index. " +
                                "Unlocking an index that is actively being written to can cause data corruption. " +
                                "Requires explicit confirmation (confirm=true) to proceed.")
                        .inputSchema(SchemaGenerator.generateSchema(UnlockIndexRequest.class))
                        .build())
                .callHandler((exchange, request) -> unlockIndex(request.arguments()))
                .build());

        // Optimize index tool (long-running)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("optimizeIndex")
                        .description("Optimize the Lucene index by merging segments. This is a long-running operation that runs in the background. " +
                                "Use getIndexAdminStatus to poll for progress. " +
                                "Optimization improves search performance but temporarily increases disk usage during the merge. " +
                                "Cannot run while the crawler is active.")
                        .inputSchema(SchemaGenerator.generateSchema(OptimizeIndexRequest.class))
                        .build())
                .callHandler((exchange, request) -> optimizeIndex(request.arguments()))
                .build());

        // Purge index tool (destructive, long-running)
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("purgeIndex")
                        .description("Delete all documents from the Lucene index. This is a destructive operation that runs in the background. " +
                                "Use getIndexAdminStatus to poll for progress. " +
                                "Requires explicit confirmation (confirm=true) to proceed. " +
                                "Set fullPurge=true to also delete index files and reinitialize (reclaims disk space immediately).")
                        .inputSchema(SchemaGenerator.generateSchema(PurgeIndexRequest.class))
                        .build())
                .callHandler((exchange, request) -> purgeIndex(request.arguments()))
                .build());

        // Get index admin status tool
        tools.add(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name("getIndexAdminStatus")
                        .description("Get the status of long-running index administration operations (optimize, purge). " +
                                "Returns the current state (IDLE, OPTIMIZING, PURGING, COMPLETED, FAILED), progress percentage, " +
                                "progress message, elapsed time, and the result of the last completed operation.")
                        .inputSchema(SchemaGenerator.emptySchema())
                        .build())
                .callHandler((exchange, request) -> getIndexAdminStatus())
                .build());

        return tools;
    }

    private McpSchema.CallToolResult indexAdmin(final Map<String, Object> args) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(ToolResultHelper.toJson(new HashMap<>()))))
                .build();
    }

    private McpSchema.CallToolResult unlockIndex(final Map<String, Object> args) {
        final UnlockIndexRequest request = UnlockIndexRequest.fromMap(args);

        logger.info("Unlock index request: confirm={}", request.confirm());

        // Require explicit confirmation
        if (!request.isConfirmed()) {
            logger.warn("Unlock index request not confirmed");
            return ToolResultHelper.createResult(UnlockIndexResponse.notConfirmed());
        }

        try {
            final boolean lockFileExisted = indexService.isLockFilePresent();
            final String lockFilePath = indexService.getLockFilePath().toString();

            if (!lockFileExisted) {
                logger.info("No lock file present at: {}", lockFilePath);
                return ToolResultHelper.createResult(UnlockIndexResponse.success(
                        "No lock file present. Index is not locked.", false, lockFilePath));
            }

            final boolean removed = indexService.removeLockFile();

            if (removed) {
                logger.warn("Lock file removed: {}", lockFilePath);
                return ToolResultHelper.createResult(UnlockIndexResponse.success(
                        "Lock file removed successfully. WARNING: Ensure no other process was using the index.",
                        true, lockFilePath));
            } else {
                return ToolResultHelper.createResult(UnlockIndexResponse.error(
                        "Failed to remove lock file. It may have been removed by another process."));
            }

        } catch (final IOException e) {
            logger.error("Error unlocking index", e);
            return ToolResultHelper.createResult(UnlockIndexResponse.error("Error unlocking index: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error unlocking index", e);
            return ToolResultHelper.createResult(UnlockIndexResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult optimizeIndex(final Map<String, Object> args) {
        final OptimizeIndexRequest request = OptimizeIndexRequest.fromMap(args);

        logger.info("Optimize index request: maxSegments={}", request.maxSegments());

        try {
            // Check if crawler is active
            final DocumentCrawlerService.CrawlerState crawlerState = crawlerService.getState();
            if (crawlerState == DocumentCrawlerService.CrawlerState.CRAWLING) {
                logger.warn("Cannot optimize while crawler is active");
                return ToolResultHelper.createResult(OptimizeIndexResponse.crawlerActive());
            }

            // Check if another admin operation is running
            if (indexService.isAdminOperationRunning()) {
                final LuceneIndexService.AdminOperationStatus status = indexService.getAdminStatus();
                logger.warn("Another admin operation is running: {}, cannot optimize the index", status.state());
                return ToolResultHelper.createResult(OptimizeIndexResponse.alreadyRunning(status.operationId()));
            }

            // Get current segment count
            final int currentSegments = indexService.getSegmentCount();
            final int targetSegments = request.effectiveMaxSegments();

            // Start optimization
            final String operationId = indexService.startOptimization(targetSegments);

            if (operationId == null) {
                logger.warn("Failed to start optimization - another operation may have started");
                return ToolResultHelper.createResult(OptimizeIndexResponse.error(
                        "Failed to start optimization. Another operation may have started."));
            }

            logger.info("Optimization started: operationId={}, currentSegments={}, targetSegments={}",
                    operationId, currentSegments, targetSegments);

            return ToolResultHelper.createResult(OptimizeIndexResponse.started(
                    operationId, targetSegments, currentSegments));

        } catch (final IOException e) {
            logger.error("Error starting optimization", e);
            return ToolResultHelper.createResult(OptimizeIndexResponse.error("Error starting optimization: " + e.getMessage()));
        } catch (final Exception e) {
            logger.error("Unexpected error starting optimization", e);
            return ToolResultHelper.createResult(OptimizeIndexResponse.error("Unexpected error: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult purgeIndex(final Map<String, Object> args) {
        final PurgeIndexRequest request = PurgeIndexRequest.fromMap(args);

        logger.info("Purge index request: confirm={}, fullPurge={}", request.confirm(), request.fullPurge());

        // Require explicit confirmation
        if (!request.isConfirmed()) {
            logger.warn("Purge index request not confirmed");
            return ToolResultHelper.createResult(PurgeIndexResponse.notConfirmed());
        }

        try {
            // Check if another admin operation is running
            if (indexService.isAdminOperationRunning()) {
                final LuceneIndexService.AdminOperationStatus status = indexService.getAdminStatus();
                logger.warn("Another admin operation is running: {}, cannot purge the index", status.state());
                return ToolResultHelper.createResult(PurgeIndexResponse.alreadyRunning(status.operationId()));
            }

            // Start purge
            final LuceneIndexService.PurgeResult result = indexService.startPurge(request.effectiveFullPurge());

            if (result == null) {
                logger.warn("Failed to start purge - another operation may have started");
                return ToolResultHelper.createResult(PurgeIndexResponse.error(
                        "Failed to start purge. Another operation may have started."));
            }

            logger.info("Purge started: operationId={}, documentsToDelete={}, fullPurge={}",
                    result.operationId(), result.documentsDeleted(), request.effectiveFullPurge());

            return ToolResultHelper.createResult(PurgeIndexResponse.started(
                    result.operationId(), result.documentsDeleted(), request.effectiveFullPurge()));

        } catch (final Exception e) {
            logger.error("Error starting purge", e);
            return ToolResultHelper.createResult(PurgeIndexResponse.error("Error starting purge: " + e.getMessage()));
        }
    }

    private McpSchema.CallToolResult getIndexAdminStatus() {
        logger.info("Get index admin status request");

        try {
            final LuceneIndexService.AdminOperationStatus status = indexService.getAdminStatus();

            logger.info("Index admin status: state={}, operationId={}, progress={}%",
                    status.state(), status.operationId(), status.progressPercent());

            if (status.state() == LuceneIndexService.AdminOperationState.IDLE) {
                return ToolResultHelper.createResult(IndexAdminStatusResponse.idle(status.lastOperationResult()));
            }

            return ToolResultHelper.createResult(IndexAdminStatusResponse.success(
                    status.state().name(),
                    status.operationId(),
                    status.progressPercent(),
                    status.progressMessage(),
                    status.elapsedTimeMs(),
                    status.lastOperationResult()
            ));

        } catch (final Exception e) {
            logger.error("Error getting index admin status", e);
            return ToolResultHelper.createResult(IndexAdminStatusResponse.error("Error getting status: " + e.getMessage()));
        }
    }
}
