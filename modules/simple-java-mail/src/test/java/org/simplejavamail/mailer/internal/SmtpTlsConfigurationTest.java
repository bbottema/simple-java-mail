package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;
import testutil.ConfigLoaderTestHelper;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_OAUTH2;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;

/** Checks construction-time configuration consistency without opening SMTP connections. */
class SmtpTlsConfigurationTest {

    private static final String REQUIRED = "mail.smtp.starttls.required";

    @Test
    void rejectsDisablingOverrideFromSingleProperty() {
        assertThatThrownBy(() -> {
            try (Mailer ignored = builder(SMTP_TLS).withProperty(REQUIRED, false).buildMailer()) {
                // Close an unexpectedly accepted Mailer too, so the regression does not leak resources.
            }
        }).isInstanceOf(MailerException.class)
                .hasMessage("SMTP_TLS requires STARTTLS, but mail.smtp.starttls.required disables it. "
                        + "Remove that override to require TLS, or choose TransportStrategy.SMTP if you want opportunistic TLS.");
    }

    @ParameterizedTest
    @MethodSource("disabledOverrides")
    void rejectsEveryValueThatDisablesMandatoryStartTls(final TransportStrategy strategy, final Object value) {
        final Properties properties = new Properties();
        properties.put(REQUIRED, value);
        assertThatThrownBy(() -> builder(strategy).withProperties(properties).buildMailer())
                .isInstanceOf(MailerException.class)
                .hasMessageStartingWith(strategy + " requires STARTTLS, but " + REQUIRED + " disables it.");
        assertThat(properties.get(REQUIRED)).isSameAs(value);
    }

    static Stream<Arguments> disabledOverrides() {
        return Stream.of(SMTP_TLS, SMTP_OAUTH2).flatMap(strategy ->
                Stream.of("false", "FaLsE", "", " true ", "yes", "1", "unrecognized", Boolean.FALSE, 1)
                        .map(value -> Arguments.of(strategy, value)));
    }

    @ParameterizedTest
    @MethodSource("enabledOverrides")
    void acceptsRedundantTrueWithoutRewritingIt(final TransportStrategy strategy, final Object value) throws Exception {
        final Properties properties = new Properties();
        properties.put(REQUIRED, value);
        try (Mailer mailer = builder(strategy).withProperties(properties).buildMailer()) {
            assertThat(mailer.getSession().getProperties().get(REQUIRED)).isSameAs(value);
            assertThat(mailer.getTransportStrategy()).isEqualTo(strategy);
        }
    }

    static Stream<Arguments> enabledOverrides() {
        return Stream.of(SMTP_TLS, SMTP_OAUTH2).flatMap(strategy ->
                Stream.of("true", "TrUe", Boolean.TRUE).map(value -> Arguments.of(strategy, value)));
    }

    @Test
    void doesNotRenderAnArbitraryPropertyValueInTheError() {
        final Object secretValue = new Object() {
            @Override public String toString() { throw new AssertionError("Do not render raw property values"); }
        };
        final Properties properties = new Properties();
        properties.put(REQUIRED, secretValue);
        assertThatThrownBy(() -> builder(SMTP_TLS).withProperties(properties).buildMailer())
                .isInstanceOf(MailerException.class).hasMessageContaining(REQUIRED);
    }

    @Test
    void mapPropertiesAndBuilderReplacementUseTheFinalValue() throws Exception {
        final MailerRegularBuilder<?> builder = builder(SMTP_TLS).withProperties(Map.of(REQUIRED, "false"));
        assertThatThrownBy(builder::buildMailer).isInstanceOf(MailerException.class);
        try (Mailer mailer = builder.withProperty(REQUIRED, true).buildMailer()) {
            assertThat(mailer.getSession().getProperty(REQUIRED)).isEqualTo("true");
        }
    }

