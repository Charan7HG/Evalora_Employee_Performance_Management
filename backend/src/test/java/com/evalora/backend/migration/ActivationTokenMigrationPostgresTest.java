package com.evalora.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PostgreSQL-only migration coverage. Set EVALORA_MIGRATION_TEST_URL to an
 * empty, isolated database named evalora_migration_test_* to enable this test.
 */
class ActivationTokenMigrationPostgresTest {

    private static final Pattern SAFE_TEST_DATABASE = Pattern.compile(
            "^jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):\\d+/"
                    + "(evalora_migration_test_[a-z0-9_]+)(?:\\?.*)?$");

    @Test
    void activationHelpersFollowTheDeleteBasedTokenLifecycle() throws Exception {
        String url = System.getenv("EVALORA_MIGRATION_TEST_URL");
        assumeTrue(url != null && !url.isBlank(),
                "Set EVALORA_MIGRATION_TEST_URL to run PostgreSQL migration coverage");

        Matcher matcher = SAFE_TEST_DATABASE.matcher(url);
        assertThat(matcher.matches())
                .as("migration tests must target a local, isolated evalora_migration_test_* database")
                .isTrue();

        String username = System.getenv("EVALORA_MIGRATION_TEST_USERNAME");
        String password = System.getenv("EVALORA_MIGRATION_TEST_PASSWORD");
        assertThat(username).isNotBlank();
        if (password == null) {
            password = "";
        }

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertThat(scalarString(connection, "SELECT current_database()"))
                    .isEqualTo(matcher.group(1));
            assertThat(scalarBoolean(connection,
                    "SELECT to_regclass('public.flyway_schema_history') IS NULL "
                            + "AND to_regclass('public.users') IS NULL"))
                    .as("the isolated database must be empty before migrations run")
                    .isTrue();

            Flyway.configure()
                    .dataSource(url, username, password)
                    .locations("classpath:db/migration")
                    .target("6")
                    .load()
                    .migrate();
            Flyway flyway = Flyway.configure()
                    .dataSource(url, username, password)
                    .locations("classpath:db/migration")
                    .load();

            assertThat(scalarLong(connection,
                    "SELECT count(*) FROM flyway_schema_history WHERE success"))
                    .isEqualTo(6);
            assertThat(scalarBoolean(connection,
                    "SELECT count(*) = 0 FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = 'activation_tokens' "
                            + "AND column_name = 'used_at'"))
                    .as("activation tokens are consumed by deletion, not a used_at column")
                    .isTrue();
            assertThat(scalarBoolean(connection,
                    "SELECT position('used_at' IN pg_get_functiondef("
                            + "'invalidate_all_unused_activation_tokens_for_user(bigint,timestamp with time zone)'::regprocedure)) > 0 "
                            + "AND position('used_at' IN pg_get_functiondef("
                            + "'has_active_activation_token(bigint)'::regprocedure)) > 0"))
                    .as("V6 helpers reference used_at despite its absence from activation_tokens")
                    .isTrue();

            long userId = insertUser(connection);
            insertToken(connection, userId, "expired-token", "CURRENT_TIMESTAMP - INTERVAL '1 hour'");
            insertToken(connection, userId, "active-token", "CURRENT_TIMESTAMP + INTERVAL '1 hour'");

            SQLException v6StatusFailure = catchThrowableOfType(
                    () -> hasActiveToken(connection, userId), SQLException.class);
            assertNotNull(v6StatusFailure, "the V6 status helper must fail on PostgreSQL");
            assertThat(v6StatusFailure.getSQLState()).isEqualTo("42702");
            assertThat(v6StatusFailure.getMessage()).contains("user_id");

            SQLException v6InvalidationFailure = catchThrowableOfType(
                    () -> invalidateTokens(connection, userId), SQLException.class);
            assertNotNull(v6InvalidationFailure, "the V6 invalidation helper must fail on PostgreSQL");
            assertThat(v6InvalidationFailure.getSQLState()).isEqualTo("42702");
            assertThat(v6InvalidationFailure.getMessage()).contains("user_id");

            flyway.migrate();
            assertThat(scalarLong(connection,
                    "SELECT count(*) FROM flyway_schema_history WHERE success"))
                    .isEqualTo(7);
            assertThat(scalarBoolean(connection,
                    "SELECT position('used_at' IN pg_get_functiondef("
                            + "'invalidate_all_unused_activation_tokens_for_user(bigint,timestamp with time zone)'::regprocedure)) = 0 "
                            + "AND position('used_at' IN pg_get_functiondef("
                            + "'has_active_activation_token(bigint)'::regprocedure)) = 0"))
                    .isTrue();

            assertThat(hasActiveToken(connection, userId)).isTrue();
            invalidateTokens(connection, userId);

            assertThat(hasActiveToken(connection, userId)).isFalse();
            assertThat(countTokens(connection, userId)).isEqualTo(1);
        }
    }

    private static long insertUser(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users (email, password_hash, role) "
                        + "VALUES ('activation-migration-test@evalora.local', 'test-hash', 'EMPLOYEE') "
                        + "RETURNING id");
             ResultSet result = statement.executeQuery()) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static void insertToken(Connection connection, long userId, String hash, String expiry)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO activation_tokens (user_id, token_hash, expires_at) "
                        + "VALUES (?, ?, " + expiry + ")")) {
            statement.setLong(1, userId);
            statement.setString(2, hash);
            statement.executeUpdate();
        }
    }

    private static boolean hasActiveToken(Connection connection, long userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT has_active_activation_token(?)")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getBoolean(1);
            }
        }
    }

    private static void invalidateTokens(Connection connection, long userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT invalidate_all_unused_activation_tokens_for_user(?, now())")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
            }
        }
    }

    private static long countTokens(Connection connection, long userId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM activation_tokens WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static long scalarLong(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static boolean scalarBoolean(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }
}
