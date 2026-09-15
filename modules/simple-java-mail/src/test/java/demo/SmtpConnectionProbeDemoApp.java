package demo;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.config.ConfigLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Prints a real probe report against a tiny loopback SMTP peer by default; no credentials or mail are needed.
 * Run with {@code --configured} to inspect DemoAppBase's configured TLS endpoint, optionally followed by
 * {@code --authenticate} to test its credentials. A probe connects even when DemoAppBase's logging-only mode is on.
 * This demo is compiled by CI but is manually runnable; it never submits an email.
 */
public final class SmtpConnectionProbeDemoApp extends DemoAppBase {
    private SmtpConnectionProbeDemoApp() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--configured")) {
            try (Mailer mailer = mailerTLSBuilder.buildMailer()) {
                printReport(mailer.sync().probeConnection(args.length > 1 && args[1].equals("--authenticate")));
            }
        } else {
            runLocalDemo();
        }
    }

    private static void runLocalDemo() throws Exception {
        try (ServerSocket endpoint = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            endpoint.setSoTimeout(10000);
            final CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> serveLocalGreeting(endpoint));
            try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder()
                    .withSMTPServer(endpoint.getInetAddress().getHostAddress(), endpoint.getLocalPort())
                    .withOpportunisticTLS(false).withSessionTimeout(5000).buildMailer()) {
                printReport(mailer.async().probeConnection().get(10, TimeUnit.SECONDS));
            }
            peer.get(10, TimeUnit.SECONDS);
        }
    }

    private static void printReport(final SmtpConnectionReport report) {
        System.out.println(report);
        report.getEffectiveCapabilities().ifPresent(capabilities -> {
            System.out.println("DSN advertised: " + capabilities.supports("DSN"));
            capabilities.getMaximumMessageSize().ifPresent(bytes -> System.out.println("Advertised size limit: " + bytes + " bytes"));
        });
        System.out.println("No email was submitted. Advertisements are not a guarantee that a later send will succeed.");
    }

    private static void serveLocalGreeting(final ServerSocket endpoint) {
        try (Socket connection = endpoint.accept();
             BufferedReader commands = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
             PrintWriter responses = new PrintWriter(connection.getOutputStream(), true, StandardCharsets.US_ASCII)) {
            connection.setSoTimeout(10000);
            responses.print("220 loopback Simple Java Mail probe demo\r\n");
            responses.flush();
            final String greeting = commands.readLine();
            if (greeting == null || !greeting.startsWith("EHLO ")) {
                throw new IllegalStateException("The local demo expected EHLO, not a mail transaction.");
            }
            responses.print("250-loopback\r\n250-SIZE 10485760\r\n250-DSN\r\n250-8BITMIME\r\n250 SMTPUTF8\r\n");
            responses.flush();
            if (!"QUIT".equals(commands.readLine())) {
                throw new IllegalStateException("The probe should close without submitting mail.");
            }
            responses.print("221 bye\r\n");
            responses.flush();
        } catch (Exception failure) {
            throw new IllegalStateException("The local SMTP probe demo failed", failure);
        }
    }
}
