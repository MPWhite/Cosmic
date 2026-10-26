package tools;

import config.YamlConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseConnectionTest {
    @Test
    void usesYamlDefaultsWhenEnvironmentIsEmpty() {
        var actual = DatabaseConnection.getConfig(Map.of());
        var defaults = YamlConfig.config.server;
        assertEquals(String.format(defaults.DB_URL_FORMAT, defaults.DB_HOST), actual.getJdbcUrl());
        assertEquals(defaults.DB_USER, actual.getUsername());
        assertEquals(defaults.DB_PASS, actual.getPassword());
    }

    @Test
    void preservesDockerHostOverride() {
        var actual = DatabaseConnection.getConfig(Map.of("DB_HOST", "db"));
        assertEquals(String.format(YamlConfig.config.server.DB_URL_FORMAT, "db"), actual.getJdbcUrl());
    }

    @Test
    void explicitUrlTakesPrecedenceOverHostAndAllowsLocalPort() {
        String url = "jdbc:mysql://127.0.0.1:3307/cosmic";
        var actual = DatabaseConnection.getConfig(Map.of("DB_URL", url, "DB_HOST", "ignored"));
        assertEquals(url, actual.getJdbcUrl());
    }

    @Test
    void overridesCredentialsIncludingAnEmptyPassword() {
        var actual = DatabaseConnection.getConfig(Map.of("DB_USER", "cosmic", "DB_PASS", ""));
        assertEquals("cosmic", actual.getUsername());
        assertEquals("", actual.getPassword());
    }
}
