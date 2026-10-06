package org.simplejavamail.managedangus;

import jakarta.mail.Transport;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;
import org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.managedangus.factories.ExportedTlsFactory;

import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.ServiceLoader;
import java.util.Properties;

/** This little program catches module-access failures when Jakarta Mail reflectively constructs the managed Angus provider. */
public final class ManagedAngusConsumer {

    /** Maven verify runs this against packaged modules; neither SMTP nor DNS is used. */
    public static void main(final String[] args) throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        for (TransportStrategy strategy : new TransportStrategy[]{TransportStrategy.SMTP, TransportStrategy.SMTPS}) {
            try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withTransportStrategy(strategy)
                    .withMailSendTimeout(Duration.ofSeconds(1)).buildMailer();
                 Transport transport = mailer.getSession().getTransport()) {
                if (!transport.getClass().getSimpleName().equals("ManagedAngusTransport")) {
                    throw new AssertionError("Jakarta Mail did not construct the managed provider");
                }
                final Runnable abort = ServiceLoader.load(MailTransportLifecycleAdapter.class).stream()
                        .map(ServiceLoader.Provider::get)
                        .flatMap(adapter -> adapter.createAbortAction(transport).stream())
                        .findFirst().orElseThrow(() -> new AssertionError("Lifecycle SPI was not discovered"));
                abort.run();
                if (transport.isConnected()) {
                    throw new AssertionError("The probe must not open a connection");
                }
                final Provider selected = mailer.getSession().getProvider(strategy == TransportStrategy.SMTPS ? "smtps" : "smtp");
                final SmtpConnectionProbeAdapter probe = ServiceLoader.load(SmtpConnectionProbeAdapter.class).stream()
                        .map(ServiceLoader.Provider::get).filter(adapter -> adapter.supportsProvider(selected))
                        .findFirst().orElseThrow(() -> new AssertionError("Connection-probe SPI was not discovered"));
                final Session dedicated = Session.getInstance((Properties) mailer.getSession().getProperties().clone());
                final SmtpConnectionReport report = probe.probe(dedicated, false, candidate -> {
                    throw new MessagingException("Stop before network access");
                });
                if (!report.isSupported() || report.isSuccessful() || report.getFailurePhase().isEmpty()) {
                    throw new AssertionError("Connection-probe adapter could not produce a safe failure report");
                }
            }
        }
        verifyExportedFactory(mail);
        verifyHiddenFactory(mail);
        verifySuppliedFactory(mail);
    }

    private static void verifyExportedFactory(final SimpleJavaMail mail) throws Exception {
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 465).withTransportStrategy(TransportStrategy.SMTPS)
                .withCustomSSLFactoryClass(ExportedTlsFactory.class.getName()).buildMailer(); Socket socket = new Socket()) {
            try {
                configuredFactory(mailer).createSocket(socket, "localhost", 465, true);
                throw new AssertionError("The exported factory should retain its fixture failure");
            } catch (IOException expected) {
                if (expected != ExportedTlsFactory.REJECTION || !socket.isClosed()) {
                    throw new AssertionError("Factory failure or connected-socket cleanup changed", expected);
                }
            }
        }
    }

    private static void verifyHiddenFactory(final SimpleJavaMail mail) throws Exception {
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 465).withTransportStrategy(TransportStrategy.SMTPS)
                .withCustomSSLFactoryClass(HiddenTlsFactory.class.getName()).buildMailer(); Socket socket = new Socket()) {
            try {
                configuredFactory(mailer).createSocket(socket, "localhost", 465, true);
                throw new AssertionError("A non-exported class factory must not bypass module visibility");
            } catch (IllegalStateException expected) {
                if (!(expected.getCause() instanceof IllegalAccessException)
                        || !expected.getMessage().contains("export its package to org.simplejavamail.mailprovider.angus") || !socket.isClosed()) {
                    throw new AssertionError("The module-access failure must explain the export/instance remedies and close the socket", expected);
                }
            }
        }
    }

    private static void verifySuppliedFactory(final SimpleJavaMail mail) throws Exception {
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 465).withTransportStrategy(TransportStrategy.SMTPS)
                .withCustomSSLFactoryInstance(new HiddenTlsFactory()).buildMailer(); Socket socket = new Socket()) {
            try {
                configuredFactory(mailer).createSocket(socket, "localhost", 465, true);
                throw new AssertionError("A supplied instance must remain usable without reflective package access");
            } catch (IOException expected) {
                if (expected != ExportedTlsFactory.REJECTION || !socket.isClosed()) {
                    throw new AssertionError("The supplied instance was not invoked normally", expected);
                }
            }
        }
    }

    private static SSLSocketFactory configuredFactory(final Mailer mailer) {
        return (SSLSocketFactory) mailer.getSession().getProperties().get("mail.smtps.ssl.socketFactory");
    }

    public static final class HiddenTlsFactory extends ExportedTlsFactory {
        public static SSLSocketFactory getDefault() {
            return new HiddenTlsFactory();
        }
    }
}
