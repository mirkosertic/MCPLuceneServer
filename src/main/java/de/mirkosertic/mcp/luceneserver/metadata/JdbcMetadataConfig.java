package de.mirkosertic.mcp.luceneserver.metadata;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Configuration for JDBC metadata enrichment.
 * Loaded from ~/.mcplucene/config.yaml under lucene.metadata.jdbc.
 * <p>
 * Note: FieldMappingConfig is intentionally omitted. The field prefix is the constant
 * {@link JdbcMetadataEnricher#FIELD_PREFIX} ("dbmeta_").
 */
public record JdbcMetadataConfig(
        boolean enabled,
        String url,
        @Nullable String username,
        @Nullable String password,
        @Nullable String driverClassName,
        int poolSize,
        int connectionTimeout,
        int queryTimeout,
        String query,
        List<ParameterMapping> parameters,
        JsonConfig json,
        SyncConfig sync
) {

    public record ParameterMapping(String name, String sourceField) {}

    public record JsonConfig(String columnName) {}

    public record SyncConfig(
            boolean enabled,
            int intervalMinutes,
            @Nullable String query,
            @Nullable String filePathColumn,
            @Nullable String timestampColumn
    ) {}
}
