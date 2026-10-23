package org.simplejavamail.examples.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keep repetitive database connection and parameter-binding code out of the examples' job flows.
 * Each application still chooses what belongs in a transaction, who owns a claim and when failed work may be reconsidered.
 */
public final class SqlStore implements AutoCloseable {
    private final HikariDataSource pool;

    public SqlStore() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(ExampleHttp.environment("DATABASE_URL", "jdbc:postgresql://database/patterns"));
        config.setUsername("patterns");
        config.setPassword("local-demo-only");
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(3000);
        pool = new HikariDataSource(config);
    }

    public DataSource dataSource() {
        return pool;
    }

    public int update(String sql, Object... values) {
        return transaction(connection -> update(connection, sql, values));
    }

    public List<Map<String, Object>> rows(String sql, Object... values) {
        return transaction(connection -> rows(connection, sql, values));
    }

    public <Result> Result transaction(Transaction<Result> work) {
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Result result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception failure) {
                try {
                    connection.rollback();
                } catch (SQLException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw new IllegalStateException("The example database operation did not complete", failure);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("The example database is unavailable", failure);
        }
    }

    public static int update(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, values)) {
            return statement.executeUpdate();
        }
    }

    public static List<Map<String, Object>> rows(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, values); ResultSet result = statement.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                    Object value = result.getObject(column);
                    // Keep SQL timestamps out of the HTTP serializer's implementation-specific type handling.
                    row.put(result.getMetaData().getColumnLabel(column), value instanceof java.util.Date ? value.toString() : value);
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object[] values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(3);
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
        return statement;
    }

    @Override
    public void close() {
        pool.close();
    }

    @FunctionalInterface
    public interface Transaction<Result> {
        Result run(Connection connection) throws Exception;
    }
}
