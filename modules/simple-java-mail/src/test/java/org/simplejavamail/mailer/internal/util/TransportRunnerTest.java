package org.simplejavamail.mailer.internal.util;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransportRunnerTest {

    @Test
    void missingProviderErrorExplainsTheRuntimeDependency() {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "missing-test-provider");
        final Session session = Session.getInstance(properties);

        assertThatThrownBy(() -> TransportRunner.transportFor(session))
                .hasMessageContaining("Jakarta Mail transport provider")
                .hasMessageContaining("angus-mail-provider-module")
                .hasMessageContaining("matching MailTransportAdapter");
    }
}
