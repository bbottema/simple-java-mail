package org.simplejavamail.mailer;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.SmtpCapabilities;
import org.simplejavamail.api.mailer.SmtpConnectionReport;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;
import testutil.conformance.RealSmtpServer;
import testutil.testrules.MimeMessageAndEnvelope;

import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.email.ContentTransferEncoding.BIT8;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.NEVER;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.PARTIALLY_ACCEPTED;
import static org.simplejavamail.api.mailer.MailSubmissionStatus.REJECTED;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTP;
import static org.simplejavamail.api.mailer.config.TransportStrategy.SMTPS;
import static testutil.testrules.ReceivedMailAssertions.assertEnvelopeMatches;

/** Runs only through the explicit conformance profile; every message stays inside the runner's isolated server containers. */
@Timeout(45)
class SmtpServerConformanceIT {

	@BeforeAll
	static void recordRuntime() throws Exception {
		final Properties runtime = new Properties();
		runtime.setProperty("java", System.getProperty("java.runtime.version"));
		runtime.setProperty("os", System.getProperty("os.name"));
		final Properties angus = new Properties();
		try (InputStream metadata = SMTPTransport.class.getResourceAsStream("/META-INF/maven/org.eclipse.angus/angus-mail/pom.properties")) {
			angus.load(metadata);
		}
		runtime.setProperty("angus", angus.getProperty("version"));
		runtime.setProperty("crypto", new BouncyCastleProvider().getInfo());
		try (OutputStream output = Files.newOutputStream(RealSmtpServer.runDirectory().resolve("evidence/runtime.properties"))) {
			runtime.store(output, "Actual test JVM and loaded providers");
		}
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void discoversExpectedCapabilitiesAndAuthenticatesOverVerifiedTls(final RealSmtpServer server) throws Exception {
		try (Mailer mailer = server.mailer().buildMailer()) {
			final SmtpConnectionReport report = mailer.sync().probeConnection(true);
			assertThat(report.isSuccessful()).as(report.toString()).isTrue();
			assertThat(report.isTlsActive()).isTrue();
			assertThat(report.isAuthenticated()).isTrue();
			final SmtpCapabilities capabilities = report.getAfterTls().orElseThrow();
			assertThat(capabilities.getExtensions().keySet()).contains("AUTH", "DSN", "SIZE", "8BITMIME", "SMTPUTF8");
			assertThat(capabilities.supports("REQUIRETLS")).isFalse();
			assertThat(capabilities.getMaximumMessageSize()).hasValue(1048576);
			Files.writeString(RealSmtpServer.runDirectory().resolve("evidence/" + server.serverName() + "-probe.txt"), report.toString());
		}
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void receivesComposedContentAndTheActualEnvelope(final RealSmtpServer server) throws Exception {
		final Email email = server.email("composed").withAttachment("fixture.txt", "attachment café".getBytes(UTF_8), "text/plain").buildEmail();
		try (Mailer mailer = server.mailer().buildMailer()) {
			assertThat(mailer.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
		}
		final MimeMessageAndEnvelope received = server.receive(email.getId());
		assertEnvelopeMatches(received, email);
		final Email decoded = EmailConverter.mimeMessageToEmail(received.getMimeMessage());
		assertThat(decoded.getPlainText()).isEqualTo(email.getPlainText());
		assertThat(decoded.getAttachments()).singleElement().satisfies(attachment -> assertThat(attachment.readAllData()).isEqualTo("attachment café"));
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void keepsConcurrentReceiptsAndObserverOutcomesAttemptLocal(final RealSmtpServer server) throws Exception {
		final List<MailSendOutcome> observed = new CopyOnWriteArrayList<>();
		final List<MailSend<MailSubmissionReceipt>> sends = new ArrayList<>();
		final List<Properties> deliveries = new ArrayList<>();
		try (Mailer mailer = server.mailer().withMailSendObserver(observed::add).buildMailer()) {
			for (int index = 0; index < 6; index++) {
				sends.add(mailer.async().sendMail(server.email("parallel-" + index).buildEmail()));
			}
			for (int index = 0; index < sends.size(); index++) {
				final MailSubmissionReceipt receipt = sends.get(index).getCompletion().get(20, SECONDS);
				assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
				assertThat(receipt.getEmailId()).isEqualTo(server.messageId("parallel-" + index));
				assertThat(observed.stream().map(outcome -> outcome.getSubmissionReceipt().orElseThrow()).collect(Collectors.toList()))
						.anySatisfy(outcomeReceipt -> assertThat(outcomeReceipt).isSameAs(receipt));
				deliveries.addAll(server.awaitDeliveries(receipt.getEmailId(), 1));
			}
		}
		assertThat(observed).hasSize(6);
		assertThat(deliveries.stream().map(item -> item.getProperty("queueId")).distinct()).hasSize(6);
		assertThat(deliveries.stream().map(item -> item.getProperty("connection")).distinct().collect(Collectors.toList()))
				.hasSizeBetween(1, 2).allSatisfy(connection -> assertThat(connection).matches(".+:[1-9][0-9]*"));
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void sendsStreamingBatchesAndRetainsUsableOpenConnections(final RealSmtpServer server) throws Exception {
		try (Mailer mailer = server.mailer().buildMailer()) {
			mailer.sync().sendMailsInSimpleBatch(Arrays.asList(server.email("batch-1").buildEmail(), server.email("batch-2").buildEmail()));
			mailer.async().sendMailsInSimpleBatch(Arrays.asList(server.email("async-batch-1").buildEmail(), server.email("async-batch-2").buildEmail()))
					.getCompletion().get(20, SECONDS);
			mailer.withOpenConnection(sender -> {
				assertThat(sender.sendMailAndGetReceipt(server.email("open-1").buildEmail()).getStatus()).isEqualTo(ACCEPTED);
				assertThat(sender.sendMailAndGetReceipt(server.email("open-2").buildEmail()).getStatus()).isEqualTo(ACCEPTED);
			});
		}
		for (final String name : Arrays.asList("batch-1", "batch-2", "async-batch-1", "async-batch-2", "open-1", "open-2")) {
			server.awaitDeliveries(server.messageId(name), 1);
		}
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void preservesPartialRecipientFactsThenSendsSuccessfully(final RealSmtpServer server) throws Exception {
		final Email mixed = server.email("partial").withRecipients(new Recipient(null, "reject@conformance.test", TO, null),
				new Recipient(null, "another@conformance.test", TO, null)).buildEmail();
		try (Mailer mailer = server.mailer().withProperty("mail.smtp.sendpartial", "true").buildMailer()) {
			final Throwable thrown = catchThrowable(() -> mailer.sync().sendMail(mixed));
			assertThat(thrown).isInstanceOf(MailSubmissionException.class);
			final MailSubmissionReceipt receipt = ((MailSubmissionException) thrown).getSubmissionReceipt();
			assertThat(receipt.getStatus()).isEqualTo(PARTIALLY_ACCEPTED);
			assertThat(receipt.getAcceptedRecipients()).containsExactly("recipient@conformance.test", "another@conformance.test");
			assertThat(receipt.getInvalidRecipients()).containsExactly("reject@conformance.test");
			assertThat(mailer.sync().sendMail(server.email("after-partial").buildEmail()).getInvalidRecipients()).isEmpty();
		}
		final List<Properties> delivered = server.awaitDeliveries(mixed.getId(), 2);
		assertThat(delivered.stream().map(item -> item.getProperty("queueId")).distinct()).hasSize(1);
		server.awaitDeliveries(server.messageId("after-partial"), 1);
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void negotiatesInternationalEnvelopeEightBitBodyAndDsn(final RealSmtpServer server) throws Exception {
		final Email email = server.email("international").clearRecipients().withRecipients(new Recipient(null, "josé@conformance.test", TO, null))
				.withPlainTextContentTransferEncoding(BIT8).withDeliveryStatusNotification(HEADERS_ONLY, NEVER)
				.fixingEnvelopeId(server.serverName() + "-attempt +42=").buildEmail();
		try (Mailer mailer = server.mailer().buildMailer()) {
			final MailSubmissionReceipt receipt = mailer.sync().sendMail(email);
			assertThat(receipt.getStatus()).isEqualTo(ACCEPTED);
			assertThat(receipt.getEnvelopeId()).isEqualTo(server.serverName() + "-attempt +42=");
			assertThat(receipt.getMessageSize()).isPositive();
		}
		assertEnvelopeMatches(server.receive(email.getId()), email);
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void rejectsOversizedContentAndReusesTheMailer(final RealSmtpServer server) throws Exception {
		final Email oversized = server.email("oversized").withAttachment("too-large.bin", new byte[1048576], "application/octet-stream").buildEmail();
		try (Mailer mailer = server.mailer().buildMailer()) {
			final Throwable thrown = catchThrowable(() -> mailer.sync().sendMail(oversized));
			assertThat(thrown).isInstanceOf(MailSubmissionException.class);
			final MailSubmissionReceipt receipt = ((MailSubmissionException) thrown).getSubmissionReceipt();
			assertThat(receipt.getMessageSize()).isGreaterThan(receipt.getServerMaximumMessageSize());
			assertThat(mailer.sync().sendMail(server.email("after-oversized").buildEmail()).getStatus()).isEqualTo(ACCEPTED);
		}
		server.awaitDeliveries(server.messageId("after-oversized"), 1);
		assertThat(server.deliveries(oversized.getId())).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void rejectsOnwardTlsWhenThePinnedServerDoesNotAdvertiseIt(final RealSmtpServer server) throws Exception {
		final Email email = server.email("requiretls").withTlsRequiredForOnwardDelivery().buildEmail();
		try (Mailer mailer = server.mailer().buildMailer()) {
			assertThatThrownBy(() -> mailer.sync().sendMail(email)).isInstanceOf(MailSubmissionException.class);
		}
		assertThat(server.deliveries(email.getId())).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void acceptsOrdinaryMailButRejectsAnInternationalAddressWithoutAdvertisement(final RealSmtpServer server) throws Exception {
		try (Mailer mailer = server.mailer(SMTP).buildMailer()) {
			final SmtpCapabilities capabilities = mailer.sync().probeConnection(false).getBeforeTls().orElseThrow();
			assertThat(capabilities.supports("SMTPUTF8")).isFalse();
			assertThat(capabilities.supports("DSN")).isFalse();
			assertThat(mailer.sync().sendMail(server.email("limited").buildEmail()).getStatus()).isEqualTo(ACCEPTED);
			assertThatThrownBy(() -> mailer.sync().sendMail(server.email("limited-international").clearRecipients()
					.withRecipients(new Recipient(null, "josé@conformance.test", TO, null)).buildEmail())).isInstanceOf(MailSubmissionException.class);
		}
		server.awaitDeliveries(server.messageId("limited"), 1);
		assertThat(server.deliveries(server.messageId("limited-international"))).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void supportsImplicitTlsAndRejectsWrongCredentials(final RealSmtpServer server) throws Exception {
		try (Mailer mailer = server.mailer(SMTPS).buildMailer()) {
			assertThat(mailer.getSession().getProperties().get("mail.smtps.ssl.socketFactory")).isInstanceOf(SSLSocketFactory.class);
			mailer.async().testConnection().get(15, SECONDS);
			assertThat(mailer.async().probeConnection(true).get(15, SECONDS).isAuthenticated()).isTrue();
			assertThat(mailer.sync().sendMail(server.email("smtps").buildEmail()).getStatus()).isEqualTo(ACCEPTED);
			final Email outsideFixture = server.email("external-address").clearRecipients()
					.withRecipients(new Recipient(null, "recipient@outside.invalid", TO, null)).buildEmail();
			assertThatThrownBy(() -> mailer.sync().sendMail(outsideFixture)).isInstanceOfSatisfying(MailSubmissionException.class,
					failure -> assertThat(failure.getStatus()).isEqualTo(REJECTED));
		}
		try (Mailer mailer = server.mailer().withSMTPServerPassword("deliberately-wrong").buildMailer()) {
			assertThat(mailer.sync().probeConnection(true).isSuccessful()).isFalse();
			assertThatThrownBy(() -> mailer.sync().sendMail(server.email("bad-auth").buildEmail())).isInstanceOf(RuntimeException.class);
		}
		server.awaitDeliveries(server.messageId("smtps"), 1);
		assertThat(server.deliveries(server.messageId("bad-auth"))).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void deliversExactContentWithoutRebuildingIt(final RealSmtpServer server) throws Exception {
		final String body = ".A line needing SMTP transparency\r\nFrom untouched\r\nlast line\r\n";
		final String eml = "Message-ID: " + server.messageId("exact") + "\r\nFrom: header@conformance.test\r\n"
				+ "To: not-the-envelope@conformance.test\r\nSubject: Exact fixture\r\nMIME-Version: 1.0\r\n"
				+ "Content-Type: text/plain; charset=us-ascii\r\nContent-Transfer-Encoding: 7bit\r\n\r\n" + body;
		final Email email = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingFromExactEml(eml.getBytes(UTF_8))
				.withEnvelopeSender("bounce@conformance.test").withEnvelopeRecipients("recipient@conformance.test").buildEmail();
		try (Mailer mailer = server.mailer().buildMailer()) {
			assertThat(mailer.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
		}
		final MimeMessageAndEnvelope received = server.receive(email.getId());
		assertThat(received.getEnvelopeSender()).isEqualTo("bounce@conformance.test");
		assertThat(received.getEnvelopeReceiver()).isEqualTo("recipient@conformance.test");
		assertThat(received.getMimeMessage().getContent()).isEqualTo(body);
	}

	@ParameterizedTest
	@EnumSource(RealSmtpServer.class)
	void deliversProtectedFixturesForIndependentVerification(final RealSmtpServer server) throws Exception {
		final Path fixture = RealSmtpServer.runDirectory().resolve("private");
		final Path openPgp = Path.of("src/test/resources/openpgpjs");
		final OpenPgpSigningConfig signing = OpenPgpSigningConfig.builder().secretKeyRing(Files.readAllBytes(openPgp.resolve("private-key.asc")))
				.passphrase("openpgpjs-fixture-passphrase").build();
		final List<Email> emails = Arrays.asList(
				// Reuse the existing signing fixture's DNS-free identity. Independent verification supplies its own local DNS record.
				server.email("dkim").from("fixture@supersecret-testing-domain.com")
						.signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData(Files.readAllBytes(fixture.resolve("private-key.pem")))
								.dkimSigningDomain("supersecret-testing-domain.com").dkimSelector("fixture").build()).buildEmail(),
				server.email("smime-signed").signWithSmime(fixture.resolve("identity.p12"), "fixture-password", "fixture", "fixture-password", null).buildEmail(),
				server.email("smime-encrypted").encryptWithSmime(fixture.resolve("certificate.pem"), null, null).buildEmail(),
				server.email("pgp-signed").signWithOpenPgp(signing).buildEmail(),
				server.email("pgp-encrypted").signWithOpenPgp(signing).encryptWithOpenPgp(OpenPgpEncryptionConfig.builder()
						.addRecipientPublicKeyRing(Files.readAllBytes(openPgp.resolve("public-key.asc"))).build()).buildEmail());
		try (Mailer mailer = server.mailer().buildMailer()) {
			for (final Email email : emails) {
				assertThat(mailer.sync().sendMail(email).getStatus()).isEqualTo(ACCEPTED);
				server.awaitDeliveries(email.getId(), 1);
			}
		}
	}
}
