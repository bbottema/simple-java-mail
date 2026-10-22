package org.simplejavamail.springsupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailSendTelemetry;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.MailSendTelemetryConfig;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.OpenTelemetryModule;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.core.env.MapPropertySource;
import testutil.TestMailSendTelemetry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class SimpleJavaMailTelemetryActivationTest {

    private final TestMailSendTelemetry global = new TestMailSendTelemetry();
    private MockedStatic<ModuleLoader> moduleLoader;

    @BeforeEach
    void provideFrameworkNeutralTelemetryModule() {
        final OpenTelemetryModule module = (configuration, endpoints) ->
                (configuration == null ? global : (TestMailSendTelemetry) configuration).bind(endpoints);
        moduleLoader = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS);
        moduleLoader.when(ModuleLoader::loadOpenTelemetryModule).thenReturn(module);
        moduleLoader.clearInvocations();
    }

    @AfterEach
    void removeModuleFixture() {
        moduleLoader.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void annotationEnablesTheDefaultAndFreshFactoryMailers(final boolean boot) {
        runner(boot).withUserConfiguration(Enabled.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SimpleJavaMailConfig.class).get(ConfigKeys.TELEMETRY_ENABLED)).isTrue();
            assertThat(context.getBean(SimpleJavaMailConfig.class).getPropertySource(ConfigKeys.TELEMETRY_ENABLED))
                    .isEqualTo("@EnableSjmTelemetry");
            assertThat(context.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
            try (Mailer fresh = context.getBean(SimpleJavaMail.class).mailerBuilder().buildMailer()) {
                assertThat(fresh.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
            }
            assertThat(global.bindings).hasValue(2);
            try (Mailer disabled = context.getBean(SimpleJavaMail.class).mailerBuilder()
                    .withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(false).build()).buildMailer()) {
                assertThat(disabled.getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
                assertThat(global.bindings).hasValue(2);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void annotationUsesTheSelectedBeanForTheDefaultMailer(final boolean boot) {
        final TestMailSendTelemetry selected = new TestMailSendTelemetry();
        runner(boot).withUserConfiguration(Enabled.class)
                .withBean(MailSendTelemetry.class, () -> selected).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
                    assertThat(selected.bindings).hasValue(1);
                    final SimpleJavaMail mail = context.getBean(SimpleJavaMail.class);
                    try (Mailer fresh = mail.mailerBuilder().buildMailer()) {
                        assertThat(fresh.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
                        assertThat(fresh.getOperationalConfig().getTelemetryConfig().getIntegration()).isNull();
                    }
                    assertThat(global.bindings).hasValue(1);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aBeanAloneDoesNotEnableTelemetryAndPropertyActivationStillUsesIt(final boolean boot) {
        final TestMailSendTelemetry selected = new TestMailSendTelemetry();
        runner(boot).withBean(MailSendTelemetry.class, () -> selected).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
            assertThat(selected.bindings).hasValue(0);
            moduleLoader.verify(ModuleLoader::loadOpenTelemetryModule, never());
        });
        runner(boot).withPropertyValues("simplejavamail.telemetry.enabled=true")
                .withBean(MailSendTelemetry.class, () -> selected).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
                    assertThat(selected.bindings).hasValue(1);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitFalseOverridesTheAnnotationWithoutLoadingTelemetry(final boolean boot) {
        runner(boot).withUserConfiguration(Enabled.class).withPropertyValues("simplejavamail.telemetry.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final SimpleJavaMailConfig config = context.getBean(SimpleJavaMailConfig.class);
                    assertThat(config.get(ConfigKeys.TELEMETRY_ENABLED)).isFalse();
                    assertThat(config.getPropertySource(ConfigKeys.TELEMETRY_ENABLED)).isNotEqualTo("@EnableSjmTelemetry");
                    assertThat(context.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
                    assertThat(global.bindings).hasValue(0);
                    moduleLoader.verify(ModuleLoader::loadOpenTelemetryModule, never());
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activationLocksRemainAuthoritativeAndConstrainEveryFactoryMailer(final boolean boot) {
        for (boolean locked : new boolean[] {false, true}) {
            runner(boot).withUserConfiguration(Enabled.class).withPropertyValues(
                    "simplejavamail.telemetry.enabled=" + !locked,
                    "simplejavamail.locked.telemetry.enabled=" + locked).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isEqualTo(locked);
                final SimpleJavaMail factory = context.getBean(SimpleJavaMail.class);
                try (Mailer equal = factory.mailerBuilder()
                        .withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(locked).build()).buildMailer()) {
                    assertThat(equal.getOperationalConfig().getTelemetryConfig().getEnabled()).isEqualTo(locked);
                }
                assertThatThrownBy(() -> factory.mailerBuilder()
                        .withTelemetryConfig(MailSendTelemetryConfig.builder().withEnabled(!locked).build()).buildMailer())
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simplejavamail.locked.telemetry.enabled");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitSelectionIsStillRequiredForAmbiguousIntegrationBeans(final boolean boot) {
        final TestMailSendTelemetry first = new TestMailSendTelemetry();
        final TestMailSendTelemetry second = new TestMailSendTelemetry();
        runner(boot).withUserConfiguration(Enabled.class)
                .withBean("first", MailSendTelemetry.class, () -> first)
                .withBean("second", MailSendTelemetry.class, () -> second).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class);
                    assertThat(first.bindings).hasValue(0);
                    assertThat(second.bindings).hasValue(0);
                });
        runner(boot).withUserConfiguration(Enabled.class)
                .withBean("first", MailSendTelemetry.class, () -> first)
                .withBean("second", MailSendTelemetry.class, () -> second, definition -> definition.setPrimary(true)).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(first.bindings).hasValue(0);
                    assertThat(second.bindings).hasValue(1);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void annotationRemainsContextLocalAndCapturedConfigurationIsImmutable(final boolean boot) {
        runner(boot).withUserConfiguration(Enabled.class).run(enabled -> {
            final SimpleJavaMail factory = enabled.getBean(SimpleJavaMail.class);
            enabled.getEnvironment().getPropertySources().addFirst(new MapPropertySource("later change",
                    Map.of("simplejavamail.telemetry.enabled", false)));
            runner(boot).run(disabled -> {
                assertThat(disabled).hasNotFailed();
                assertThat(disabled.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
                assertThat(disabled.getBean(SimpleJavaMailConfig.class).get(ConfigKeys.TELEMETRY_ENABLED)).isNull();
                try (Mailer captured = factory.mailerBuilder().buildMailer()) {
                    assertThat(captured.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
                }
            });
        });
    }

    @Test
    void annotationDoesNotImportTheBaseIntegration() {
        new ApplicationContextRunner().withUserConfiguration(Enabled.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(SimpleJavaMailConfig.class)
                    .doesNotHaveBean(SimpleJavaMail.class).doesNotHaveBean(Mailer.class);
        });
    }

    @Test
    void aChildLoadingItsOwnSnapshotDoesNotInheritTheParentsActivationAnnotation() {
        runner(false).withUserConfiguration(Enabled.class).run(parent -> {
            final SimpleJavaMail parentFactory = parent.getBean(SimpleJavaMail.class);
            runner(false).withParent(parent).run(child -> {
                assertThat(child).hasNotFailed();
                assertThat(child.getBean(SimpleJavaMail.class)).isNotSameAs(parentFactory);
                assertThat(child.getBean(SimpleJavaMailConfig.class).get(ConfigKeys.TELEMETRY_ENABLED)).isNull();
                assertThat(child.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
            });
            try (Mailer inherited = parentFactory.mailerBuilder().buildMailer()) {
                assertThat(inherited.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
            }
        });
    }

    @Test
    void bootKeepsTheActiveParentFactoryWhenItIntentionallyInheritsTheParentsBeans() {
        runner(true).withUserConfiguration(Enabled.class).run(parent -> runner(true).withParent(parent).run(child -> {
            assertThat(child).hasNotFailed();
            assertThat(child.getBean(SimpleJavaMailConfig.class)).isSameAs(parent.getBean(SimpleJavaMailConfig.class));
            assertThat(child.getBean(SimpleJavaMail.class)).isSameAs(parent.getBean(SimpleJavaMail.class));
            assertThat(child.getBean(Mailer.class)).isSameAs(parent.getBean(Mailer.class));
            assertThat(child.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
        }));
    }

    @Test
    void bootPreservesAnApplicationProvidedSnapshot() {
        final SimpleJavaMailConfig supplied = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "localhost", "simplejavamail.telemetry.enabled", false)).load();
        runner(true).withUserConfiguration(Enabled.class).withBean(SimpleJavaMailConfig.class, () -> supplied).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SimpleJavaMailConfig.class)).isSameAs(supplied);
            assertThat(context.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
            moduleLoader.verify(ModuleLoader::loadOpenTelemetryModule, never());
        });
    }

    @Test
    void bootPreservesAnApplicationProvidedFactory() {
        final SimpleJavaMail supplied = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.smtp.host", "localhost", "simplejavamail.telemetry.enabled", false)).load());
        runner(true).withUserConfiguration(Enabled.class).withBean(SimpleJavaMail.class, () -> supplied).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SimpleJavaMail.class)).isSameAs(supplied);
            assertThat(context.getBean(SimpleJavaMailConfig.class).get(ConfigKeys.TELEMETRY_ENABLED)).isTrue();
            assertThat(context.getBean(Mailer.class).getOperationalConfig().getTelemetryConfig().getEnabled()).isFalse();
            moduleLoader.verify(ModuleLoader::loadOpenTelemetryModule, never());
        });
    }

    @Test
    void bootPreservesAnApplicationProvidedMailerAndKeepsTheConfiguredFactory() {
        final Mailer supplied = mock(Mailer.class);
        runner(true).withUserConfiguration(Enabled.class).withBean(Mailer.class, () -> supplied).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(Mailer.class)).isSameAs(supplied);
            assertThat(global.bindings).hasValue(0);
            try (Mailer fresh = context.getBean(SimpleJavaMail.class).mailerBuilder().buildMailer()) {
                assertThat(fresh.getOperationalConfig().getTelemetryConfig().getEnabled()).isTrue();
                assertThat(global.bindings).hasValue(1);
            }
        });
    }

    private static ApplicationContextRunner runner(final boolean boot) {
        final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withPropertyValues("simplejavamail.smtp.host=localhost");
        return boot ? runner.withConfiguration(AutoConfigurations.of(SimpleJavaMailAutoConfiguration.class))
                : runner.withUserConfiguration(SimpleJavaMailSpringSupport.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSjmTelemetry
    static class Enabled {
    }
}
