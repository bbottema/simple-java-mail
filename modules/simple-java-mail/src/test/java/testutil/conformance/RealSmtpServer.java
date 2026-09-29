package testutil.conformance;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import testutil.ConfigLoaderTestHelper;
import testutil.testrules.MimeMessageAndEnvelope;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static jakarta.mail.Message.RecipientType.TO;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP_TLS;

/** Reads only this runner's endpoints and completed delivery captures; starting/stopping containers belongs to the runner. */
public enum RealSmtpServer {
	POSTFIX, EXIM;

	public static Path runDirectory() {
		final String directory = System.getenv("SJM_CONFORMANCE_RUN");
		if (directory == null) {
			throw new IllegalStateException("Run tools/smtp-conformance/run.py; the real-server profile needs its isolated endpoints and fixtures.");
		}
		return Path.of(directory);
	}

	public String serverName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public MailerRegularBuilder mailer() throws Exception {
		return mailer(SMTP_TLS);
	}

	public MailerRegularBuilder mailer(final TransportStrategy strategy) throws Exception {
		final String endpoint = strategy == SMTP ? "limited-port" : strategy == SMTPS ? "smtps-port" : "port";
		final Properties endpoints = new Properties();
		try (Reader input = Files.newBufferedReader(runDirectory().resolve("endpoints.properties"))) {
			endpoints.load(input);
		}
		final MailerRegularBuilder builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer(endpoints.getProperty(serverName() + ".host", "localhost"), Integer.parseInt(endpoints.getProperty(serverName() + "." + endpoint)))
				.withTransportStrategy(strategy)
				.withSmtpClientHostname("client.conformance.test")
				.withSessionTimeout(10000)
				.withConnectionPoolCoreSize(0)
				.withConnectionPoolMaxSize(2)
				.withThreadPoolSize(2);
		if (strategy != SMTP) {
			builder.withSMTPServerUsername("fixture").withSMTPServerPassword("fixture-password")
					.withCustomSSLFactoryInstance(fixtureTlsContext().getSocketFactory())
					.withProperty(strategy == SMTPS ? "mail.smtps.socketFactory.fallback" : "mail.smtp.socketFactory.fallback", "false");
		}
		return builder;
	}

	private static SSLContext fixtureTlsContext() throws Exception {
		final KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
		trust.load(null, null);
		try (InputStream certificate = Files.newInputStream(runDirectory().resolve("private/certificate.pem"))) {
			trust.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(certificate));
		}
		final TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		managers.init(trust);
		final SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, managers.getTrustManagers(), null);
		return context;
	}

	public EmailPopulatingBuilder email(final String scenario) {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
				.fixingMessageId(messageId(scenario)).from("Sender", "sender@conformance.test")
				.withRecipients(new Recipient("Recipient", "recipient@conformance.test", TO, null)).withBounceTo("bounce@conformance.test")
				.withSubject("Conformance " + scenario).withPlainText("Fixture body: café, Καλημέρα, 東京.");
	}

	public String messageId(final String scenario) {
		return "<" + serverName() + "-" + scenario + "@conformance.test>";
	}

	public List<Properties> awaitDeliveries(final String messageId, final int expectedCount) throws Exception {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		List<Properties> deliveries = deliveries(messageId);
		while (deliveries.size() < expectedCount && System.nanoTime() < deadline) {
			TimeUnit.MILLISECONDS.sleep(50);
			deliveries = deliveries(messageId);
		}
		assertThat(deliveries).as("recipient deliveries for %s", messageId).hasSize(expectedCount);
		return deliveries;
	}

	public List<Properties> deliveries(final String messageId) throws Exception {
		final List<Path> manifests;
		try (Stream<Path> paths = Files.list(captureDirectory())) {
			manifests = paths.filter(path -> path.toString().endsWith(".xml")).collect(Collectors.toList());
		}
		final List<Properties> matches = new ArrayList<>();
		for (final Path manifest : manifests) {
			final Properties properties = new Properties();
			try (InputStream input = Files.newInputStream(manifest)) {
				properties.loadFromXML(input);
			}
			if (messageId.equals(properties.getProperty("messageId"))) {
				matches.add(properties);
			}
		}
		return matches;
	}

	public MimeMessageAndEnvelope receive(final String messageId) throws Exception {
		final Properties delivery = awaitDeliveries(messageId, 1).get(0);
		try (InputStream content = Files.newInputStream(captureDirectory().resolve(delivery.getProperty("file")))) {
			return new MimeMessageAndEnvelope(new MimeMessage(Session.getInstance(new Properties()), content),
					delivery.getProperty("sender"), delivery.getProperty("recipient"));
		}
	}

	private Path captureDirectory() {
		return runDirectory().resolve("captures").resolve(serverName());
	}
}
