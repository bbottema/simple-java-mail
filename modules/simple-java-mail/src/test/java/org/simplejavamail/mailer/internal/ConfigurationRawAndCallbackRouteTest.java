package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendProgressObserver;
import org.simplejavamail.api.mailer.MailSendResultObserver;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.ConfigKey;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Exact raw/callback overloads are invoked and their selected runtime values are asserted independently of catalogue labels. */
class ConfigurationRawAndCallbackRouteTest {
    enum RawRoute { PROPERTY, MAP, PROPERTIES, CLEAR }

    @ParameterizedTest
    @EnumSource(RawRoute.class)
    void everyRawInputRouteReachesTheOperationalAndSessionViews(final RawRoute route) throws Exception {
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.extraproperties.application.marker", "configured")).load();
        final MailerRegularBuilder<?> builder = SimpleJavaMail.withConfig(config).mailerBuilder().withTransportModeLoggingOnly(true);
        final AtomicInteger renders = new AtomicInteger();
        final Object opaque = new Object() {
            @Override public String toString() { throw new AssertionError("An opaque bulk property must not be rendered"); }
        };
        switch (route) {
            case PROPERTY:
                invoke(builder, method("withProperty", String.class, Object.class), "application.marker", new Object() {
                    @Override public String toString() { renders.incrementAndGet(); return "Java"; }
                });
                break;
            case MAP:
                invoke(builder, method("withProperties", Map.class), Map.of("application.marker", "Java"));
                break;
            case PROPERTIES:
                final Properties supplied = new Properties();
                supplied.put("application.marker", opaque);
                invoke(builder, method("withProperties", Properties.class), supplied);
                supplied.clear();
                break;
            case CLEAR:
                builder.withProperty("application.local", "local");
                invoke(builder, method("clearProperties"));
                break;
            default: throw new AssertionError(route);
        }
        try (Mailer mailer = builder.buildMailer()) {
            if (route == RawRoute.CLEAR) {
                assertThat(mailer.getOperationalConfig().getProperties()).doesNotContainKeys("application.marker", "application.local");
                assertThat(mailer.getSession().getProperties()).doesNotContainKeys("application.marker", "application.local");
            } else if (route == RawRoute.PROPERTIES) {
                assertThat(mailer.getOperationalConfig().getProperties().get("application.marker")).isSameAs(opaque);
                assertThat(mailer.getSession().getProperties().get("application.marker")).isSameAs(opaque);
            } else {
                assertThat(mailer.getOperationalConfig().getProperties().getProperty("application.marker")).isEqualTo("Java");
                assertThat(mailer.getSession().getProperty("application.marker")).isEqualTo("Java");
            }
            assertThat(renders).hasValue(route == RawRoute.PROPERTY ? 1 : 0);
        }
        record("field", "mailer.RAW_PROPERTIES");
        record("rule", "mailer.raw-pass-through");
        record("case", "mailer.raw-input-routes");
    }

