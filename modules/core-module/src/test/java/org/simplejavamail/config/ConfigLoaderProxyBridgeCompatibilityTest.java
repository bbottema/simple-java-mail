package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Removed bridge inputs must never disappear silently from permissive deployment sources. */
class ConfigLoaderProxyBridgeCompatibilityTest {
    private static final String BRIDGE_PORT = "simplejavamail.proxy.socks5bridge.port";
    private static final String LOCKED_BRIDGE_PORT = "simplejavamail.locked.proxy.socks5bridge.port";
    private static final String NAMED_BRIDGE_PORT = "simplejavamail.mailers.backup.proxy.socks5bridge.port";
    private static final String LOCKED_NAMED_BRIDGE_PORT = "simplejavamail.locked.mailers.backup.proxy.socks5bridge.port";

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {BRIDGE_PORT, LOCKED_BRIDGE_PORT, NAMED_BRIDGE_PORT, LOCKED_NAMED_BRIDGE_PORT})
    void mapInputsExplainWhichRemovedSettingMustBeDeleted(final String key) {
        assertRemoved(ConfigLoader.builder().withMap("deployment", Map.of(key, "1081")), key, "deployment");
    }

    @Test
    void inheritedPropertiesCannotRetainTheRemovedSetting() {
        final Properties defaults = new Properties();
        defaults.setProperty(BRIDGE_PORT, "1081");
        assertRemoved(ConfigLoader.builder().withProperties("inherited deployment", new Properties(defaults)),
                BRIDGE_PORT, "inherited deployment");
    }

    @ParameterizedTest
    @ValueSource(strings = {BRIDGE_PORT, LOCKED_BRIDGE_PORT, NAMED_BRIDGE_PORT, LOCKED_NAMED_BRIDGE_PORT})
    void fileInputsIdentifyTheFileToMigrate(final String key) throws IOException {
        final Path file = directory.resolve("proxy.properties");
        Files.writeString(file, key + "=1081\n");
        assertRemoved(ConfigLoader.builder().withPropertiesFile(file), key, "file:" + file);
    }

    @ParameterizedTest
    @ValueSource(strings = {BRIDGE_PORT, LOCKED_BRIDGE_PORT, NAMED_BRIDGE_PORT, LOCKED_NAMED_BRIDGE_PORT})
    void permissiveSystemPropertiesStillRejectRemovedSettings(final String key) {
        final Properties properties = new Properties();
        properties.setProperty(key, "1081");
        properties.setProperty("unrelated.application.setting", "ignored");
        assertRemoved(ConfigLoader.builder().withSystemProperties(properties), key, "system properties");
    }

    @Test
    void permissiveCustomSourceStillIdentifiesItsRemovedSetting() {
        final ConfigSource source = new ConfigSource() {
            @Override public String getName() { return "custom deployment"; }
            @Override public Map<String, ?> getProperties() {
                return Map.of(BRIDGE_PORT, "1081", "unrelated.application.setting", "ignored");
            }
            @Override public boolean isStrict() { return false; }
        };
        assertRemoved(ConfigLoader.builder().withSource(source), BRIDGE_PORT, "custom deployment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIMPLEJAVAMAIL_PROXY_SOCKS5BRIDGE_PORT", "SIMPLEJAVAMAIL_LOCKED_PROXY_SOCKS5BRIDGE_PORT",
            NAMED_BRIDGE_PORT, LOCKED_NAMED_BRIDGE_PORT,
            "SIMPLEJAVAMAIL_MAILERS_BACKUP_PROXY_SOCKS5BRIDGE_PORT", "SIMPLEJAVAMAIL_LOCKED_MAILERS_BACKUP_PROXY_SOCKS5BRIDGE_PORT",
            "SIMPLEJAVAMAIL_MAILERS_MY_BACKUP_PROXY_SOCKS5BRIDGE_PORT", "SIMPLEJAVAMAIL_LOCKED_MAILERS_MY_BACKUP_PROXY_SOCKS5BRIDGE_PORT"})
    void environmentInputsCannotDisappearAfterSchemaRemoval(final String key) {
        assertRemoved(ConfigLoader.builder().withEnvironmentVariables(Map.of(key, "1081")), key, "environment variables");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "1081", "private-invalid-port"})
    void anyExplicitValueRequiresRemovingTheSetting(final String value) {
        final ConfigLoader loader = ConfigLoader.builder().withMap("proxy deployment", Map.of(
                BRIDGE_PORT, value, "simplejavamail.proxy.password", "private-proxy-password"));
        assertThatThrownBy(loader::load).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll(BRIDGE_PORT, "proxy deployment", "Remove property", "connects directly")
                .hasMessageNotContaining("private-invalid-port").hasMessageNotContaining("private-proxy-password");
    }

    @Test
    void unrelatedPermissiveInputsAndRemoteProxySettingsKeepTheirMeaning() {
        final Properties properties = new Properties();
        properties.setProperty("unrelated.application.setting", "ignored");
        properties.setProperty("simplejavamail.proxy.host", "proxy.example.test");
        properties.setProperty("simplejavamail.proxy.port", "1081");
        final SimpleJavaMailConfig config = ConfigLoader.builder().withSystemProperties(properties).load();
        assertThat(config.get(ConfigKeys.PROXY_HOST)).isEqualTo("proxy.example.test");
        assertThat(config.get(ConfigKeys.PROXY_PORT)).isEqualTo(1081);
        assertThat(config.asMap().keySet()).noneMatch(property -> property.key().contains("socks5bridge"));
    }

    private static void assertRemoved(final ConfigLoader loader, final String key, final String source) {
        assertThatThrownBy(loader::load).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll("authenticated SOCKS bridge has been removed", "Remove property", key, source, "connects directly");
    }
}
