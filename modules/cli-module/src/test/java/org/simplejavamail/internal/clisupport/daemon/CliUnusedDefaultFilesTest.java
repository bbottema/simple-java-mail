package org.simplejavamail.internal.clisupport.daemon;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.internal.clisupport.CliMailerConfigurationBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.clisupport.CliExecutionEnvironment;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.MailerProvider;
import org.simplejavamail.internal.clisupport.OneShotMailerProvider;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.wiser.Wiser;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SELECTOR;
import static org.simplejavamail.config.ConfigLoader.Property.DKIM_SIGNING_DOMAIN;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_ENCRYPTION_CERTIFICATE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEYSTORE_PASSWORD;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_ALIAS;
import static org.simplejavamail.config.ConfigLoader.Property.SMIME_SIGNING_KEY_PASSWORD;

class CliUnusedDefaultFilesTest {
    private static final String WITHOUT = "--mailer:withoutEmailDefaults";
    private static final String RESTORE = "--mailer:clearEmailDefaults";

    @TempDir Path directory;

    @ParameterizedTest
    @EnumSource(Material.class)
    void disabledDefaultFileRotationDoesNotChangeTheProfile(final Material material) throws Exception {
        final Path key = Files.writeString(directory.resolve("ordinary.key"), "first material");
        final SimpleJavaMailConfig config = config(material.properties(key.getFileName().toString()));
        final List<CliReceivedOptionData> options = options(WITHOUT);
        final CliMailerProfile first = profile(config, options);
        Files.writeString(key, "rotated material");
        assertThat(profile(config, options)).isEqualTo(first);
    }

    @ParameterizedTest
    @MethodSource("materialsAndOwnership")
    @EnabledOnOs(OS.WINDOWS)
    void unusedUnreadableMaterialDoesNotBlockTheActualValidateCommand(final Material material, final boolean daemon) throws Exception {
        final Path key = Files.writeString(directory.resolve("unreadable.key"), "ordinary material");
        final Properties properties = material.properties(key.toString());
        final MailerProvider provider = daemon ? new DaemonMailerRegistry(3, Duration.ofMinutes(1)) : new OneShotMailerProvider();
        try (FileChannel ignored = lockAgainstReading(key);
                CliExecutionEnvironment environment = new CliExecutionEnvironment(config(properties), provider, directory)) {
            assertUnreadable(key);
            final CliExecutionResult result = validate(environment, daemon, WITHOUT);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
        }
    }

    static Stream<Arguments> materialsAndOwnership() {
        return Stream.of(Material.values()).flatMap(material -> Stream.of(false, true).map(daemon -> Arguments.of(material, daemon)));
    }