    @Test
    void configurationPrecedenceAndBuilderOverridesDoNotChangeTheSnapshot() throws Exception {
        final String key = "simplejavamail.extraproperties." + REQUIRED;
        final ConfigLoader loader = ConfigLoader.builder().withMap("base", Map.of(
                "simplejavamail.smtp.host", "localhost", "simplejavamail.transportstrategy", "SMTP_TLS", key, "false"));
        final SimpleJavaMailConfig rejected = loader.load();
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(rejected);
        assertThatThrownBy(() -> factory.mailerBuilder().buildMailer()).isInstanceOf(MailerException.class);

        final SimpleJavaMailConfig corrected = loader.withMap("override", Map.of(key, "TrUe")).load();
        try (Mailer fromSource = SimpleJavaMail.withConfig(corrected).mailerBuilder().buildMailer();
             Mailer fromBuilder = factory.mailerBuilder().withProperty(REQUIRED, true).buildMailer()) {
            assertThat(fromSource.getSession().getProperty(REQUIRED)).isEqualTo("TrUe");
            assertThat(fromBuilder.getSession().getProperty(REQUIRED)).isEqualTo("true");
            assertThat(corrected.getDiagnostics().toString()).contains(key + " = TrUe (source: override)");
            assertThat(rejected.<Map<String, String>>getProperty(ConfigLoader.Property.EXTRA_PROPERTIES)).containsEntry(REQUIRED, "false");
        }
        assertThatThrownBy(() -> factory.mailerBuilder().buildMailer()).isInstanceOf(MailerException.class);
    }

    @Test
    void removingAnOverrideRestoresTheStrategyDefault() throws Exception {
        try (Mailer mailer = builder(SMTP_TLS).withProperty(REQUIRED, false).withProperty(REQUIRED, null).buildMailer()) {
            assertThat(mailer.getSession().getProperty(REQUIRED)).isEqualTo("true");
        }
    }

