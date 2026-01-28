package de.mirkosertic.mcp.luceneserver;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class McpServerLifecycleManager implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(McpServerLifecycleManager.class);

    private final ConfigurableApplicationContext context;

    public McpServerLifecycleManager(final ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(final @NonNull ApplicationArguments args) {
        // Parent Process Monitor
        ProcessHandle.current().parent().ifPresent(parent -> {
            parent.onExit().thenRun(() -> {
                logger.info("[MCP Server] Parent process terminated, shutting down...");
                final int exitCode = SpringApplication.exit(context, () -> 0);
                System.exit(exitCode);
            });

            logger.info("[MCP Server] Monitoring parent process PID: {}", parent.pid());
        });

        logger.info("[MCP Server] Lifecycle management initialized");
    }
}