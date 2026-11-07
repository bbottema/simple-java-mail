package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Session;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import jakarta.mail.internet.MimeMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SessionLoggerTest {

    @Test
    void disabledDebugDoesNotReadTheSession() {
        final Session session = mock(Session.class);
        final Logger logger = (Logger) LogManager.getLogger(SessionLogger.class);
        final Level previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            SessionLogger.logSession(session, "ignored");
            verifyNoInteractions(session);
        } finally {
            logger.setLevel(previousLevel);
        }
    }

    @ParameterizedTest
    @EnumSource(LoggingRoute.class)
    void suppliedSessionOperationsReachTheirCallbacksWithoutRenderingOpaqueValues(final LoggingRoute route) throws Exception {
        final Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.host", "relay.example.org");
        defaults.setProperty("mail.smtp.user", "sender@example.org");
        defaults.setProperty("mail.smtp.port", "25");
        defaults.setProperty("mail.smtp.auth", "true");
        final Properties properties = new Properties(defaults);
        properties.setProperty("mail.transport.protocol", "smtp");
        if (route.marked) properties.setProperty("simplejavamail.transportstrategy", "SMTP");
        final Object opaque = new Object() {
            @Override public String toString() { throw new AssertionError("Application object was rendered"); }
        };
        properties.put("application.metadata", opaque);
        properties.put("mail.smtp.host", opaque);
        properties.put("mail.smtp.user", opaque);
        properties.put("mail.smtp.port", 2525);
        properties.put("mail.smtp.auth", false);
        properties.put(opaque, "hidden-under-opaque-key");
        final Session session = Session.getInstance(properties);
        final AtomicInteger connectionTests = new AtomicInteger();
        final AtomicInteger sends = new AtomicInteger();
        final CustomMailer custom = new CustomMailer() {
            @Override public void testConnection(final OperationalConfig configuration, final Session supplied) {
                assertThat(supplied).isSameAs(session);
                connectionTests.incrementAndGet();
            }
            @Override public void sendMessage(final OperationalConfig configuration, final Session supplied,
                    final Email email, final MimeMessage message) {
                assertThat(supplied).isSameAs(session);
                assertThat(email.getPlainText()).isEqualTo("Hello");
                sends.incrementAndGet();
            }
        };
        final Logger logger = (Logger) LogManager.getLogger(SessionLogger.class);
        final Level previousLevel = logger.getLevel();
        final CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(route.debug ? Level.DEBUG : Level.INFO);
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer mailer = mail.mailerBuilder(session).withCustomMailer(custom).buildMailer()) {
            mailer.sync().testConnection();
            mailer.sync().sendMail(mail.emailBuilder().startingBlank().from("sender@example.org")
                    .to("recipient@example.org").withPlainText("Hello").buildEmail());
            assertThat(connectionTests).hasValue(1);
            assertThat(sends).hasValue(1);
            assertThat(properties.get("application.metadata")).isSameAs(opaque);
            if (route.debug) {
                assertThat(appender.messages).hasSize(2).allSatisfy(text -> assertThat(text)
                        .contains("2525", "false")
                        .doesNotContain("hidden-under-opaque-key"));
                if (route.marked) {
                    assertThat(appender.messages).allSatisfy(text -> assertThat(text).contains("relay.example.org", "sender@example.org"));
                } else {
                    assertThat(appender.messages).allSatisfy(text -> assertThat(text).contains(opaque.getClass().getName(), "mail.transport.protocol=smtp"));
                }
            } else {
                assertThat(appender.messages).isEmpty();
            }
        } finally {
            logger.setLevel(previousLevel);
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @Test
    void abbreviatedFieldsEscapeTextAndDescribeOpaqueValuesWithoutRenderingThem() {
        final Properties properties = new Properties();
        properties.setProperty("mail.smtp.host", "relay\r\nexample.org");
        properties.put("mail.smtp.user", new Object() {
            @Override public String toString() { throw new AssertionError("Username object was rendered"); }
        });
        properties.put("mail.smtp.port", 2525);
        properties.put("mail.smtp.auth", false);
        assertThat(TransportStrategy.SMTP.toString(properties)).contains("relay\\r\\nexample.org", "2525", "false")
                .doesNotContain("\r", "\n");
    }

    @Test
    void unknownTransportLoggingRedactsSecretsAndKeepsOrdinarySettings() {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "custom-protocol");
        properties.setProperty("vendor.client", "public-client");
        properties.setProperty("vendor.api_key", "secret-api-value");
        properties.setProperty("mail.smtp.password", "secret-password-value");
        final Logger logger = (Logger) LogManager.getLogger(SessionLogger.class);
        final Level previousLevel = logger.getLevel();
        final CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            SessionLogger.logSession(Session.getInstance(properties), "test operation");
            assertThat(appender.messages).hasSize(1);
            assertThat(appender.messages.get(0)).contains("public-client", "vendor.api_key", "<redacted>")
                    .doesNotContain("secret-api-value", "secret-password-value");
            assertThat(properties.getProperty("mail.smtp.password")).isEqualTo("secret-password-value");
        } finally {
            logger.setLevel(previousLevel);
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> messages = new ArrayList<>();

        private CapturingAppender() {
            super("session-diagnostics-test", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }

    private enum LoggingRoute {
        UNMARKED_DISABLED(false, false), UNMARKED_DEBUG(false, true), MARKED_DISABLED(true, false), MARKED_DEBUG(true, true);
        private final boolean marked;
        private final boolean debug;
        LoggingRoute(final boolean marked, final boolean debug) { this.marked = marked; this.debug = debug; }
    }
}
