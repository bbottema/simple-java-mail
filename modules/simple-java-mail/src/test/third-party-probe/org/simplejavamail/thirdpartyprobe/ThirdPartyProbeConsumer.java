package org.simplejavamail.thirdpartyprobe;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpConnectionPhase;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter;
import org.simplejavamail.config.ConfigLoader;

import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * This little program proves an application-supplied probe adapter actually loads and works when Angus is absent.
 * Maven verify packages it separately and runs the same fixture on the classpath and module path. Only the synthetic
 * provider is exercised: no sockets, messages, internal SJM helper classes or test-framework dependencies are involved.
 */
public final class ThirdPartyProbeConsumer {
    public static void main(final String[] args) throws Exception {
        check(ThirdPartyProbeConsumer.class.getModule().isNamed() == "jpms".equals(args[0]), "Wrong runtime path for the fixture");
        assertAngusIsAbsent();
        check(ServiceLoader.load(SmtpConnectionProbeAdapter.class).stream()
                .filter(provider -> provider.type() == PartialProbeAdapter.class).count() == 1, "The fixture adapter was not discovered");
        for (final boolean async : new boolean[]{false, true}) {
            verifyPartialReportsAndIsolation(async);
            verifyFailuresAndRecovery(async);
            verifyUnsupportedProviderDoesNotConnect(async);
        }
        System.out.println("Third-party SMTP probe fixture passed (" + args[0] + ")");
    }

    private static void verifyPartialReportsAndIsolation(final boolean async) throws Exception {
        final AtomicInteger authenticationCalls = new AtomicInteger();
        final AtomicInteger observerCalls = new AtomicInteger();
        final Session source = sourceSession(authenticationCalls);
        try (Mailer mailer = mailer(source, observerCalls)) {
            for (final boolean capabilitiesAvailable : new boolean[]{false, true}) {
                source.getProperties().setProperty("fixture.capabilities", Boolean.toString(capabilitiesAvailable));
                for (final boolean authenticate : new boolean[]{false, true, false}) {
                    final int previousAuthenticationCalls = authenticationCalls.get();
                    final SmtpConnectionReport report = probeAndCheckIsolation(mailer, source, async, authenticate);
                    check(report.isSupported() && report.isSuccessful() && report.isConnected(), "Partial diagnostics prevented a successful connection");
                    check(report.isAuthenticationRequested() == authenticate && report.isAuthenticated() == authenticate, "Authentication choice was lost");
                    check(authenticationCalls.get() == previousAuthenticationCalls + (authenticate ? 1 : 0), "Credentials were resolved outside the opted-in probe");
                    check(report.isTlsActive() && report.getTlsDetails().isEmpty(), "Missing TLS metadata was invented");
                    check(report.getAuthenticationMechanism().isEmpty(), "An unobserved AUTH mechanism was invented");
                    check(report.getBeforeTls().isEmpty(), "A plaintext snapshot was invented for this TLS-only provider");
                    check(report.getAfterTls().isPresent() == capabilitiesAvailable, "Unknown capabilities became an empty known snapshot");
                    check(report.getEffectiveCapabilities().isPresent() == capabilitiesAvailable, "Effective capabilities misrepresented missing facts");
                    if (capabilitiesAvailable) {
                        check(report.getEffectiveCapabilities().orElseThrow().supports("X-FIXTURE"), "An unknown extension was lost");
                        check(report.getEffectiveCapabilities().orElseThrow().getMaximumMessageSize().orElseThrow() == 4096, "The observed SIZE limit was lost");
                    } else {
                        check(report.toString().contains("After TLS: not available"), "Unknown capabilities were not explained");
                    }
                    check(report.getGreeting().orElseThrow().equals("220 fixture\\r\\nsecond line"), "Adapter display fields were not escaped");
                    check(report.toString().contains("TLS details: not available"), "Missing TLS metadata was not explained");
                }
            }
            check(observerCalls.get() == 0, "Connection probes must not invoke a mail-send observer");
        }
    }

    private static void verifyFailuresAndRecovery(final boolean async) throws Exception {
        final Session source = sourceSession(new AtomicInteger());
        try (Mailer mailer = mailer(source, new AtomicInteger())) {
            for (final String failure : List.of("connect", "close", "both")) {
                source.getProperties().setProperty("fixture.capabilities", "true");
                source.getProperties().setProperty("fixture.fail.connect", Boolean.toString(!"close".equals(failure)));
                source.getProperties().setProperty("fixture.fail.close", Boolean.toString(!"connect".equals(failure)));
                final SmtpConnectionReport report = probeAndCheckIsolation(mailer, source, async, false);
                check(!report.isSuccessful() && report.isSupported(), "A provider failure became success or unsupported");
                check(report.getFailurePhase().orElseThrow() == ("close".equals(failure) ? SmtpConnectionPhase.CLOSE : SmtpConnectionPhase.CONNECT),
                        "Cleanup erased the first failure phase");
                check(report.getEffectiveCapabilities().isPresent() == "close".equals(failure), "Cleanup lost facts or connection failure invented them");
                if ("both".equals(failure)) {
                    check(report.getWarnings().stream().anyMatch(warning -> warning.contains("also failed during cleanup")), "Secondary cleanup failure was lost");
                }
            }
            source.getProperties().remove("fixture.fail.connect");
            source.getProperties().remove("fixture.fail.close");
            check(probeAndCheckIsolation(mailer, source, async, false).isSuccessful(), "Earlier failures leaked into a later probe");

            source.getProperties().setProperty("fixture.fail.adapter", "true");
            final int previousTransports = FixtureTransport.CREATED.size();
            final SmtpConnectionReport adapterFailure = probe(mailer, async, false);
            check(!adapterFailure.isSuccessful() && adapterFailure.getFailurePhase().orElseThrow() == SmtpConnectionPhase.SETUP,
                    "An adapter setup exception escaped the report contract");
            check(FixtureTransport.CREATED.size() == previousTransports, "Failed setup opened a transport");
            assertSafeText(adapterFailure);
        }
    }

