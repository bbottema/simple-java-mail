package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.util.MailTransportLifecycleResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Pins background permission independently of thread scheduling in the public socket-level proxy tests. */
class DestinationConnectionObserverTest {

    @Test
    void authenticatedProxyNeverStartsBackgroundConnectionsOutsideAnOperationOwnedScope() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withProxyConfig(SocksProxyConfig.builder().withHost("localhost").withPort(1080).withUsername("user").withPassword("password").build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer()) {
            final MailerImpl implementation = (MailerImpl) mailer;
            assertThat(MailTransportLifecycleResolver.supportsDirectAuthenticatedSocks(mailer.getSession())).isTrue();
            assertThat(mailer.getSession().getProperties().get("mail.smtp.socketFactory.transport") != null).isTrue();
            assertThat(mailer.getSession().getProperty("mail.smtp.socks.port")).isEqualTo("1080");
            assertThat(implementation.connectionObserver().isBackgroundConnectionAllowed()).isFalse();
            assertThat(implementation.connectionObserver().isBackgroundConnectionAllowed()).isFalse();
        }
    }

    @Test
    void anonymousProxyKeepsNormalBackgroundPermission() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withProxyConfig(SocksProxyConfig.builder().withHost("localhost").withPort(1080).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer()) {
            assertThat(((MailerImpl) mailer).connectionObserver().isBackgroundConnectionAllowed()).isTrue();
        }
    }

    @Test
    void loggingOnlyModeDoesNotWarmSmtpConnections() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withTransportModeLoggingOnly(true)
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer()) {
            assertThat(((MailerImpl) mailer).connectionObserver().isBackgroundConnectionAllowed()).isFalse();
        }
    }

    @Test
    void customMailerDoesNotWarmLibraryOwnedSmtpConnections() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withCustomMailer(mock(CustomMailer.class))
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer()) {
            assertThat(((MailerImpl) mailer).connectionObserver().isBackgroundConnectionAllowed()).isFalse();
        }
    }
}
