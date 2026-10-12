package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Pins background permission independently of thread scheduling in the public socket-level proxy tests. */
class DestinationConnectionObserverTest {

    @Test
    void authenticatedProxyNeverStartsBackgroundConnectionsOutsideAnOperationOwnedScope() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withProxy("localhost", 1080, "user", "password")
                .withConnectionPoolCoreSize(0).buildMailer()) {
            final MailerImpl implementation = (MailerImpl) mailer;
            assertThat(implementation.connectionObserver().isBackgroundConnectionAllowed()).isFalse();
            try (SmtpProxyScope scope = implementation.openProxy()) {
                // A momentarily running bridge is not background-owned: its operation can end while allocation is still starting.
                assertThat(implementation.connectionObserver().isBackgroundConnectionAllowed()).isFalse();
            }
            assertThat(implementation.connectionObserver().isBackgroundConnectionAllowed()).isFalse();
        }
    }

    @Test
    void anonymousProxyDoesNotNeedTheLocalBridgeAndKeepsNormalBackgroundPermission() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withProxy("localhost", 1080)
                .withConnectionPoolCoreSize(0).buildMailer()) {
            assertThat(((MailerImpl) mailer).connectionObserver().isBackgroundConnectionAllowed()).isTrue();
        }
    }

    @Test
    void loggingOnlyModeDoesNotWarmSmtpConnections() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withTransportModeLoggingOnly(true)
                .withConnectionPoolCoreSize(0).buildMailer()) {
            assertThat(((MailerImpl) mailer).connectionObserver().isBackgroundConnectionAllowed()).isFalse();
        }
    }

    @Test
    void customMailerDoesNotWarmLibraryOwnedSmtpConnections() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withCustomMailer(mock(CustomMailer.class))
                .withConnectionPoolCoreSize(0).buildMailer()) {
            assertThat(((MailerImpl) mailer).connectionObserver().isBackgroundConnectionAllowed()).isFalse();
        }
    }
}