    private static void verifyUnsupportedProviderDoesNotConnect(final boolean async) throws Exception {
        final Session source = sourceSession(new AtomicInteger());
        source.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", "example.UnclaimedTransport", "fixture", "1"));
        final int previousTransports = FixtureTransport.CREATED.size();
        try (Mailer mailer = mailer(source, new AtomicInteger())) {
            final SmtpConnectionReport report = probe(mailer, async, false);
            check(!report.isSupported() && !report.isSuccessful(), "The adapter claimed an unrelated provider");
            check(FixtureTransport.CREATED.size() == previousTransports, "An unsupported provider caused a fallback connection");
            check(report.getEffectiveCapabilities().isEmpty(), "An unsupported provider gained invented capabilities");
        }
    }

    private static SmtpConnectionReport probeAndCheckIsolation(final Mailer mailer, final Session source,
            final boolean async, final boolean authenticate) throws Exception {
        final Properties originalProperties = (Properties) source.getProperties().clone();
        final Provider originalProvider = source.getProvider("smtp");
        final int previousTransports = FixtureTransport.CREATED.size();
        final Thread callerThread = Thread.currentThread();
        final SmtpConnectionReport report = probe(mailer, async, authenticate);
        check(FixtureTransport.CREATED.size() == previousTransports + 1, "A probe must own exactly one new transport");
        final FixtureTransport transport = FixtureTransport.CREATED.get(previousTransports);
        check(transport.connectionCalls == 1 && transport.closeCalls == 1 && transport.closed, "The connector or cleanup did not run exactly once before completion");
        check((transport.connectionThread != callerThread) == async, "The execution view did not preserve thread semantics");
        check(transport.probeSession != source && transport.probeSession.getProperties() != source.getProperties(), "The caller's Session was used directly");
        check(transport.probeSession.getProperties().get("fixture.shared.hook") == source.getProperties().get("fixture.shared.hook"), "A caller-owned hook was copied or replaced");
        check(!transport.probeSession.getDebug(), "The dedicated Session leaked debug authentication output");
        check(source.getProperties().equals(originalProperties) && source.getDebug() && source.getProvider("smtp") == originalProvider,
                "Probing mutated the caller's configuration, debug mode or provider registration");
        check(!report.getCompletedAt().isBefore(report.getStartedAt()), "Probe timestamps are reversed");
        assertSafeText(report);
        return report;
    }

    private static SmtpConnectionReport probe(final Mailer mailer, final boolean async, final boolean authenticate) throws Exception {
        return async ? mailer.async().probeConnection(authenticate).get(5, SECONDS) : mailer.sync().probeConnection(authenticate);
    }

    private static Mailer mailer(final Session source, final AtomicInteger observerCalls) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder(source)
                .withDebugLogging(true).withMailSendObserver(outcome -> observerCalls.incrementAndGet()).buildMailer();
    }

    private static Session sourceSession(final AtomicInteger authenticationCalls) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "localhost");
        properties.setProperty("mail.smtp.port", "2465");
        properties.setProperty("mail.smtp.user", FixtureTransport.USER);
        properties.setProperty("mail.smtp.auth", "true");
        properties.setProperty("mail.debug.auth", "true");
        properties.put("fixture.shared.hook", new Object());
        final Session source = Session.getInstance(properties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                authenticationCalls.incrementAndGet();
                return new PasswordAuthentication(FixtureTransport.USER, FixtureTransport.PASSWORD);
            }
        });
        source.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", FixtureTransport.class.getName(), "fixture", "1"));
        return source;
    }

    private static void assertSafeText(final SmtpConnectionReport report) {
        check(!report.toString().contains(FixtureTransport.USER) && !report.toString().contains(FixtureTransport.PASSWORD),
                "Provider credentials or raw failures leaked into the report");
    }

    private static void assertAngusIsAbsent() throws Exception {
        try {
            Class.forName("org.eclipse.angus.mail.smtp.SMTPTransport");
            throw new AssertionError("The fixture must run without Angus");
        } catch (ClassNotFoundException expected) {
            // Absence is intentional: this process is the cross-provider compatibility boundary.
        }
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
