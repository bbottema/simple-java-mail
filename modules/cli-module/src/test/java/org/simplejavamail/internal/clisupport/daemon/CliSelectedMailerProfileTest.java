package org.simplejavamail.internal.clisupport.daemon;

import com.sun.nio.file.ExtendedOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.internal.clisupport.CliFailoverMailerBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.clisupport.CliExecutionEnvironment;
import org.simplejavamail.internal.clisupport.CliExecutionResult;
import org.simplejavamail.internal.clisupport.CliMailerProfile;
import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.MailerProvider;
import org.simplejavamail.internal.clisupport.OneShotMailerProvider;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CliSelectedMailerProfileTest {
    @TempDir Path directory;

    @Test
    void unusedKeyContentsDoNotDisplaceARetainedMailer() throws Exception {
        final Path key = Files.writeString(directory.resolve("unused.pem"), "first key");
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.smtp.host", "primary.example.org");
        namedKey(properties, "unused", key.getFileName().toString());
        final SimpleJavaMailConfig configuration = config(properties);
        final CliMailerProfile first = profile(configuration);
        final AtomicInteger constructions = new AtomicInteger();
        final Mailer mailer = mock(Mailer.class);
        try (DaemonMailerRegistry registry = new DaemonMailerRegistry(2, Duration.ofMinutes(1))) {
            try (MailerProvider.Lease ignored = registry.acquire(first, () -> {
                constructions.incrementAndGet();
                return mailer;
            })) {
                Files.writeString(key, "replacement key");
            }
            final CliMailerProfile later = profile(configuration);
            assertThat(later).isEqualTo(first);
            try (MailerProvider.Lease lease = registry.acquire(later, () -> {
                constructions.incrementAndGet();
                return mock(Mailer.class);
            })) {
                assertThat(lease.mailer()).isSameAs(mailer);
            }
            assertThat(constructions).hasValue(1);
        }
    }

    @Test
    void selectedTransitiveKeyUsesTheRootRegistryAndRetainsContentAndLockIdentity() throws Exception {
        final Path key = Files.writeString(directory.resolve("last.pem"), "first key");
        final Properties properties = route("first");
        properties.setProperty("simplejavamail.mailers.first.failover.mailer", "last");
        namedKey(properties, "last", key.getFileName().toString());
        final SimpleJavaMailConfig original = config(properties);
        final CliMailerProfile first = profile(original);
        Files.writeString(key, "replacement key");
        assertThat(profile(original)).isNotEqualTo(first);
        Files.writeString(key, "first key");
        assertThat(profile(original)).isEqualTo(first);
        properties.setProperty("simplejavamail.locked.mailers.last.smtp.host", "last.example.org");
        assertThat(profile(config(properties))).isNotEqualTo(first);
    }

    @Test
    void lastExplicitSelectionReplacesConfiguredAndEarlierCliRoutes() throws Exception {
        final Path unused = Files.writeString(directory.resolve("unused.pem"), "unused");
        final Path earlier = Files.writeString(directory.resolve("earlier.pem"), "earlier");
        final Path selected = Files.writeString(directory.resolve("selected.pem"), "selected");
        final Properties properties = route("unused");
        namedKey(properties, "unused", unused.getFileName().toString());
        namedKey(properties, "earlier", earlier.getFileName().toString());
        namedKey(properties, "selected", selected.getFileName().toString());
        final SimpleJavaMailConfig configuration = config(properties);
        final List<CliReceivedOptionData> options = List.of(select("earlier"), select("selected"));
        final CliMailerProfile first = profile(configuration, options);
        Files.writeString(unused, "unused changed");
        Files.writeString(earlier, "earlier changed");
        assertThat(profile(configuration, options)).isEqualTo(first);
        Files.writeString(selected, "selected changed");
        assertThat(profile(configuration, options)).isNotEqualTo(first);
        assertThat(profile(configuration, List.of(select("selected"), select("earlier")))).isNotEqualTo(first);
    }

    @Test
    void relativeSelectedKeysUseConfigurationDirectoryInsteadOfRequestDirectory() throws Exception {
        final Properties properties = route("selected");
        namedKey(properties, "selected", "key.pem");
        final SimpleJavaMailConfig configuration = config(properties);
        final Path other = Files.createDirectory(directory.resolve("other"));
        Files.writeString(directory.resolve("key.pem"), "configuration key");
        Files.writeString(other.resolve("key.pem"), "other key");
        assertThat(profile(configuration)).isNotEqualTo(CliMailerProfile.create(configuration, List.of(), new byte[32], other));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void unreadableUnusedKeyIsIgnoredButSelectedKeyStillFails() throws Exception {
        final Path key = Files.writeString(directory.resolve("locked.pem"), "key");
        final Properties properties = new Properties();
        namedKey(properties, "locked", key.getFileName().toString());
        try (FileChannel ignored = FileChannel.open(key, StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_READ)) {
            assertThat(Files.isRegularFile(key)).isTrue();
            assertThatThrownBy(() -> Files.newInputStream(key)).isInstanceOf(IOException.class);
            assertThatCode(() -> profile(config(properties))).doesNotThrowAnyException();
            properties.setProperty("simplejavamail.failover.mailer", "locked");
            assertThatThrownBy(() -> profile(config(properties)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Unable to derive Mailer identity");
        }
    }

    @Test
    void unknownAndCyclicSelectionsKeepTheOrdinaryBuilderErrors() {
        final Properties missing = route("missing");
        final Properties cyclic = route("first");
        cyclic.setProperty("simplejavamail.mailers.first.smtp.host", "first.example.org");
        cyclic.setProperty("simplejavamail.mailers.first.failover.mailer", "last");
        cyclic.setProperty("simplejavamail.mailers.last.smtp.host", "last.example.org");
        cyclic.setProperty("simplejavamail.mailers.last.failover.mailer", "first");
        for (final boolean daemon : List.of(false, true)) {
            assertValidationFails(missing, daemon, "The failover Mailer has no definition");
            assertValidationFails(cyclic, daemon, "The named failover Mailers form a cycle");
        }
    }

    @Test
    void cliSelectionStillEnforcesALockedConfiguredDestination() {
        final Properties properties = route("first");
        properties.setProperty("simplejavamail.locked.failover.mailer", "first");
        properties.setProperty("simplejavamail.mailers.first.smtp.host", "first.example.org");
        properties.setProperty("simplejavamail.mailers.other.smtp.host", "other.example.org");
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config(properties), new OneShotMailerProvider(), directory)) {
            final CliExecutionResult result = CliSupport.execute(new String[] {"validate", "--email:startingBlank",
                    "--email:from", "sender@example.org", "--email:to", "recipient@example.org",
                    "--mailer:withFailoverMailerName", "other"}, directory, UUID.randomUUID(), environment, null);
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains("simplejavamail.locked.failover.mailer");
        }
    }

    private void assertValidationFails(final Properties properties, final boolean daemon, final String message) {
        final MailerProvider provider = daemon ? new DaemonMailerRegistry(2, Duration.ofMinutes(1)) : new OneShotMailerProvider();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(config(properties), provider, directory)) {
            final CliExecutionResult result = CliSupport.execute(new String[] {"validate", "--email:startingBlank",
                    "--email:from", "sender@example.org", "--email:to", "recipient@example.org"}, directory,
                    UUID.randomUUID(), environment, daemon ? new byte[32] : null);
            assertThat(result.exitCode()).isNotZero();
            assertThat(result.stderr()).contains(message);
        }
    }

    private CliMailerProfile profile(final SimpleJavaMailConfig configuration, final CliReceivedOptionData... options) {
        return profile(configuration, List.of(options));
    }

    private CliMailerProfile profile(final SimpleJavaMailConfig configuration, final List<CliReceivedOptionData> options) {
        return CliMailerProfile.create(configuration, options, new byte[32], directory);
    }

    private static CliReceivedOptionData select(final String name) throws Exception {
        return new CliReceivedOptionData(new CliDeclaredOptionSpec("--mailer:withFailoverMailerName", List.of(), List.of(),
                CliBuilderApiType.MAILER, CliFailoverMailerBuilder.class.getMethod("withFailoverMailerName", String.class)), List.of(name));
    }

    private static Properties route(final String name) {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.smtp.host", "primary.example.org");
        properties.setProperty("simplejavamail.failover.mailer", name);
        return properties;
    }

    private static void namedKey(final Properties properties, final String name, final String file) {
        properties.setProperty("simplejavamail.mailers." + name + ".smtp.host", name + ".example.org");
        properties.setProperty("simplejavamail.mailers." + name + ".dkim.signing.private_key_file_or_data", file);
    }

    private static SimpleJavaMailConfig config(final Properties properties) {
        return ConfigLoader.builder().withProperties(properties).load();
    }
}
