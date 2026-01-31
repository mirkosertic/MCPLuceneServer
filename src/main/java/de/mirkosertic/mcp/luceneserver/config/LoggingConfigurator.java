package de.mirkosertic.mcp.luceneserver.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configures logging based on the active profile.
 * <p>
 * In deployed mode (STDIO transport), loads logback-deployed.xml which
 * outputs to file only, preventing interference with MCP JSON-RPC.
 * <p>
 * In default mode (development), uses logback.xml with console output.
 */
public final class LoggingConfigurator {

    private static final String LOG_DIR = System.getProperty("user.home") + "/.mcplucene/log";
    private static final String DEPLOYED_CONFIG = "logback-deployed.xml";

    private LoggingConfigurator() {
    }

    /**
     * Configure logging based on the active profile.
     * Must be called early in application startup, before logging is used.
     *
     * @param deployedMode true if running in deployed mode (STDIO transport)
     */
    public static void configure(final boolean deployedMode) {
        if (deployedMode) {
            ensureLogDirectoryExists();
            loadConfiguration(DEPLOYED_CONFIG);
        }
        // Default mode uses logback.xml which is loaded automatically
    }

    private static void ensureLogDirectoryExists() {
        try {
            final Path logDir = Paths.get(LOG_DIR);
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
        } catch (final Exception e) {
            System.err.println("Warning: Could not create log directory: " + LOG_DIR);
        }
    }

    private static void loadConfiguration(final String configFile) {
        try {
            final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();

            final JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);

            try (InputStream configStream = LoggingConfigurator.class.getClassLoader()
                    .getResourceAsStream(configFile)) {
                if (configStream != null) {
                    configurator.doConfigure(configStream);
                } else {
                    System.err.println("Warning: Could not find " + configFile + " on classpath");
                }
            }
        } catch (final JoranException e) {
            System.err.println("Warning: Error loading logback configuration: " + e.getMessage());
        } catch (final Exception e) {
            System.err.println("Warning: Unexpected error configuring logging: " + e.getMessage());
        }
    }
}