    @Test
    void callbackChoicesRetainTheirIdentityUntilTheSelectedSendAndCanBeClearedIndependently() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final MailerRegularBuilder<?> builder = mail.mailerBuilder();
        final CustomMailer custom = mock(CustomMailer.class);
        final AtomicInteger sharedCalls = new AtomicInteger();
        final AtomicInteger generatedCalls = new AtomicInteger();
        final AtomicInteger factories = new AtomicInteger();
        final AtomicInteger identifiers = new AtomicInteger();
        final UUID expectedId = UUID.fromString("00000000-0000-0000-0000-000000000867");
        final MailSendHandlers shared = MailSendHandlers.builder().withResultObserver(outcome -> {
            assertThat(outcome.getObservationId()).isEqualTo(expectedId);
            sharedCalls.incrementAndGet();
        }).build();
        final Function<UUID, MailSendHandlers> generated = id -> {
            assertThat(id).isEqualTo(expectedId);
            factories.incrementAndGet();
            return MailSendHandlers.builder().withResultObserver(outcome -> generatedCalls.incrementAndGet()).build();
        };
        final Supplier<UUID> identifier = () -> { identifiers.incrementAndGet(); return expectedId; };
        invoke(builder, method("withCustomMailer", CustomMailer.class), custom);
        invoke(builder, method("withSendHandlers", MailSendHandlers.class), shared);
        invoke(builder, method("withSendHandlersFactory", Function.class), generated);
        invoke(builder, method("withSendIdGenerator", Supplier.class), identifier);
        assertThat(builder.getCustomMailer()).isSameAs(custom);
        assertThat(builder.getSendHandlers()).isSameAs(shared);
        assertThat(builder.getSendHandlersFactory()).isSameAs(generated);
        assertThat(builder.getSendIdGenerator()).isSameAs(identifier);
        final Email email = mail.emailBuilder().startingBlank().from("sender@example.test").to("receiver@example.test")
                .withSubject("callback source").withPlainText("body").buildEmail();
        try (Mailer selected = builder.buildMailer()) {
            verifyNoInteractions(custom);
            assertThat(factories).hasValue(0);
            assertThat(identifiers).hasValue(0);
            assertThat(selected.getOperationalConfig().getCustomMailer()).isSameAs(custom);
            selected.sync().sendMail(email);
            verify(custom, times(1)).sendMessage(any(), any(), any(), any());
            assertThat(sharedCalls).hasValue(1);
            assertThat(generatedCalls).hasValue(1);
            assertThat(factories).hasValue(1);
            assertThat(identifiers).hasValue(1);
        }
        invoke(builder, method("clearCustomMailer"));
        invoke(builder, method("clearSendHandlers"));
        invoke(builder, method("clearSendHandlersFactory"));
        invoke(builder, method("resetSendIdGenerator"));
        assertThat(builder.getCustomMailer()).isNull();
        assertThat(builder.getSendHandlers()).isNull();
        assertThat(builder.getSendHandlersFactory()).isNull();
        assertThat(builder.getSendIdGenerator()).isNotSameAs(identifier);
        try (Mailer cleared = builder.withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(cleared.getOperationalConfig().getCustomMailer()).isNull();
            cleared.sync().sendMail(email);
            assertThat(sharedCalls).hasValue(1);
            assertThat(generatedCalls).hasValue(1);
            assertThat(factories).hasValue(1);
            assertThat(identifiers).hasValue(1);
        }
        for (String field : new String[] {"CUSTOM_MAILER", "SEND_HANDLERS", "SEND_HANDLERS_FACTORY", "SEND_ID_GENERATOR"}) {
            record("field", "mailer." + field);
        }
        record("rule", "mailer.callback-identity");
        record("case", "mailer.callback-input-routes");
    }

    @Test
    void everyHandlerBuilderRoutePreservesBorrowedIdentityAndItsIndependentDeliveryPair() throws Exception {
        final MailSendResultObserver result = mock(MailSendResultObserver.class);
        final MailSendResultObserver replacementResult = mock(MailSendResultObserver.class);
        final MailSendProgressObserver progress = mock(MailSendProgressObserver.class);
        final MailSendProgressObserver replacementProgress = mock(MailSendProgressObserver.class);
        final Executor resultExecutor = mock(Executor.class);
        final Executor progressExecutor = mock(Executor.class);
        final MailSendHandlers.Builder builder = MailSendHandlers.builder();
        invoke(builder, handlerMethod("withResultObserver", MailSendResultObserver.class, Executor.class), result, resultExecutor);
        invoke(builder, handlerMethod("withProgressObserver", MailSendProgressObserver.class, Executor.class), progress, progressExecutor);
        final MailSendHandlers queued = builder.build();
        assertThat(queued.getResultObserver()).isSameAs(result);
        assertThat(queued.getResultObserverExecutor()).isSameAs(resultExecutor);
        assertThat(queued.getProgressObserver()).isSameAs(progress);
        assertThat(queued.getProgressObserverExecutor()).isSameAs(progressExecutor);

        invoke(builder, handlerMethod("withResultObserver", MailSendResultObserver.class), replacementResult);
        assertThat(builder.getResultObserver()).isSameAs(replacementResult);
        assertThat(builder.getResultObserverExecutor()).isNull();
        assertThat(builder.getProgressObserver()).isSameAs(progress);
        assertThat(builder.getProgressObserverExecutor()).isSameAs(progressExecutor);
        invoke(builder, handlerMethod("withProgressObserver", MailSendProgressObserver.class), replacementProgress);
        final MailSendHandlers inline = builder.build();
        assertThat(inline.getResultObserver()).isSameAs(replacementResult);
        assertThat(inline.getResultObserverExecutor()).isNull();
        assertThat(inline.getProgressObserver()).isSameAs(replacementProgress);
        assertThat(inline.getProgressObserverExecutor()).isNull();

        invoke(builder, handlerMethod("withResultObserver", MailSendResultObserver.class, Executor.class), result, resultExecutor);
        invoke(builder, handlerMethod("clearResultObserver"));
        assertThat(builder.getResultObserver()).isNull();
        assertThat(builder.getResultObserverExecutor()).isNull();
        assertThat(builder.getProgressObserver()).isSameAs(replacementProgress);
        assertThat(builder.getProgressObserverExecutor()).isNull();
        invoke(builder, handlerMethod("withResultObserver", MailSendResultObserver.class, Executor.class), result, resultExecutor);
        invoke(builder, handlerMethod("withProgressObserver", MailSendProgressObserver.class, Executor.class), progress, progressExecutor);
        invoke(builder, handlerMethod("clearProgressObserver"));
        assertThat(builder.getResultObserver()).isSameAs(result);
        assertThat(builder.getResultObserverExecutor()).isSameAs(resultExecutor);
        assertThat(builder.getProgressObserver()).isNull();
        assertThat(builder.getProgressObserverExecutor()).isNull();
        invoke(builder, handlerMethod("clearResultObserver"));
        final MailSendHandlers cleared = builder.build();
        assertThat(cleared.getResultObserver()).isNull();
        assertThat(cleared.getResultObserverExecutor()).isNull();
        assertThat(cleared.getProgressObserver()).isNull();
        assertThat(cleared.getProgressObserverExecutor()).isNull();
        assertThat(queued.getResultObserver()).isSameAs(result);
        assertThat(queued.getResultObserverExecutor()).isSameAs(resultExecutor);
        assertThat(queued.getProgressObserver()).isSameAs(progress);
        assertThat(queued.getProgressObserverExecutor()).isSameAs(progressExecutor);
        assertThat(inline.getResultObserver()).isSameAs(replacementResult);
        assertThat(inline.getResultObserverExecutor()).isNull();
        assertThat(inline.getProgressObserver()).isSameAs(replacementProgress);
        assertThat(inline.getProgressObserverExecutor()).isNull();
        verifyNoInteractions(result, replacementResult, progress, replacementProgress, resultExecutor, progressExecutor);
        for (String field : new String[] {"RESULT_OBSERVER", "RESULT_OBSERVER_EXECUTOR", "PROGRESS_OBSERVER", "PROGRESS_OBSERVER_EXECUTOR"}) {
            record("field", "handlers." + field);
        }
        record("rule", "callbacks.value-ownership");
        record("case", "callbacks.handler-builder-routes");
    }

    @Test
    void bothTypedValueFallbackOverloadsApplyTheExplicitConfiguredAndFallbackRules() throws Exception {
        final SimpleJavaMailConfig configured = ConfigLoader.builder().withMap(Map.of(ConfigKeys.SMTP_PORT.key(), "2525")).load();
        final SimpleJavaMailConfig empty = ConfigLoader.builder().load();
        final Method two = SimpleJavaMailConfig.class.getMethod("valueOrProperty", Object.class, ConfigKey.class);
        final Method three = SimpleJavaMailConfig.class.getMethod("valueOrProperty", Object.class, ConfigKey.class, Object.class);
        assertThat(invoke(configured, two, 0, ConfigKeys.SMTP_PORT)).isEqualTo(0);
        assertThat(invoke(configured, two, null, ConfigKeys.SMTP_PORT)).isEqualTo(2525);
        assertThat(invoke(empty, two, null, ConfigKeys.SMTP_PORT)).isNull();
        assertThat(invoke(configured, three, 0, ConfigKeys.SMTP_PORT, 25)).isEqualTo(0);
        assertThat(invoke(configured, three, null, ConfigKeys.SMTP_PORT, 25)).isEqualTo(2525);
        assertThat(invoke(empty, three, null, ConfigKeys.SMTP_PORT, 25)).isEqualTo(25);
        record("field", "core.VALUE_FALLBACK");
        record("rule", "core.value-fallback");
        record("case", "core.value-fallback-overloads");
    }

    private static Method method(final String name, final Class<?>... parameters) throws NoSuchMethodException {
        return MailerGenericBuilder.class.getMethod(name, parameters);
    }

    private static Method handlerMethod(final String name, final Class<?>... parameters) throws NoSuchMethodException {
        return MailSendHandlers.Builder.class.getMethod(name, parameters);
    }
}
