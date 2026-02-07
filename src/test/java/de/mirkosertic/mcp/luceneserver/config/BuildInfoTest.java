package de.mirkosertic.mcp.luceneserver.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BuildInfo.
 */
@DisplayName("BuildInfo Tests")
class BuildInfoTest {

    @Test
    @DisplayName("Should load version and timestamp")
    void shouldLoadVersionAndTimestamp() {
        // When: Access build info
        final String version = BuildInfo.getVersion();
        final String timestamp = BuildInfo.getBuildTimestamp();

        // Then: Values should be non-null
        assertThat(version)
                .as("Version should not be null")
                .isNotNull()
                .isNotEmpty();

        assertThat(timestamp)
                .as("Build timestamp should not be null")
                .isNotNull()
                .isNotEmpty();

        // Version should be either Maven version (when filtered) or "dev" (IDE mode)
        assertThat(version)
                .as("Version should be either Maven version or 'dev'")
                .matches("^(\\d+\\.\\d+\\.\\d+.*|dev)$");

        // Timestamp should be either ISO format (when filtered) or "unknown" (IDE mode)
        assertThat(timestamp)
                .as("Timestamp should be either ISO format or 'unknown'")
                .matches("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z|unknown)$");
    }
}
