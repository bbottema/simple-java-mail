package org.simplejavamail.thirdpartyprobe;

import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import org.simplejavamail.api.mailer.SmtpCapabilities;
import org.simplejavamail.api.mailer.SmtpConnectionPhase;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Proves a separately compiled adapter can report limited provider facts using only the public SPI, without Angus or internal helpers. */
public final class PartialProbeAdapter implements SmtpConnectionProbeAdapter {
    @Override
    public boolean supportsProvider(final Provider provider) {
        return provider.getType() == Provider.Type.TRANSPORT && "smtp".equals(provider.getProtocol())
                && FixtureTransport.class.getName().equals(provider.getClassName());
    }

    @Override
    public SmtpConnectionReport probe(final Session session, final boolean authenticate, final Connector connector) {
        if (Boolean.parseBoolean(session.getProperty("fixture.fail.adapter"))) {
            throw new IllegalStateException("Private adapter failure: " + FixtureTransport.PASSWORD);
        }
        final SmtpConnectionReport.SmtpConnectionReportBuilder report = SmtpConnectionReport.builder()
                .host(session.getProperty("mail.smtp.host")).port(Integer.parseInt(session.getProperty("mail.smtp.port")))
                .protocol("smtp").startedAt(Instant.now()).supported(true).authenticationRequested(authenticate);
        final List<String> warnings = new ArrayList<>(List.of("This fixture provider does not expose TLS session details or the AUTH mechanism."));
        FixtureTransport transport = null;
        boolean connectionFailed = false;
        try {
            transport = (FixtureTransport) session.getTransport();
            // The SPI promises this is a private Session, so adapter-local state must not leak back to the caller.
            session.getProperties().put("fixture.adapter.local", "not caller configuration");
            connector.connect(transport);
            report.connected(true).authenticated(transport.authenticated).tlsActive(true).greeting("220 fixture\r\nsecond line");
            if (Boolean.parseBoolean(session.getProperty("fixture.capabilities"))) {
                report.afterTls(new SmtpCapabilities(Map.of("SIZE", List.of("4096"), "X-FIXTURE", List.of(""))));
            } else {
                warnings.add("This fixture provider cannot expose its EHLO reply; capabilities are unavailable.");
            }
        } catch (MessagingException failure) {
            connectionFailed = true;
            report.failurePhase(SmtpConnectionPhase.CONNECT).failureDescription("The fixture provider could not establish the connection.");
        } finally {
            closeDedicatedTransport(transport, report, warnings, connectionFailed);
        }
        return report.completedAt(Instant.now()).warnings(warnings).build();
    }

    private static void closeDedicatedTransport(final FixtureTransport transport,
            final SmtpConnectionReport.SmtpConnectionReportBuilder report, final List<String> warnings, final boolean connectionFailed) {
        if (transport == null) {
            return;
        }
        try {
            transport.close();
        } catch (MessagingException failure) {
            if (connectionFailed) {
                warnings.add("The fixture provider also failed during cleanup; the connection failure is retained.");
            } else {
                report.failurePhase(SmtpConnectionPhase.CLOSE).failureDescription("The fixture provider could not close normally.");
            }
        }
    }
}
