package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

class SessionCaptureContractTest {
    @Test
    void suppliedConstructionNeedsNoPrivateLiveSessionForCaptureResolutionOrValidation() throws Exception {
        final Properties defaults = new Properties();
        defaults.setProperty("mail.transport.protocol", "smtp");
        defaults.setProperty("mail.debug", "true");
        final Properties properties = new Properties(defaults);
        // Native debug state can intentionally disagree with the visible inherited raw property.
        properties.setProperty("mail.debug", "false");
        final Session supplied = Session.getInstance(properties);
        properties.remove("mail.debug");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream sink = new PrintStream(bytes);
        supplied.setDebugOut(sink);
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
        try (MockedStatic<Session> nativeConstruction = mockStatic(Session.class)) {
            nativeConstruction.when(() -> Session.getInstance(any(Properties.class)))
                    .thenThrow(new AssertionError("Configuration capture created a private live Session"));
            try (Mailer mailer = factory.mailerBuilder(supplied).buildMailer()) {
                assertThat(mailer.getSession()).isSameAs(supplied);
                assertThat(mailer.getOperationalConfig().getDebugConfig().getEnabled()).isFalse();
                assertThat(mailer.getOperationalConfig().getDebugConfig().getPrinter()).isSameAs(sink);
                assertThat(supplied.getProperty("mail.debug")).isEqualTo("true");
                assertThat(supplied.getProperties().containsKey("mail.debug")).isFalse();
            }
        }
        assertThat(bytes.size()).isZero();
        assertThat(supplied.getDebugOut()).isSameAs(sink);
    }
}
