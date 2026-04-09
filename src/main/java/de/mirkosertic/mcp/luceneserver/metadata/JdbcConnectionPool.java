package de.mirkosertic.mcp.luceneserver.metadata;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * HikariCP-based JDBC connection pool for metadata enrichment.
 * Must be closed on application shutdown to release connections.
 */
public class JdbcConnectionPool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(JdbcConnectionPool.class);

    private final HikariDataSource dataSource;

    public JdbcConnectionPool(final JdbcMetadataConfig config) {
        final HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.url());
        if (config.username() != null) {
            hikariConfig.setUsername(config.username());
        }
        if (config.password() != null) {
            hikariConfig.setPassword(config.password());
        }
        if (config.driverClassName() != null) {
            hikariConfig.setDriverClassName(config.driverClassName());
        }
        hikariConfig.setMaximumPoolSize(config.poolSize() > 0 ? config.poolSize() : 5);
        if (config.connectionTimeout() > 0) {
            hikariConfig.setConnectionTimeout(config.connectionTimeout());
        }
        hikariConfig.setReadOnly(true);
        hikariConfig.setPoolName("metadata-pool");

        this.dataSource = new HikariDataSource(hikariConfig);
        logger.info("JDBC metadata connection pool initialized: url={}", config.url());
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
            logger.info("JDBC metadata connection pool closed");
        }
    }
}
