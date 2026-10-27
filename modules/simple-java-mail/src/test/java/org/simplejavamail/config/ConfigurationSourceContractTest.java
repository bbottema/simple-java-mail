package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.config.ConfigLoader.Property;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Source access, source precedence and typed schema projection have independent executable witnesses. */
class ConfigurationSourceContractTest {
    @ParameterizedTest
    @EnumSource(Property.class)
    void everyCanonicalPropertyReadsTheSelectedSourceThroughItsDeclaredJavaType(final Property property) throws Exception {
        final ConfigurationSchemaFixture fixture = ConfigurationSchemaFixture.forProperty(property);
        final SimpleJavaMailConfig config = ConfigLoader.builder()
                .withMap("lower schema source", fixture.lowerSource()).withMap("upper schema source", fixture.upperSource()).load();
        fixture.assertUpperSelection(config);
        assertThatThrownBy(() -> config.asMap().clear()).isInstanceOf(UnsupportedOperationException.class);
        record("property", property.key());
        record("rule", "source.schema-projection");
        record("case", "source.schema");
    }

    enum SourceRoute {
        MAP, NAMED_MAP, PROPERTIES, NAMED_PROPERTIES, STREAM, NAMED_STREAM,
        FILE, NAMED_FILE, CLASSPATH, CLASSPATH_LOADER, CUSTOM, SNAPSHOT
    }

