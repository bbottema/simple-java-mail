package org.simplejavamail.internal.clisupport.daemon;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.clisupport.CliExecutionEnvironment;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.MailerProvider;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliDkimKeyRotationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"", "file:"})
    void repeatedCommandsReuseTheMailerUntilItsDkimFileChanges(final String prefix) throws Exception {
        final byte[] firstKey = privateKey();
        final byte[] replacementKey = privateKey();
        final Path file = Files.write(directory.resolve("dkim.der"), firstKey);
        final SimpleJavaMailConfig configuration = configuration(prefix + file);
        final RecordingMailerProvider provider = new RecordingMailerProvider();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(configuration, provider, directory)) {
            validate(environment);
            validate(environment);
            final Mailer first = provider.mailers.get(0);
            assertThat(provider.mailers.get(1)).isSameAs(first);
            assertThat(signingKey(first)).containsExactly(firstKey);

            Files.write(file, replacementKey);
            validate(environment);
            final Mailer replacement = provider.mailers.get(2);
            assertThat(replacement).isNotSameAs(first);
            assertThat(signingKey(replacement)).containsExactly(replacementKey);
            assertThat(signingKey(first)).containsExactly(firstKey);
            assertThat(provider.registry.entryCount()).isEqualTo(2);
        }
    }

    @Test
    void relativePrefixedPathsUseTheConfigurationDirectory() throws Exception {
        final Path otherDirectory = Files.createDirectory(directory.resolve("other"));
        final Path file = Files.writeString(directory.resolve("key.der"), "first key");
        Files.writeString(otherDirectory.resolve("key.der"), "other key");
        final SimpleJavaMailConfig configuration = configuration("file:key.der");
        final CliMailerProfile first = profile(configuration);
        final CliMailerProfile other = CliMailerProfile.create(configuration, List.of(), new byte[32], otherDirectory);

        assertThat(first).isNotEqualTo(other);
        Files.writeString(file, "replacement key");
        assertThat(profile(configuration)).isNotEqualTo(first);
        assertThat(CliMailerProfile.create(configuration, List.of(), new byte[32], otherDirectory)).isEqualTo(other);
    }

    @Test
    void selectedTransitivePrefixedKeysRetainContentAndLockIdentity() throws Exception {
        final Path selected = Files.writeString(directory.resolve("selected.der"), "selected key");
        final Path unused = Files.writeString(directory.resolve("unused.der"), "unused key");
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.failover.mailer", "first");
        properties.setProperty("simplejavamail.mailers.first.smtp.host", "first.example.org");
        properties.setProperty("simplejavamail.mailers.first.failover.mailer", "last");
        properties.setProperty("simplejavamail.mailers.last.smtp.host", "last.example.org");
        properties.setProperty("simplejavamail.mailers.last.dkim.signing.private_key_file_or_data", "file:selected.der");
        properties.setProperty("simplejavamail.mailers.unused.dkim.signing.private_key_file_or_data", "file:unused.der");
        final SimpleJavaMailConfig configuration = ConfigLoader.builder().withProperties(properties).load();
        final CliMailerProfile first = profile(configuration);

        Files.writeString(unused, "unused replacement");
        assertThat(profile(configuration)).isEqualTo(first);
        Files.writeString(selected, "selected replacement");
        assertThat(profile(configuration)).isNotEqualTo(first);
        Files.writeString(selected, "selected key");
        assertThat(profile(configuration)).isEqualTo(first);

        properties.setProperty("simplejavamail.locked.mailers.last.dkim.signing.private_key_file_or_data", "file:selected.der");
        final SimpleJavaMailConfig locked = ConfigLoader.builder().withProperties(properties).load();
        final CliMailerProfile lockedProfile = profile(locked);
        assertThat(lockedProfile).isNotEqualTo(first);
        Files.writeString(selected, "locked replacement");
        assertThat(profile(locked)).isNotEqualTo(lockedProfile);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "base64:"})
    void inlineKeyChangesStillSeparateProfiles(final String prefix) throws Exception {
        final Path unrelated = Files.writeString(directory.resolve("unrelated.der"), "unrelated key");
        final String firstValue = prefix + Base64.getEncoder().encodeToString("first inline key".getBytes(StandardCharsets.UTF_8));
        final String secondValue = prefix + Base64.getEncoder().encodeToString("second inline key".getBytes(StandardCharsets.UTF_8));
        final SimpleJavaMailConfig firstConfiguration = configuration(firstValue);
        final CliMailerProfile first = profile(firstConfiguration);
        Files.writeString(unrelated, "replacement unrelated key");
        assertThat(profile(firstConfiguration)).isEqualTo(first);
        assertThat(profile(configuration(secondValue))).isNotEqualTo(first);
        assertThat(first.toString()).isEqualTo("CliMailerProfile{<redacted>}");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void explicitBase64DoesNotProbeALookalikeFilename() throws Exception {
        final String inlineValue = "base64:a2V5";
        final Path lookalike = Files.writeString(directory.resolve(inlineValue), "unrelated file");
        final SimpleJavaMailConfig configuration = configuration(inlineValue);
        final CliMailerProfile first = profile(configuration);
        Files.writeString(lookalike, "unrelated replacement");
        assertThat(profile(configuration)).isEqualTo(first);
    }

    @Test
    void missingExplicitFileKeepsTheOrdinaryConstructionFailure() {
        final RecordingMailerProvider provider = new RecordingMailerProvider();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(
                configuration("file:" + directory.resolve("missing.der")), provider, directory)) {
            final CliExecutionResult result = executeValidation(environment);
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains("does not exist or is not a regular file");
            assertThat(provider.registry.entryCount()).isZero();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void unreadableExplicitFilesKeepTheProfileReadFailure() throws Exception {
        final Path key = Files.writeString(directory.resolve("locked.der"), "private key");
        final SimpleJavaMailConfig configuration = configuration("file:" + key);
        try (FileChannel ignored = FileChannel.open(key, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
            assertThatThrownBy(() -> profile(configuration)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unable to derive Mailer identity").hasCauseInstanceOf(IOException.class);
        }
    }

    private void validate(final CliExecutionEnvironment environment) {
        final CliExecutionResult result = executeValidation(environment);
        assertThat(result.exitCode()).describedAs(result.stderr()).isZero();
    }

    private CliExecutionResult executeValidation(final CliExecutionEnvironment environment) {
        return CliSupport.execute(new String[] {"validate", "--email:startingBlank",
                "--email:from", "sender@supersecret-testing-domain.com", "--email:to", "recipient@example.org",
                "--email:withPlainText", "A locally prepared signed message"}, directory,
                UUID.randomUUID(), environment, new byte[32]);
    }

    private CliMailerProfile profile(final SimpleJavaMailConfig configuration) {
        return CliMailerProfile.create(configuration, List.of(), new byte[32], directory);
    }

    private static SimpleJavaMailConfig configuration(final String key) {
        final Properties properties = new Properties();
        properties.setProperty(ConfigLoader.Property.SMTP_HOST.key(), "localhost");
        properties.setProperty(ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), key);
        properties.setProperty(ConfigLoader.Property.DKIM_SIGNING_DOMAIN.key(), "supersecret-testing-domain.com");
        properties.setProperty(ConfigLoader.Property.DKIM_SELECTOR.key(), "mail");
        return ConfigLoader.builder().withProperties(properties).load();
    }

    private static byte[] signingKey(final Mailer mailer) {
        return mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(null).getDkimConfig().getDkimPrivateKeyData();
    }

    private static byte[] privateKey() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair().getPrivate().getEncoded();
    }

    private static final class RecordingMailerProvider implements MailerProvider {
        private final DaemonMailerRegistry registry = new DaemonMailerRegistry(4, Duration.ofMinutes(1));
        private final List<Mailer> mailers = new ArrayList<>();

        @Override
        public Lease acquire(final CliMailerProfile profile, final Supplier<Mailer> factory) {
            final Lease lease = registry.acquire(profile, factory);
            mailers.add(lease.mailer());
            return lease;
        }

        @Override
        public void close() {
            registry.close();
        }
    }
}
