package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderRequiredInputsTest {

    @TempDir
    Path directory;

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredRegistrations")
    void rejectedRegistrationLeavesEarlierSourcesAndLaterRegistrationUsable(final String route,
            final String argumentName, final Consumer<ConfigLoader> registration) {
        final ConfigLoader loader = ConfigLoader.builder().withMap(Map.of(ConfigKeys.SMTP_HOST.key(), "before.example.test"));

        assertThatThrownBy(() -> registration.accept(loader))
                .isInstanceOf(NullPointerException.class).hasMessage(argumentName);

        assertThat(loader.load().get(ConfigKeys.SMTP_HOST)).isEqualTo("before.example.test");
        loader.withMap(Map.of(ConfigKeys.SMTP_HOST.key(), "after.example.test"));
        assertThat(loader.load().get(ConfigKeys.SMTP_HOST)).isEqualTo("after.example.test");
    }

    private static Stream<Arguments> requiredRegistrations() {
        return Stream.of(
                registration("custom source", "source", loader -> loader.withSource(null)),
                registration("Properties", "properties", loader -> loader.withProperties((Properties) null)),
                registration("named Properties", "properties", loader -> loader.withProperties("properties", null)),
                registration("map", "properties", loader -> loader.withMap((Map<String, ?>) null)),
                registration("named map", "properties", loader -> loader.withMap("map", null)),
                registration("file", "propertiesFile", loader -> loader.withPropertiesFile((Path) null)),
                registration("named file", "propertiesFile", loader -> loader.withPropertiesFile("file", null)),
                registration("resource", "resourceName", loader -> loader.withClasspathResource(null)),
                registration("resource with ClassLoader", "resourceName",
                        loader -> loader.withClasspathResource(null, ConfigLoaderRequiredInputsTest.class.getClassLoader())),
                registration("ClassLoader", "classLoader", loader -> loader.withClasspathResource("optional.properties", null)),
                registration("environment map", "environment", loader -> loader.withEnvironmentVariables(null)),
                registration("system Properties", "properties", loader -> loader.withSystemProperties(null)));
    }

    private static Arguments registration(final String route, final String argumentName, final Consumer<ConfigLoader> registration) {
        return Arguments.of(route, argumentName, registration);
    }

    @Test
    void customSourcesAndMapsStayLazyAndAreSampledOncePerLoad() {
        final AtomicInteger reads = new AtomicInteger();
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put(ConfigKeys.SMTP_HOST.key(), "first.example.test");
        final ConfigLoader loader = ConfigLoader.builder().withSource(new ConfigSource() {
            @Override public String getName() { return "changing source"; }
            @Override public Map<String, ?> getProperties() { reads.incrementAndGet(); return values; }
        }).withMap("later map", values);

        assertThat(reads).hasValue(0);
        final SimpleJavaMailConfig first = loader.load();
        assertThat(reads).hasValue(1);
        values.put(ConfigKeys.SMTP_HOST.key(), "second.example.test");
        assertThat(loader.load().get(ConfigKeys.SMTP_HOST)).isEqualTo("second.example.test");
        assertThat(reads).hasValue(2);
        assertThat(first.get(ConfigKeys.SMTP_HOST)).isEqualTo("first.example.test");
    }

    @Test
    void requiredFileIsOpenedOnlyAtLoadAndResampled() throws IOException {
        final Path file = directory.resolve("changing.properties");
        final ConfigLoader loader = ConfigLoader.builder().withPropertiesFile(file);
        assertThatThrownBy(loader::load).isInstanceOf(IllegalStateException.class);

        Files.writeString(file, ConfigKeys.SMTP_HOST.key() + "=first.example.test");
        final SimpleJavaMailConfig first = loader.load();
        Files.writeString(file, ConfigKeys.SMTP_HOST.key() + "=second.example.test");

        assertThat(loader.load().get(ConfigKeys.SMTP_HOST)).isEqualTo("second.example.test");
        assertThat(first.get(ConfigKeys.SMTP_HOST)).isEqualTo("first.example.test");
    }

    @Test
    void optionalResourceIsLookedUpOnlyAtLoadAndMayBeAbsent() {
        final AtomicInteger reads = new AtomicInteger();
        final ClassLoader resources = new ClassLoader(null) {
            @Override public InputStream getResourceAsStream(final String name) { reads.incrementAndGet(); return null; }
        };
        final ConfigLoader loader = ConfigLoader.builder().withClasspathResource("missing.properties", resources);

        assertThat(reads).hasValue(0);
        assertThat(loader.load().asMap()).isEmpty();
        assertThat(reads).hasValue(1);
    }

    @Test
    void sourceNamesAndContentsKeepTheirLoadTimeValidation() {
        final ConfigLoader unnamed = ConfigLoader.builder().withMap(null, Map.of());
        final ConfigLoader unknownProperty = ConfigLoader.builder().withMap(Map.of("unknown.setting", "value"));

        assertThatThrownBy(unnamed::load).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name was blank");
        assertThatThrownBy(unknownProperty::load).isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown.setting");
    }

    @Test
    void callerStreamStillClosesDuringRegistration() {
        final AtomicInteger closes = new AtomicInteger();
        final InputStream stream = new ByteArrayInputStream((ConfigKeys.SMTP_HOST.key() + "=stream.example.test")
                .getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
        };
        final ConfigLoader loader = ConfigLoader.builder().withInputStream(stream);

        assertThat(closes).hasValue(1);
        assertThat(loader.load().get(ConfigKeys.SMTP_HOST)).isEqualTo("stream.example.test");
    }
}
