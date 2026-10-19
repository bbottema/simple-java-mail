package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Session;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SessionLoggerTest {

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
}
