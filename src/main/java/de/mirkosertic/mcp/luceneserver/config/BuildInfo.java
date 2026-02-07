package de.mirkosertic.mcp.luceneserver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build information provider that loads version and timestamp from Maven-filtered build-info.properties.
 * Falls back to "dev"/"unknown" when running in IDE or when the properties file is not available.
 */
public final class BuildInfo {

    private static final Logger logger = LoggerFactory.getLogger(BuildInfo.class);

    private static final String BUILD_INFO_FILE = "build-info.properties";
    private static final String VERSION_KEY = "build.version";
    private static final String TIMESTAMP_KEY = "build.timestamp";

    private static final String version;
    private static final String buildTimestamp;

    static {
        String tempVersion = "dev";
        String tempTimestamp = "unknown";

        try (final InputStream input = BuildInfo.class.getClassLoader().getResourceAsStream(BUILD_INFO_FILE)) {
            if (input != null) {
                final Properties props = new Properties();
                props.load(input);
                tempVersion = props.getProperty(VERSION_KEY, "dev");
                tempTimestamp = props.getProperty(TIMESTAMP_KEY, "unknown");
                logger.debug("Loaded build info: version={}, timestamp={}", tempVersion, tempTimestamp);
            } else {
                logger.debug("Build info file not found, using defaults (IDE/dev mode)");
            }
        } catch (final IOException e) {
            logger.warn("Failed to load build info, using defaults", e);
        }

        version = tempVersion;
        buildTimestamp = tempTimestamp;
    }

    private BuildInfo() {
        // Prevent instantiation
    }

    public static String getVersion() {
        return version;
    }

    public static String getBuildTimestamp() {
        return buildTimestamp;
    }
}