    @Test
    void inheritedPropertiesDefaultsAreNotCopiedOrRejected() throws Exception {
        final Properties defaults = new Properties();
        defaults.setProperty(REQUIRED, "false");
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.smtp.timeout", "1000");
        try (Mailer mailer = builder(SMTP_TLS).withProperties(properties).buildMailer()) {
            assertThat(mailer.getSession().getProperty(REQUIRED)).isEqualTo("true");
            assertThat(properties.getProperty(REQUIRED)).isEqualTo("false");
            assertThat(properties).doesNotContainKey(REQUIRED);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsContradictionWithoutCredentialsAndInLoggingOnlyMode(final boolean loggingOnly) {
        assertThatThrownBy(() -> builder(SMTP_TLS).withSMTPServerPassword(null)
                .withTransportModeLoggingOnly(loggingOnly).withProperty(REQUIRED, false).buildMailer())
                .isInstanceOf(MailerException.class).hasMessageContaining("SMTP_TLS requires STARTTLS");
    }

    @Test
    void loggingOnlyWithoutAnSmtpHostStillValidatesTheStrategy() {
        assertThatThrownBy(() -> factory().mailerBuilder().withTransportModeLoggingOnly(true)
                .withTransportStrategy(SMTP_TLS).withProperty(REQUIRED, false).buildMailer())
                .isInstanceOf(MailerException.class).hasMessageContaining("SMTP_TLS requires STARTTLS");
    }

    @Test
    void rejectsBeforeRuntimeOwnersProxyPoolTokenProviderOrObserverAreUsed() {
        final AtomicInteger tokenCalls = new AtomicInteger();
        final AtomicInteger observerCalls = new AtomicInteger();
        final ExecutorService executor = mock(ExecutorService.class);
        final MailerRegularBuilder<?> builder = builder(SMTP_OAUTH2).withSMTPServerPassword(null)
                .withOAuth2AccessTokenProvider(() -> { tokenCalls.incrementAndGet(); return "fake-token"; })
                .withMailSendObserver(outcome -> observerCalls.incrementAndGet())
                .withExecutorService(executor).withProxy("localhost", 12346, "proxy-user", "fake-proxy-password")
                .withProperty(REQUIRED, false);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
             MockedStatic<MailTransportLifecycleResolver> lifecycle = mockStatic(MailTransportLifecycleResolver.class);
             MockedConstruction<MailSendOperations> operations = mockConstruction(MailSendOperations.class)) {
            assertThatThrownBy(builder::buildMailer).isInstanceOf(MailerException.class)
                    .hasMessage("SMTP_OAUTH2 requires STARTTLS, but mail.smtp.starttls.required disables it. "
                            + "Remove that override so the access token is sent only after TLS succeeds.");
            modules.verifyNoInteractions();
            lifecycle.verifyNoInteractions();
            assertThat(operations.constructed()).isEmpty();
            assertThat(tokenCalls).hasValue(0);
            assertThat(observerCalls).hasValue(0);
            verifyNoInteractions(executor);
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP", "SMTPS"})
    void leavesOtherStrategiesAndTheirRawPropertiesAlone(final TransportStrategy strategy) throws Exception {
        try (Mailer mailer = builder(strategy).withProperty(REQUIRED, false)
                .withProperty("mail.smtps.ssl.enable", false).buildMailer()) {
            assertThat(mailer.getSession().getProperty(REQUIRED)).isEqualTo("false");
            assertThat(mailer.getTransportStrategy()).isEqualTo(strategy);
        }
    }

    @Test
    void allowsStrengtheningSmtpAndKeepsTrustExceptionsIndependent() throws Exception {
        try (Mailer mailer = builder(SMTP).withProperty(REQUIRED, true).buildMailer()) {
            assertThat(mailer.getSession().getProperty(REQUIRED)).isEqualTo("true");
        }
        try (Mailer mailer = builder(SMTP_TLS).trustingAllHosts(true).verifyingServerIdentity(false).buildMailer()) {
            assertThat(mailer.getSession().getProperties()).containsEntry(REQUIRED, "true")
                    .containsEntry("mail.smtp.ssl.trust", "*").containsEntry("mail.smtp.ssl.checkserveridentity", "false");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerOwnedSessionRemainsExemptWithOrWithoutAStrategyMarker(final boolean marked) throws Exception {
        final Properties properties = marked ? SMTP_TLS.generateProperties() : new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "localhost");
        properties.setProperty(REQUIRED, "false");
        final Session session = Session.getInstance(properties);
        try (Mailer mailer = factory().mailerBuilder(session).withConnectionPoolCoreSize(0)
                .withProperty(REQUIRED, false).buildMailer()) {
            assertThat(mailer.getSession()).isSameAs(session);
            assertThat(session.getProperty(REQUIRED)).isEqualTo("false");
        }
    }

    @ParameterizedTest
    @EnumSource(value = TransportStrategy.class, names = {"SMTP_TLS", "SMTP_OAUTH2"})
    void customMailerRemainsExempt(final TransportStrategy strategy) throws Exception {
        final CustomMailer custom = mock(CustomMailer.class);
        final MailerRegularBuilder<?> builder = factory().mailerBuilder().withTransportStrategy(strategy).withCustomMailer(custom);
        if (strategy == SMTP_OAUTH2) {
            builder.withOAuth2AccessTokenProvider(() -> { throw new AssertionError("No transport should request a token"); });
        }
        try (Mailer mailer = builder.withProperty(REQUIRED, false).buildMailer()) {
            assertThat(mailer.getSession().getProperty(REQUIRED)).isEqualTo("false");
            verifyNoInteractions(custom);
        }
    }

    private static MailerRegularBuilder<?> builder(final TransportStrategy strategy) {
        return factory().mailerBuilder().withSMTPServer("localhost", 12345, "test-user", "fake-password-or-token")
                .withTransportStrategy(strategy).withConnectionPoolCoreSize(0);
    }

    private static SimpleJavaMail factory() {
        return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    }
}