    @ParameterizedTest
    @EnumSource(Material.class)
    @EnabledOnOs(OS.WINDOWS)
    void activeRestoredAndLockedMaterialStillMustBeReadable(final Material material) throws Exception {
        final Path key = Files.writeString(directory.resolve("required.key"), "required material");
        final Properties properties = material.properties(key.toString());
        try (FileChannel ignored = lockAgainstReading(key)) {
            assertUnreadable(key);
            for (final List<CliReceivedOptionData> active : List.of(options(), options(WITHOUT, RESTORE))) {
                assertThatThrownBy(() -> profile(config(properties), active))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Unable to derive Mailer identity");
            }
            properties.setProperty(material.property.key().replace("simplejavamail.", "simplejavamail.locked."), key.toString());
            assertThatThrownBy(() -> profile(config(properties), options(WITHOUT)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Unable to derive Mailer identity");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void actualCommandRespectsTheLastDefaultSelection() throws Exception {
        final Path key = Files.writeString(directory.resolve("ordered.key"), "ordinary key");
        try (FileChannel ignored = lockAgainstReading(key);
                CliExecutionEnvironment environment = new CliExecutionEnvironment(config(Material.DKIM.properties(key.toString())),
                        new OneShotMailerProvider(), directory)) {
            assertThat(validate(environment, false, RESTORE, WITHOUT).exitCode()).isZero();
            final CliExecutionResult restored = validate(environment, false, WITHOUT, RESTORE);
            assertThat(restored.exitCode()).isNotZero();
            assertThat(restored.stderr()).contains("Unable to derive Mailer identity");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualDaemonReuseTracksOnlyUsedDefaultMaterial(final boolean disabled) throws Exception {
        final Wiser smtp = Wiser.create(SMTPServer.port(0));
        smtp.start();
        final Path key = Files.writeString(directory.resolve("rotation.key"), "first ordinary key");
        final Properties properties = Material.DKIM.properties(key.toString());
        properties.setProperty("simplejavamail.smtp.port", Integer.toString(smtp.getServer().getPortAllocated()));
        final DaemonMailerRegistry registry = new DaemonMailerRegistry(4, Duration.ofMinutes(1));
        final RecordingProvider provider = new RecordingProvider(registry);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config(properties), provider, directory)) {
            final String[] choices = disabled ? new String[] {WITHOUT} : new String[0];
            assertSuccess(connect(environment, choices));
            Files.writeString(key, "rotated ordinary key");
            assertSuccess(connect(environment, choices));
            assertSuccess(connect(environment, choices));
            assertThat(provider.mailers).hasSize(3);
            if (disabled) assertThat(provider.mailers.get(1)).isSameAs(provider.mailers.get(0));
            else assertThat(provider.mailers.get(1)).isNotSameAs(provider.mailers.get(0));
            assertThat(provider.mailers.get(2)).isSameAs(provider.mailers.get(1));
            assertThat(registry.entryCount()).isEqualTo(disabled ? 1 : 2);
        } finally {
            smtp.stop();
        }
    }

    @Test
    void primaryOptOutDoesNotSuppressAnIndependentSelectedNamedRoute() throws Exception {
        final Path primary = Files.writeString(directory.resolve("primary.key"), "primary");
        final Path selected = Files.writeString(directory.resolve("selected.key"), "selected");
        final Properties properties = Material.DKIM.properties(primary.toString());
        properties.setProperty("simplejavamail.failover.mailer", "selected");
        properties.setProperty("simplejavamail.mailers.selected.smtp.host", "selected.example.org");
        properties.setProperty("simplejavamail.mailers.selected.dkim.signing.private_key_file_or_data", selected.toString());
        final SimpleJavaMailConfig config = config(properties);
        final CliMailerProfile first = profile(config, options(WITHOUT));
        Files.writeString(primary, "unused primary rotation");
        assertThat(profile(config, options(WITHOUT))).isEqualTo(first);
        Files.writeString(selected, "used selected rotation");
        assertThat(profile(config, options(WITHOUT))).isNotEqualTo(first);
    }

    @Test
    void repeatedDefaultChoicesKeepTheirExistingOrderedMeaning() throws Exception {
        final Path key = Files.writeString(directory.resolve("repeated.key"), "first");
        final SimpleJavaMailConfig config = config(Material.DKIM.properties(key.toString()));
        final List<CliReceivedOptionData> disabled = options(WITHOUT, RESTORE, WITHOUT);
        final List<CliReceivedOptionData> restored = options(RESTORE, WITHOUT, RESTORE);
        final CliMailerProfile disabledBefore = profile(config, disabled);
        final CliMailerProfile restoredBefore = profile(config, restored);
        Files.writeString(key, "second");
        assertThat(profile(config, disabled)).isEqualTo(disabledBefore);
        assertThat(profile(config, restored)).isNotEqualTo(restoredBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "file:"})
    void dkimPrefixesAndConfigurationDirectoryRetainTheirFingerprintMeaning(final String prefix) throws Exception {
        Files.writeString(directory.resolve("relative.key"), "configuration key");
        final Path other = Files.createDirectory(directory.resolve("other"));
        Files.writeString(other.resolve("relative.key"), "request key");
        final SimpleJavaMailConfig config = config(Material.DKIM.properties(prefix + "relative.key"));
        assertThat(profile(config, options(RESTORE)))
                .isNotEqualTo(CliMailerProfile.create(config, options(RESTORE), new byte[32], other));
        assertThat(profile(config, options(WITHOUT)))
                .isEqualTo(CliMailerProfile.create(config, options(WITHOUT), new byte[32], other));
    }

    @Test
    void explicitInlineDkimDataRemainsIndependentOfFileContents() throws Exception {
        final Path file = Files.writeString(directory.resolve("unrelated.key"), "first");
        final SimpleJavaMailConfig config = config(Material.DKIM.properties("base64:aW5saW5lIG1hdGVyaWFs"));
        final CliMailerProfile first = profile(config, options(RESTORE));
        Files.writeString(file, "second");
        assertThat(profile(config, options(RESTORE))).isEqualTo(first);
    }

    private CliMailerProfile profile(final SimpleJavaMailConfig config, final List<CliReceivedOptionData> options) {
        return CliMailerProfile.create(config, options, new byte[32], directory);
    }

    private static List<CliReceivedOptionData> options(final String... names) throws Exception {
        final List<CliReceivedOptionData> options = new ArrayList<>();
        for (final String name : names) {
            final Method method = name.equals(WITHOUT) ? MailerGenericBuilder.class.getMethod("withoutEmailDefaults")
                    : CliMailerConfigurationBuilder.class.getMethod("clearEmailDefaults");
            options.add(new CliReceivedOptionData(new CliDeclaredOptionSpec(name, List.of(), List.of(), CliBuilderApiType.MAILER, method), List.of()));
        }
        return options;
    }

    private CliExecutionResult validate(final CliExecutionEnvironment environment, final boolean daemon, final String... choices) {
        final List<String> args = new ArrayList<>(List.of("validate", "--email:startingBlank", "--email:from", "sender@example.org",
                "--email:to", "reader@example.org", "--email:withPlainText", "body"));
        args.addAll(List.of(choices));
        return CliSupport.execute(args.toArray(String[]::new), directory, UUID.randomUUID(), environment, daemon ? new byte[32] : null);
    }

    private CliExecutionResult connect(final CliExecutionEnvironment environment, final String... choices) {
        final List<String> args = new ArrayList<>(List.of("connect"));
        args.addAll(List.of(choices));
        return CliSupport.execute(args.toArray(String[]::new), directory, UUID.randomUUID(), environment, new byte[32]);
    }

    private static void assertSuccess(final CliExecutionResult result) {
        assertThat(result.exitCode()).as(result.stderr()).isZero();
    }

    private static FileChannel lockAgainstReading(final Path key) throws IOException {
        return FileChannel.open(key, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ);
    }

    private static void assertUnreadable(final Path key) {
        assertThat(Files.isRegularFile(key)).isTrue();
        assertThatThrownBy(() -> Files.newInputStream(key)).isInstanceOf(IOException.class);
    }

    private static SimpleJavaMailConfig config(final Properties properties) {
        return ConfigLoader.builder().withProperties(properties).load();
    }

    private enum Material {
        DKIM(DKIM_PRIVATE_KEY_FILE_OR_DATA), SMIME_SIGNING(SMIME_SIGNING_KEYSTORE), SMIME_ENCRYPTION(SMIME_ENCRYPTION_CERTIFICATE);

        private final ConfigLoader.Property property;
        Material(final ConfigLoader.Property property) { this.property = property; }

        Properties properties(final String path) {
            final Properties properties = new Properties();
            properties.setProperty("simplejavamail.smtp.host", "localhost");
            properties.setProperty(property.key(), path);
            if (this == DKIM) {
                properties.setProperty(DKIM_SELECTOR.key(), "mail");
                properties.setProperty(DKIM_SIGNING_DOMAIN.key(), "example.org");
            } else if (this == SMIME_SIGNING) {
                properties.setProperty(SMIME_SIGNING_KEYSTORE_PASSWORD.key(), "password");
                properties.setProperty(SMIME_SIGNING_KEY_ALIAS.key(), "alias");
                properties.setProperty(SMIME_SIGNING_KEY_PASSWORD.key(), "password");
            }
            return properties;
        }
    }

    private static final class RecordingProvider implements MailerProvider {
        private final MailerProvider delegate;
        private final List<Mailer> mailers = new ArrayList<>();
        private RecordingProvider(final MailerProvider delegate) { this.delegate = delegate; }

        @Override public Lease acquire(final CliMailerProfile profile, final Supplier<Mailer> factory) {
            final Lease lease = delegate.acquire(profile, factory);
            mailers.add(lease.mailer());
            return lease;
        }
        @Override public void close() { delegate.close(); }
    }
}