    @ParameterizedTest
    @EnumSource(SourceRoute.class)
    void everyPublicSourceAdapterSuppliesTheSnapshotAndItsConsumer(final SourceRoute route, @TempDir final Path directory) throws Exception {
        final ConfigLoader loader = (ConfigLoader) invoke(null, ConfigLoader.class.getMethod("builder"));
        final Map<String, String> source = Map.of(ConfigKeys.DEFAULT_SUBJECT.key(), "adapter-value");
        final Properties properties = new Properties();
        properties.putAll(source);
        final ClosingInputStream stream = new ClosingInputStream();
        final Path file = directory.resolve("source.properties");
        Files.writeString(file, ConfigKeys.DEFAULT_SUBJECT.key() + "=adapter-value\n");
        try (URLClassLoader resourceLoader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, null)) {
            final String origin;
            final String adapter;
            switch (route) {
                case MAP:
                    invoke(loader, ConfigLoader.class.getMethod("withMap", Map.class), source);
                    origin = "map"; adapter = "source.map"; break;
                case NAMED_MAP:
                    invoke(loader, ConfigLoader.class.getMethod("withMap", String.class, Map.class), "named map", source);
                    origin = "named map"; adapter = "source.map"; break;
                case PROPERTIES:
                    invoke(loader, ConfigLoader.class.getMethod("withProperties", Properties.class), properties);
                    origin = "properties"; adapter = "source.properties"; break;
                case NAMED_PROPERTIES:
                    invoke(loader, ConfigLoader.class.getMethod("withProperties", String.class, Properties.class), "named properties", properties);
                    origin = "named properties"; adapter = "source.properties"; break;
                case STREAM:
                    invoke(loader, ConfigLoader.class.getMethod("withInputStream", InputStream.class), stream);
                    origin = "input stream"; adapter = "source.input-stream"; break;
                case NAMED_STREAM:
                    invoke(loader, ConfigLoader.class.getMethod("withInputStream", String.class, InputStream.class), "named stream", stream);
                    origin = "named stream"; adapter = "source.input-stream"; break;
                case FILE:
                    invoke(loader, ConfigLoader.class.getMethod("withPropertiesFile", Path.class), file);
                    origin = "file:" + file; adapter = "source.file"; break;
                case NAMED_FILE:
                    invoke(loader, ConfigLoader.class.getMethod("withPropertiesFile", String.class, Path.class), "named file", file);
                    origin = "named file"; adapter = "source.file"; break;
                case CLASSPATH:
                    invoke(loader, ConfigLoader.class.getMethod("withClasspathResource", String.class), "configuration-source-contract.properties");
                    origin = "classpath:configuration-source-contract.properties"; adapter = "source.classpath"; break;
                case CLASSPATH_LOADER:
                    invoke(loader, ConfigLoader.class.getMethod("withClasspathResource", String.class, ClassLoader.class), "source.properties", resourceLoader);
                    origin = "classpath:source.properties"; adapter = "source.classpath"; break;
                case CUSTOM:
                    invoke(loader, ConfigLoader.class.getMethod("withSource", ConfigSource.class), namedSource("custom source", source));
                    origin = "custom source"; adapter = "source.custom"; break;
                case SNAPSHOT:
                    invoke(loader, ConfigLoader.class.getMethod("withConfig", SimpleJavaMailConfig.class), ConfigLoader.builder().withMap(source).load());
                    origin = "existing SimpleJavaMailConfig"; adapter = "source.snapshot"; break;
                default: throw new AssertionError(route);
            }
            final SimpleJavaMailConfig config = (SimpleJavaMailConfig) invoke(loader, ConfigLoader.class.getMethod("load"));
            assertThat(config.get(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("adapter-value");
            assertThat(config.getPropertySource(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo(origin);
            assertThat(SimpleJavaMail.withConfig(config).emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getSubject())
                    .isEqualTo("adapter-value");
            if (route == SourceRoute.STREAM || route == SourceRoute.NAMED_STREAM) { assertThat(stream.closed).isTrue(); }
            if (route == SourceRoute.FILE || route == SourceRoute.NAMED_FILE) { Files.delete(file); assertThat(file).doesNotExist(); }
            record("adapter", adapter);
            record("case", "source.adapters");
            if (route == SourceRoute.STREAM || route == SourceRoute.NAMED_STREAM || route == SourceRoute.FILE || route == SourceRoute.NAMED_FILE) {
                record("rule", "source.stream-file-lifecycle");
            }
        }
    }

    @Test
    @SetEnvironmentVariable(key = "SIMPLEJAVAMAIL_DEFAULTS_SUBJECT", value = "environment contract subject")
    void publicEnvironmentAdapterNormalizesItsRealProcessInput() throws Exception {
        final ConfigLoader loader = ConfigLoader.builder().withMap(Map.of(ConfigKeys.DEFAULT_SUBJECT.key(), "lower"));
        invoke(loader, ConfigLoader.class.getMethod("withEnvironmentVariables"));
        final SimpleJavaMailConfig config = loader.load();
        assertThat(config.get(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("environment contract subject");
        assertThat(config.getPropertySource(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("environment variables");
        assertThat(SimpleJavaMail.withConfig(config).emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getSubject())
                .isEqualTo("environment contract subject");
        record("adapter", "source.environment");
        record("case", "source.environment");
    }

    @Test
    @SetSystemProperty(key = "simplejavamail.defaults.subject", value = "system contract subject")
    void publicSystemAdapterReadsCurrentJvmProperties() throws Exception {
        final ConfigLoader loader = ConfigLoader.builder().withMap(Map.of(ConfigKeys.DEFAULT_SUBJECT.key(), "lower"));
        invoke(loader, ConfigLoader.class.getMethod("withSystemProperties"));
        final SimpleJavaMailConfig config = loader.load();
        assertThat(config.get(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("system contract subject");
        assertThat(config.getPropertySource(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("system properties");
        assertThat(SimpleJavaMail.withConfig(config).emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getSubject())
                .isEqualTo("system contract subject");
        record("adapter", "source.system-properties");
        record("case", "source.system-properties");
    }

    @Test
    void priorityBlankInheritanceAndWinnerOnlyParsingAreIndependentSourceRules() {
        final Properties inherited = new Properties();
        inherited.setProperty(ConfigKeys.DEFAULT_SUBJECT.key(), "inherited subject");
        final Properties direct = new Properties(inherited);
        direct.setProperty(ConfigKeys.SMTP_HOST.key(), "lower.example.test");
        final SimpleJavaMailConfig config = ConfigLoader.builder().withProperties("lower", direct)
                .withMap("invalid loser", Map.of(ConfigKeys.SMTP_PORT.key(), "not a number"))
                .withMap("upper", Map.of(ConfigKeys.SMTP_HOST.key(), "upper.example.test", ConfigKeys.SMTP_PORT.key(), "2525",
                        ConfigKeys.DEFAULT_SUBJECT.key(), " ")).load();
        assertThat(config.get(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("inherited subject");
        assertThat(config.getPropertySource(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("lower");
        assertThat(config.get(ConfigKeys.SMTP_HOST)).isEqualTo("upper.example.test");
        assertThat(config.getPropertySource(ConfigKeys.SMTP_HOST)).isEqualTo("upper");
        assertThat(config.get(ConfigKeys.SMTP_PORT)).isEqualTo(2525);
        record("rule", "source.precedence");
        record("rule", "source.blank-inheritance");
        record("rule", "source.winning-parse");
        record("rule", "source.inherited-properties");
        record("rule", "source.provenance");
        record("case", "source.priority");
    }

    @Test
    void aSourceIsSampledOncePerLoadAndCompletedSnapshotsStayDetached() {
        final AtomicInteger samples = new AtomicInteger();
        final Map<String, Object> values = new LinkedHashMap<>(Map.of(ConfigKeys.DEFAULT_SUBJECT.key(), "first"));
        final ConfigLoader loader = ConfigLoader.builder().withSource(new ConfigSource() {
            @Override public String getName() { return "sampled source"; }
            @Override public Map<String, ?> getProperties() { samples.incrementAndGet(); return values; }
        });
        final SimpleJavaMailConfig first = loader.load();
        assertThat(samples).hasValue(1);
        values.put(ConfigKeys.DEFAULT_SUBJECT.key(), "second");
        final SimpleJavaMailConfig second = loader.load();
        assertThat(samples).hasValue(2);
        assertThat(first.get(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("first");
        assertThat(second.get(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("second");
        assertThatThrownBy(() -> first.asMap().clear()).isInstanceOf(UnsupportedOperationException.class);
        record("rule", "source.sampling");
        record("rule", "source.snapshot-immutability");
        record("case", "source.sampling");
    }

    @Test
    void locksAndWildcardChildrenKeepTheirOwnValuesAndSourcesThroughSnapshotCopying() {
        final SimpleJavaMailConfig original = ConfigLoader.builder().withMap("central source", Map.of(
                "simplejavamail.locked.defaults.subject", "fixed subject",
                "simplejavamail.locked.extraproperties.application.marker", "fixed raw",
                "simplejavamail.mailers.backup.smtp.host", "backup.example.test"))
                .withMap("application source", Map.of(ConfigKeys.DEFAULT_SUBJECT.key(), "ignored subject",
                        "simplejavamail.extraproperties.application.marker", "ignored raw")).load();
        final SimpleJavaMailConfig copy = ConfigLoader.builder().withConfig(original).load();
        assertThat(copy.get(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("fixed subject");
        assertThat(copy.get(ConfigKeys.EXTRA_PROPERTIES)).containsEntry("application.marker", "fixed raw");
        assertThat(copy.get(ConfigKeys.NAMED_MAILERS).get("backup").get(ConfigKeys.SMTP_HOST)).isEqualTo("backup.example.test");
        assertThat(original.getPropertySource(ConfigKeys.DEFAULT_SUBJECT)).isEqualTo("central source");
        assertThat(locksOf(copy).contains(Property.DEFAULT_SUBJECT)).isTrue();
        assertThatThrownBy(() -> locksOf(copy).verify(Property.DEFAULT_SUBJECT, "different")).hasMessageContaining("locked.defaults.subject");
        record("rule", "source.lock-authority");
        record("rule", "source.wildcard-containers");
        record("case", "source.lock-and-wildcard");
    }

    @Test
    void strictCallerSourcesRejectUnknownKeysWhileProcessSourcesIgnoreUnrelatedKeys() {
        assertThatThrownBy(() -> ConfigLoader.builder().withMap(Map.of("application.unrelated", "unused")).load())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("application.unrelated");
        final Properties unrelated = new Properties();
        unrelated.setProperty("application.unrelated", "unused");
        assertThat(ConfigLoader.builder().withSystemProperties(unrelated).load().asMap()).isEmpty();
        record("rule", "source.strictness");
        record("case", "source.strictness");
    }

    private static ConfigSource namedSource(final String name, final Map<String, ?> values) {
        return new ConfigSource() {
            @Override public String getName() { return name; }
            @Override public Map<String, ?> getProperties() { return values; }
        };
    }

    private static final class ClosingInputStream extends ByteArrayInputStream {
        private boolean closed;
        private ClosingInputStream() { super((ConfigKeys.DEFAULT_SUBJECT.key() + "=adapter-value\n").getBytes(StandardCharsets.UTF_8)); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
