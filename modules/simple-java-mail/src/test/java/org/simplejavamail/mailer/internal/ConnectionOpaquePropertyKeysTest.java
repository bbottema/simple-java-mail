package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class ConnectionOpaquePropertyKeysTest {
    enum InputRoute { OWNED_PROPERTIES, SUPPLIED_SESSION, SUPPLIED_SESSION_OVERLAY }

    @ParameterizedTest
    @EnumSource(InputRoute.class)
    void opaqueKeysAndValuesRemainUninterpretedAcrossInputRoutes(final InputRoute route) throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
            for (boolean throwingKey : new boolean[] {false, true}) {
                final AtomicInteger renders = new AtomicInteger();
                final Object key = metadataKey(renders, throwingKey);
                final Object value = new Object() {
                    @Override public String toString() { throw new AssertionError("Metadata value rendered"); }
                };
                final Properties extras = new Properties();
                extras.put(key, value);
                final Properties supplied = new Properties();
                supplied.setProperty("mail.smtp.host", "relay.example.org");
                if (route == InputRoute.SUPPLIED_SESSION) { supplied.putAll(extras); }
                final Session session = Session.getInstance(supplied);
                try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
                    modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
                    final MailerGenericBuilder<?> builder = route == InputRoute.OWNED_PROPERTIES
                            ? mail.mailerBuilder().withSMTPServerHost("relay.example.org") : mail.mailerBuilder(session);
                    if (route != InputRoute.SUPPLIED_SESSION) { builder.withProperties(extras); }
                    try (Mailer built = builder.withConnectionConflictPolicy(policy).buildMailer()) {
                        assertThat(built.getSession().getProperty("mail.smtp.host")).isEqualTo("relay.example.org");
                        assertThat(built.getSession().getProperties().get(key) == value).isTrue();
                        if (route != InputRoute.SUPPLIED_SESSION) {
                            assertThat(built.getOperationalConfig().getProperties().get(key) == value).isTrue();
                        }
                    }
                }
                assertThat(renders).hasValue(0);
            }
        }
    }

    @Test
    void clearingMetadataDoesNotRemoveAnUnrelatedImportedHost() throws Exception {
        final var config = ConfigLoader.builder().withMap(java.util.Map.of("simplejavamail.smtp.host", "relay.example.org")).load();
        final AtomicInteger renders = new AtomicInteger();
        final Object key = metadataKey(renders, false);
        final Properties extras = new Properties();
        extras.put(key, "metadata");
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer built = SimpleJavaMail.withConfig(config).mailerBuilder().withProperties(extras).clearProperties().buildMailer()) {
                assertThat(built.getSession().getProperty("mail.smtp.host")).isEqualTo("relay.example.org");
                assertThat(built.getSession().getProperties().containsKey(key)).isFalse();
            }
        }
        assertThat(renders).hasValue(0);
    }

    @Test
    void clearingAnUnrenderableMetadataKeyRemainsOrdinaryLocalEditing() throws Exception {
        final AtomicInteger renders = new AtomicInteger();
        final Properties extras = new Properties();
        extras.put(metadataKey(renders, true), "metadata");
        final var builder = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder()
                .withSMTPServerHost("relay.example.org").withProperties(extras);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer built = builder.clearProperties().buildMailer()) {
                assertThat(built.getOperationalConfig().getProperties()).isEmpty();
            }
        }
        assertThat(renders).hasValue(0);
    }

    @Test
    void aMetadataKeyCannotSupplyAMissingSmtpHost() {
        final AtomicInteger renders = new AtomicInteger();
        final Properties extras = new Properties();
        extras.put(metadataKey(renders, false), "phantom.example.org");
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            assertThatThrownBy(() -> {
                try (Mailer ignored = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder().withProperties(extras).buildMailer()) {
                    // An unexpectedly accepted phantom host must still retire the test's resources.
                }
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SMTP server host missing");
        }
        assertThat(renders).hasValue(0);
    }

    private static Object metadataKey(final AtomicInteger renders, final boolean throwing) {
        return new Object() {
            @Override public String toString() {
                renders.incrementAndGet();
                if (throwing) { throw new AssertionError("Metadata key rendered"); }
                return "mail.smtp.host";
            }
        };
    }
}
