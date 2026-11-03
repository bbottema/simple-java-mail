package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderProxyBridgeCompatibilityTest {

    private static final String BRIDGE_PORT = "simplejavamail.proxy.socks5bridge.port";
    private static final String LOCKED_BRIDGE_PORT = "simplejavamail.locked.proxy.socks5bridge.port";
    private static final String NAMED_BRIDGE_PORT = "simplejavamail.mailers.backup.proxy.socks5bridge.port";
    private static final String LOCKED_NAMED_BRIDGE_PORT = "simplejavamail.locked.mailers.backup.proxy.socks5bridge.port";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {BRIDGE_PORT, LOCKED_BRIDGE_PORT, NAMED_BRIDGE_PORT, LOCKED_NAMED_BRIDGE_PORT})
    void mapInputsRetainBridgePortValuesAndSources(final String key) {
        assertBridgePort(ConfigLoader.builder().withMap("deployment", Map.of(key, "1081")).load(), key, 1081, "deployment");
    }

    @Test
    void inheritedPropertiesRetainTheBridgePort() {
        final Properties defaults = new Properties();
        defaults.setProperty(BRIDGE_PORT, "1081");
        assertBridgePort(ConfigLoader.builder().withProperties("inherited deployment", new Properties(defaults)).load(),
                BRIDGE_PORT, 1081, "inherited deployment");
    }

    @ParameterizedTest
    @ValueSource(strings = {BRIDGE_PORT, LOCKED_BRIDGE_PORT, NAMED_BRIDGE_PORT, LOCKED_NAMED_BRIDGE_PORT})
    void fileInputsRetainTheActualSource(final String key) throws IOException {
        final Path file = directory.resolve("proxy.properties");
        Files.writeString(file, key + "=1081\n");
        assertBridgePort(ConfigLoader.builder().withPropertiesFile(file).load(), key, 1081, "file:" + file);
    }

    @ParameterizedTest
    @ValueSource(strings = {BRIDGE_PORT, LOCKED_BRIDGE_PORT, NAMED_BRIDGE_PORT, LOCKED_NAMED_BRIDGE_PORT})
    void nonStrictSystemPropertiesRetainTheBridgePort(final String key) {
        final Properties properties = new Properties();
        properties.setProperty(key, "1081");
        properties.setProperty("unrelated.application.setting", "ignored");
        assertBridgePort(ConfigLoader.builder().withSystemProperties(properties).load(), key, 1081, "system properties");
    }

    @Test
    void nonStrictCustomSourcesRetainTheBridgePortAlongsideUnrelatedInputs() {
        final ConfigSource source = new ConfigSource() {
            @Override public String getName() { return "custom deployment"; }
            @Override public Map<String, ?> getProperties() {
                return Map.of(BRIDGE_PORT, "1081", "unrelated.application.setting", "ignored");
            }
            @Override public boolean isStrict() { return false; }
        };
        assertBridgePort(ConfigLoader.builder().withSource(source).load(), BRIDGE_PORT, 1081, "custom deployment");
    }

    @ParameterizedTest
    @ValueSource(strings = {BRIDGE_PORT, LOCKED_BRIDGE_PORT})
    void uppercaseEnvironmentInputsRetainTheBridgePort(final String key) {
        final String environmentName = key.replace('.', '_').toUpperCase(Locale.ROOT);
        assertBridgePort(ConfigLoader.builder().withEnvironmentVariables(Map.of(environmentName, "1081")).load(),
                key, 1081, "environment variables");
    }

    @ParameterizedTest
    @ValueSource(strings = {NAMED_BRIDGE_PORT, LOCKED_NAMED_BRIDGE_PORT})
    void dottedNamedEnvironmentInputsRetainTheBridgePort(final String key) {
        assertBridgePort(ConfigLoader.builder().withEnvironmentVariables(Map.of(key, "1081")).load(),
                key, 1081, "environment variables");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1081, 65535})
    void automaticAndFixedListenerPortsRemainConfiguredValues(final int port) {
        assertBridgePort(ConfigLoader.builder().withMap("listener", Map.of(BRIDGE_PORT, Integer.toString(port))).load(),
                BRIDGE_PORT, port, "listener");
    }

    @ParameterizedTest
    @ValueSource(strings = {LOCKED_BRIDGE_PORT, LOCKED_NAMED_BRIDGE_PORT})
    void bridgePortLocksSurviveSnapshotCopyAndHigherPriorityOrdinaryInputs(final String key) {
        final SimpleJavaMailConfig original = ConfigLoader.builder().withMap("central deployment", Map.of(key, "1081")).load();
        final String ordinaryKey = key.replace("simplejavamail.locked.", "simplejavamail.");
        final SimpleJavaMailConfig copied = ConfigLoader.builder().withConfig(original)
                .withMap("local override", Map.of(ordinaryKey, "1082")).load();

        assertBridgePort(copied, key, 1081, "existing SimpleJavaMailConfig");
        assertBridgePort(original, key, 1081, "central deployment");
    }

    @Test
    void invalidBridgePortStillUsesSecretSafeSchemaValidation() {
        final ConfigLoader loader = ConfigLoader.builder().withMap("proxy deployment", Map.of(
                BRIDGE_PORT, "private-invalid-port", "simplejavamail.proxy.password", "private-proxy-password"));

        assertThatThrownBy(loader::load).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContainingAll(BRIDGE_PORT, "proxy deployment", "integer")
                .hasMessageNotContaining("private-invalid-port").hasMessageNotContaining("private-proxy-password");
    }

    private static void assertBridgePort(final SimpleJavaMailConfig config, final String key, final int port, final String source) {
        final SimpleJavaMailConfig selected = key.contains(".mailers.backup.") ? config.get(ConfigKeys.NAMED_MAILERS).get("backup") : config;
        assertThat(selected.get(ConfigKeys.PROXY_SOCKS5BRIDGE_PORT)).isEqualTo(port);
        assertThat(selected.getPropertySource(ConfigKeys.PROXY_SOCKS5BRIDGE_PORT)).isEqualTo(source);
        assertThat(config.getDiagnostics().getGroups().stream()
                .flatMap(group -> config.getDiagnostics().getProperties(group).stream())
                .filter(property -> property.getPropertyName().equals(key)))
                .singleElement().satisfies(property -> {
                    assertThat(property.getDisplayValue()).isEqualTo(Integer.toString(port));
                    assertThat(property.getSourceName()).isEqualTo(source);
                });
    }
}
