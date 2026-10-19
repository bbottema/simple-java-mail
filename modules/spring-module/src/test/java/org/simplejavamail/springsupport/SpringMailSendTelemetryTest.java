package org.simplejavamail.springsupport;

import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.OpenTelemetryModule;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import testutil.TestMailSendTelemetry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

class SpringMailSendTelemetryTest {
    private final TestMailSendTelemetry global = new TestMailSendTelemetry();
    private MockedStatic<ModuleLoader> moduleLoader;

    @BeforeEach
    void provideFrameworkNeutralModuleFixture() {
        final OpenTelemetryModule module = (configuration, endpoints) ->
                (configuration == null ? global : (TestMailSendTelemetry) configuration).bind(endpoints);
        moduleLoader = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
        moduleLoader.when(ModuleLoader::loadOpenTelemetryModule).thenReturn(module);
    }

    @AfterEach
    void removeModuleFixture() {
        moduleLoader.close();
    }

    @Test
    void capturedEnvironmentEnablesEveryFactoryMailerAndSupportsAnExplicitDisable() throws Exception {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("telemetry properties", Map.of(
                "simplejavamail.smtp.host", "localhost", "simplejavamail.telemetry.enabled", true)));
        final SimpleJavaMailSpringBeanFactory support = new SimpleJavaMailSpringBeanFactory(environment);
        final SimpleJavaMail mail = support.createSimpleJavaMail(ConfigLoader.builder().withSource(new SpringEnvironmentConfigSource(environment)).load());
        try (Mailer first = mail.mailerBuilder().buildMailer(); Mailer second = mail.mailerBuilder().buildMailer();
             Mailer disabled = mail.mailerBuilder().withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(false).build()).buildMailer()) {
            assertThat(first.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
            assertThat(second.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
            assertThat(disabled.getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
            assertThat(global.bindings).hasValue(2);
        }
    }

    @Test
    void integrationBeanIsBorrowedAndRequiresIndependentActivation() throws Exception {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("telemetry test", Map.of(
                "simplejavamail.smtp.host", "localhost",
                "simplejavamail.telemetry.smtp.endpointattributes", true,
                "simplejavamail.transport.mode.logging.only", true)));
        final SimpleJavaMailSpringBeanFactory support = new SimpleJavaMailSpringBeanFactory(environment);
        // This module's shared classpath fixture enables S/MIME. Keep this activation test limited to its declared Environment inputs.
        final SimpleJavaMail mail = support.createSimpleJavaMail(ConfigLoader.builder().withSource(new SpringEnvironmentConfigSource(environment)).load());
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("arbitrarySdk", new Object());
        try (Mailer inactive = create(support, mail, beans)) {
            assertThat(inactive.getOperationalConfig().getTelemetryConfig().getSmtpEndpointAttributes()).isTrue();
        }
        final TestMailSendTelemetry registration = new TestMailSendTelemetry();
        register(beans, "mailTelemetry", registration, false);
        try (Mailer inactive = create(support, mail, beans)) {
            assertThat(inactive.getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
            assertThat(registration.bindings).hasValue(0);
        }
        final SimpleJavaMail enabled = support.createSimpleJavaMail(ConfigLoader.builder()
                .withSource(new SpringEnvironmentConfigSource(environment))
                .withMap(Map.of("simplejavamail.telemetry.enabled", true)).load());
        try (Mailer active = create(support, enabled, beans)) {
            active.sync().sendMail(mail.emailBuilder().startingBlank().from("sender@example.test").withRecipients(mail.recipientBuilder()
                    .withAddress("receiver@example.test").withType(jakarta.mail.Message.RecipientType.TO).build()).buildEmail());
            assertThat(registration.bindings).hasValue(1);
            assertThat(registration.reports).hasSize(1);
        }
        assertThat(registration.reports.get(0).isLoggingOnly()).isTrue();
    }

    @Test
    void ambiguousIntegrationsRequireSelectionAndPrimaryBeanWins() throws Exception {
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        final TestMailSendTelemetry first = new TestMailSendTelemetry();
        final TestMailSendTelemetry second = new TestMailSendTelemetry();
        register(beans, "first", first, false);
        register(beans, "second", second, false);
        final SimpleJavaMailSpringBeanFactory support = new SimpleJavaMailSpringBeanFactory(new StandardEnvironment());
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "localhost", "simplejavamail.telemetry.enabled", true)).load());
        assertThatThrownBy(() -> create(support, mail, beans)).isInstanceOf(NoUniqueBeanDefinitionException.class);
        final DefaultListableBeanFactory selected = new DefaultListableBeanFactory();
        register(selected, "first", first, false);
        register(selected, "second", second, true);
        try (Mailer active = create(support, mail, selected)) {
            assertThat(second.bindings).hasValue(1);
            assertThat(first.bindings).hasValue(0);
        }
    }

    private static Mailer create(final SimpleJavaMailSpringBeanFactory support, final SimpleJavaMail mail, final DefaultListableBeanFactory beans) {
        return support.createDefaultMailer(mail, beans.getBeanProvider(OAuth2AccessTokenProvider.class), beans.getBeanProvider(MailSendTelemetry.class));
    }

    private static void register(final DefaultListableBeanFactory beans, final String name, final TestMailSendTelemetry telemetry, final boolean primary) {
        final RootBeanDefinition definition = new RootBeanDefinition(MailSendTelemetry.class);
        definition.setInstanceSupplier(() -> telemetry);
        definition.setPrimary(primary);
        beans.registerBeanDefinition(name, definition);
    }
}
