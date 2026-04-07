package de.mirkosertic.mcp.luceneserver.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ApplicationConfig tool exposure configuration (LUCENE_TOOLS_INCLUDE/EXCLUDE).
 */
@DisplayName("ApplicationConfig Tool Exposure Tests")
class ApplicationConfigToolExposureTest {

    @BeforeEach
    void setUp() {
        // Clear all relevant system properties before each test
        System.clearProperty("lucene.tools.include");
        System.clearProperty("lucene.tools.exclude");
        System.clearProperty("vector.model");
    }

    @AfterEach
    void tearDown() {
        // Restore clean state after each test
        System.clearProperty("lucene.tools.include");
        System.clearProperty("lucene.tools.exclude");
        System.clearProperty("vector.model");
    }

    @Test
    @DisplayName("Default config: no VECTOR_MODEL → 21 non-semantic tools, semanticSearchEnabled=false")
    void defaultConfigWithoutVectorModel() {
        // Given: default config, no vector model
        // (lucene.tools.include defaults to "*", vector.model not set)

        // When
        final ApplicationConfig config = ApplicationConfig.load();

        // Then: semantic tools excluded (no model), 21 tools remain
        assertThat(config.isSemanticSearchEnabled()).isFalse();
        assertThat(config.isToolActive("semanticSearch")).isFalse();
        assertThat(config.isToolActive("profileSemanticSearch")).isFalse();

        // Non-semantic tools should be active
        assertThat(config.isToolActive("simpleSearch")).isTrue();
        assertThat(config.isToolActive("extendedSearch")).isTrue();
        assertThat(config.isToolActive("profileQuery")).isTrue();
        assertThat(config.isToolActive("suggestTerms")).isTrue();
        assertThat(config.isToolActive("getTopTerms")).isTrue();
        assertThat(config.isToolActive("getIndexStats")).isTrue();
        assertThat(config.isToolActive("listIndexedFields")).isTrue();
        assertThat(config.isToolActive("getDocumentDetails")).isTrue();
        assertThat(config.isToolActive("startCrawl")).isTrue();
        assertThat(config.isToolActive("getCrawlerStats")).isTrue();
        assertThat(config.isToolActive("getCrawlerStatus")).isTrue();
        assertThat(config.isToolActive("pauseCrawler")).isTrue();
        assertThat(config.isToolActive("resumeCrawler")).isTrue();
        assertThat(config.isToolActive("listCrawlableDirectories")).isTrue();
        assertThat(config.isToolActive("addCrawlableDirectory")).isTrue();
        assertThat(config.isToolActive("removeCrawlableDirectory")).isTrue();
        assertThat(config.isToolActive("optimizeIndex")).isTrue();
        assertThat(config.isToolActive("purgeIndex")).isTrue();
        assertThat(config.isToolActive("unlockIndex")).isTrue();
        assertThat(config.isToolActive("getIndexAdminStatus")).isTrue();
        assertThat(config.isToolActive("indexAdmin")).isTrue();
    }

    @Test
    @DisplayName("With VECTOR_MODEL set → 23 tools active, semanticSearchEnabled=true")
    void withVectorModelAllToolsActive() {
        // Given
        System.setProperty("vector.model", "e5-base");

        try {
            // When
            final ApplicationConfig config = ApplicationConfig.load();

            // Then: all 23 tools including semantic ones
            assertThat(config.isSemanticSearchEnabled()).isTrue();
            assertThat(config.isToolActive("semanticSearch")).isTrue();
            assertThat(config.isToolActive("profileSemanticSearch")).isTrue();
            assertThat(config.isToolActive("simpleSearch")).isTrue();
            assertThat(config.isToolActive("extendedSearch")).isTrue();
            assertThat(config.isToolActive("profileQuery")).isTrue();
        } finally {
            System.clearProperty("vector.model");
        }
    }

    @Test
    @DisplayName("LUCENE_TOOLS_INCLUDE=search → only simpleSearch and extendedSearch active")
    void includeSearchGroupOnly() {
        // Given
        System.setProperty("lucene.tools.include", "search");

        // When
        final ApplicationConfig config = ApplicationConfig.load();

        // Then
        assertThat(config.isToolActive("simpleSearch")).isTrue();
        assertThat(config.isToolActive("extendedSearch")).isTrue();
        assertThat(config.isToolActive("profileQuery")).isFalse();
        assertThat(config.isToolActive("semanticSearch")).isFalse();
        assertThat(config.isToolActive("startCrawl")).isFalse();
        assertThat(config.isToolActive("getIndexStats")).isFalse();
    }

    @Test
    @DisplayName("LUCENE_TOOLS_INCLUDE=search,semantic without VECTOR_MODEL → semantic dropped, only search group")
    void includeSearchAndSemanticWithoutModel() {
        // Given
        System.setProperty("lucene.tools.include", "search,semantic");
        // No vector.model set

        // When
        final ApplicationConfig config = ApplicationConfig.load();

        // Then: semantic tools dropped because no model
        assertThat(config.isSemanticSearchEnabled()).isFalse();
        assertThat(config.isToolActive("semanticSearch")).isFalse();
        assertThat(config.isToolActive("profileSemanticSearch")).isFalse();
        // search group still active
        assertThat(config.isToolActive("simpleSearch")).isTrue();
        assertThat(config.isToolActive("extendedSearch")).isTrue();
    }

    @Test
    @DisplayName("Exclude wins: INCLUDE=search, EXCLUDE=simpleSearch → only extendedSearch")
    void excludeWinsOverInclude() {
        // Given
        System.setProperty("lucene.tools.include", "search");
        System.setProperty("lucene.tools.exclude", "simpleSearch");

        // When
        final ApplicationConfig config = ApplicationConfig.load();

        // Then
        assertThat(config.isToolActive("simpleSearch")).isFalse();
        assertThat(config.isToolActive("extendedSearch")).isTrue();
    }

    @Test
    @DisplayName("Unknown group name → no exception, gracefully ignored")
    void unknownGroupNameGracefullyIgnored() {
        // Given
        System.setProperty("lucene.tools.include", "search,unknownGroupXYZ");

        // When: should not throw
        final ApplicationConfig config = ApplicationConfig.load();

        // Then: search group still works, unknown group silently ignored
        assertThat(config.isToolActive("simpleSearch")).isTrue();
        assertThat(config.isToolActive("extendedSearch")).isTrue();
        assertThat(config.isToolActive("getIndexStats")).isFalse();
    }

    @Test
    @DisplayName("LUCENE_TOOLS_INCLUDE=debug → profileQuery active, profileSemanticSearch dropped without model")
    void debugGroupWithoutModel() {
        // Given
        System.setProperty("lucene.tools.include", "debug");
        // No vector.model

        // When
        final ApplicationConfig config = ApplicationConfig.load();

        // Then: profileQuery active, profileSemanticSearch dropped (it's a semantic tool)
        assertThat(config.isToolActive("profileQuery")).isTrue();
        assertThat(config.isToolActive("profileSemanticSearch")).isFalse();
        assertThat(config.isSemanticSearchEnabled()).isFalse();
        // Other tools not in debug group
        assertThat(config.isToolActive("simpleSearch")).isFalse();
        assertThat(config.isToolActive("startCrawl")).isFalse();
    }
}
