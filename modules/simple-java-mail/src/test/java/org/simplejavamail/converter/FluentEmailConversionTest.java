package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.EmlConversion;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.util.JakartaMailImplementation;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FluentEmailConversionTest {

	private static final byte[] EML = ("From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Source\r\n"
			+ "Message-ID: <source@example.org>\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nHello\r\n").getBytes(UTF_8);
	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
	private final ConfiguredEmailConverter converter = mail.converter();

	@Test
	void supportsEveryEmlInputWithoutReadingUntilConversion(@TempDir final Path directory) throws Exception {
		final TrackingInput input = new TrackingInput(EML);
		final EmlConversion selected = converter.fromEml(input);
		assertThat(input.reads).isZero();
		assertThat(selected.toEmail().getSubject()).isEqualTo("Source");
		assertThat(input.reads).isPositive();
		assertThat(input.closed).isFalse();

		assertThat(converter.fromEml(new String(EML, UTF_8)).toEmail().getSubject()).isEqualTo("Source");
		assertThat(converter.fromEml(EML).toEmailBuilder().getSubject()).isEqualTo("Source");
		final Path file = directory.resolve("source.eml");
		final EmlConversion lazyPath = converter.fromEml(file);
		Files.write(file, EML);
		assertThat(lazyPath.toMimeMessage().getSubject()).isEqualTo("Source");
		assertThat(converter.fromEml(file.toFile()).toEmail().getSubject()).isEqualTo("Source");
		Files.delete(file);
	}

	@Test
	void pathsRetainTheirFilesystemAndReleaseOpenedStreams(@TempDir final Path directory) throws Exception {
		final Path archive = directory.resolve("messages.zip");
		try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Collections.singletonMap("create", "true"))) {
			final Path eml = zip.getPath("/source.eml");
			Files.write(eml, EML);
			assertThat(converter.fromEml(eml).toEmail().getSubject()).isEqualTo("Source");
			assertThat(converter.fromEml(eml).toMimeMessage().getSubject()).isEqualTo("Source");
			Files.delete(eml);
		}
		Files.delete(archive);
	}

	@Test
	void terminalConversionConsumesSourceOnceAndOptionsCannotBeChangedAfterward() {
		final EmlConversion selected = converter.fromEml(EML).withFetchAttachmentData(false);
		selected.toEmail();
		assertThatThrownBy(selected::toMimeMessage).hasMessageContaining("already been used");
		assertThatThrownBy(() -> selected.withFetchAttachmentData(false)).hasMessageContaining("already been used");

		final InputStream broken = new InputStream() {
			@Override
			public int read() throws IOException {
				throw new IOException("broken source");
			}
		};
		final EmlConversion failed = converter.fromEml(broken);
		assertThatThrownBy(failed::toEmail).hasRootCauseMessage("broken source");
		assertThatThrownBy(failed::toEmail).hasMessageContaining("already been used");
	}

	@Test
	void byteInputIsCapturedDefensivelyAndSessionIsNotReplaced() {
		final byte[] mutable = EML.clone();
		final EmlConversion selected = converter.fromEml(mutable);
		java.util.Arrays.fill(mutable, (byte) 'x');
		final Session session = JakartaMailImplementation.createSession(new Properties());
		assertThat(selected.withSession(session).toMimeMessage().getSession()).isSameAs(session);
		assertThat(converter.fromEml(EML).withSession(session).resetSession().toMimeMessage().getSession()).isNotSameAs(session);
	}

	@Test
	void retainsEachFactoriesSnapshotThroughBothIncomingRoutes() throws Exception {
		final Map<String, Object> strictSettings = new HashMap<>();
		strictSettings.put(ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR.key(), true);
		strictSettings.put(ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL.key(), true);
		final SimpleJavaMail strict = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("strict", strictSettings).load());
		strictSettings.put(ConfigLoader.Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR.key(), false);
		final SimpleJavaMail passive = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("passive", strictSettings).load());
		final MimeMessage message = new MimeMessage(JakartaMailImplementation.createSession(new Properties()));
		message.setContent("<img src=\"missing.png\">", "text/html; charset=UTF-8");
		message.saveChanges();
		final byte[] bytes = EmailConverter.mimeMessageToEMLByteArray(message);

		assertThatThrownBy(() -> strict.converter().fromMimeMessage(message).toEmail()).hasMessageContaining("missing.png");
		assertThatThrownBy(() -> strict.converter().fromEml(bytes).toEmail()).hasMessageContaining("missing.png");
		assertThat(passive.converter().fromMimeMessage(message).toEmail().getEmbeddedImages()).isEmpty();
		assertThat(passive.converter().fromEml(bytes).toEmail().getEmbeddedImages()).isEmpty();
	}

	@Test
	void directMimeExportDoesNotInterpretOrReconstructProtectedContent() throws Exception {
		final String protectedEml = "From: sender@example.org\r\nContent-Type: multipart/signed; boundary=boundary; "
				+ "protocol=\"application/pgp-signature\"\r\n\r\n--boundary\r\nContent-Type: text/plain\r\n\r\n"
				+ "Signed content\r\n--boundary\r\nContent-Type: application/pgp-signature\r\n\r\n"
				+ "original signature bytes\r\n--boundary--\r\n";
		final MimeMessage source = converter.fromEml(protectedEml).toMimeMessage();
		assertThat(converter.fromMimeMessage(source).toEmlBytes()).isEqualTo(EmailConverter.mimeMessageToEMLByteArray(source));
		assertThat(converter.fromMimeMessage(source).toEml()).contains("original signature bytes", "Signed content");
	}

	@Test
	void exactEmailExportRetainsBytesAndDoesNotApplyFactoryTemplates() throws Exception {
		final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("templates",
				Collections.singletonMap(ConfigLoader.Property.DEFAULT_SUBJECT.key(), "Factory subject")).load());
		final Email exact = configured.emailBuilder().startingFromExactEml(EML).withEnvelopeRecipients("reader@example.org").buildEmail();
		assertThat(configured.converter().fromEmail(exact).toEmlBytes()).isEqualTo(EML);
		assertThat(configured.converter().fromEmail(exact).toEml()).isEqualTo(new String(EML, UTF_8));
		final Email withoutSubject = configured.emailBuilder().startingBlank().withPlainText("body").buildEmail();
		assertThat(configured.converter().fromEmail(withoutSubject).toMimeMessage().getSubject()).isNull();
		assertThat(configured.converter().fromEmail(withoutSubject).withEmailGovernance(EmailGovernanceImpl.withConfig(configured.getConfig()))
				.toMimeMessage().getSubject()).isEqualTo("Factory subject");
		assertThat(configured.converter().fromEmail(withoutSubject).withEmailGovernance(EmailGovernanceImpl.withConfig(configured.getConfig()))
				.clearEmailGovernance().toMimeMessage().getSubject()).isNull();
	}

	@Test
	void exportsEveryEmailTargetUsingTheSelectedSession() throws Exception {
		final Email source = converter.fromEml(EML).toEmail();
		final Session session = JakartaMailImplementation.createSession(new Properties());
		assertThat(converter.fromEmail(source).withSession(session).toMimeMessage().getSession()).isSameAs(session);
		assertThat(converter.fromEmail(source).toEml()).contains("Subject: Source", "Hello");
		assertThat(new String(converter.fromEmail(source).toEmlBytes(), UTF_8)).contains("Subject: Source", "Hello");
	}

	@Test
	void receiveOptionsRemainOptionalAndCanBeClearedWithoutNullPlaceholders() {
		final Pkcs12Config keys = Pkcs12Config.builder().pkcs12Store(new byte[] {1}).storePassword("unused")
				.keyAlias("unused").keyPassword("unused").build();
		final OpenPgpReceiveConfig pgp = OpenPgpReceiveConfig.builder().build();
		assertThat(converter.fromEml(EML).withSmimeDecryption(keys).clearSmimeDecryption()
				.withOpenPgpReceiveConfig(pgp).clearOpenPgpReceiveConfig().withFetchAttachmentData(false).resetFetchAttachmentData()
				.toEmail().getSubject()).isEqualTo("Source");
		assertThat(converter.fromEml(EML).withOpenPgpReceiveConfig(pgp).toEmail().getSubject()).isEqualTo("Source");
	}

	@Test
	void rejectsOptionsThatDoNotApplyToTheSelectedTarget() {
		final TrackingInput input = new TrackingInput(EML);
		final EmlConversion misconfigured = converter.fromEml(input).withFetchAttachmentData(false);
		assertThatThrownBy(misconfigured::toMimeMessage)
				.hasMessageContaining("Receive-side options").hasMessageContaining("toEmail");
		assertThat(input.reads).isZero();
		assertThat(misconfigured.resetFetchAttachmentData().toMimeMessage()).isNotNull();
		assertThatThrownBy(() -> converter.fromMimeMessage(EmailConverter.emlToMimeMessage(new ByteArrayInputStream(EML)))
				.withOpenPgpReceiveConfig(OpenPgpReceiveConfig.builder().build()).toEmlBytes()).hasMessageContaining("Receive-side options");
		assertThatThrownBy(() -> converter.fromOutlookMsg("unused.msg").withSession(JakartaMailImplementation.createSession(new Properties()))
				.toEmail()).hasMessageContaining("Session and Email-governance options");
	}

	@Test
	void fluentReceiveConfigurationDecryptsSmimeForEmlMimeAndOutlook() {
		final Pkcs12Config keys = org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore();
		final Path fixtures = Path.of(determineResourceFolder("simple-java-mail"), "test/resources/pkcs12/test messages");
		final Path eml = fixtures.resolve("S_MIME test message encrypted.eml");
		final Email fromEml = converter.fromEml(eml).withSmimeDecryption(keys).toEmail();
		final Email fromMime = converter.fromMimeMessage(EmailConverter.emlToMimeMessage(eml)).withSmimeDecryption(keys).toEmail();
		final Email fromOutlook = converter.fromOutlookMsg(fixtures.resolve("S_MIME test message encrypted.msg"))
				.withSmimeDecryption(keys).toEmail();
		for (final Email result : new Email[] {fromEml, fromMime, fromOutlook}) {
			assertThat(result.getOriginalSmimeDetails().getDecryptionStatus())
					.isEqualTo(org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.DECRYPTED);
			assertThat(result.getPlainText()).contains("For testing purposes");
		}
		assertThat(fromOutlook.getOriginalSmimeDetails().getSmimeMode())
				.isEqualTo(org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode.ENCRYPTED);
	}

	@Test
	void lazyAttachmentChoiceDoesNotAddContentReadsBeyondExistingConversion() throws Exception {
		final AtomicInteger reads = new AtomicInteger();
		final MimeBodyPart attachment = new MimeBodyPart();
		attachment.setFileName("example.txt");
		attachment.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() {
				reads.incrementAndGet();
				return new ByteArrayInputStream("attachment".getBytes(UTF_8));
			}
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return "application/octet-stream"; }
			@Override public String getName() { return "example.txt"; }
		}));
		final MimeMultipart content = new MimeMultipart();
		content.addBodyPart(attachment);
		final MimeMessage message = new MimeMessage(JakartaMailImplementation.createSession(new Properties()));
		message.setContent(content);
		message.saveChanges();
		reads.set(0);
		EmailConverter.mimeMessageToEmailBuilder(message, null, false).buildEmail();
		final int existingReads = reads.get();
		reads.set(0);
		final Email lazy = converter.fromMimeMessage(message).withFetchAttachmentData(false).toEmail();
		// Protection inspection can read the original message. The fluent layer must not introduce another read or eager attachment copy.
		assertThat(reads.get()).isEqualTo(existingReads);
		assertThat(lazy.getAttachments()).hasSize(1);
		try (InputStream input = lazy.getAttachments().get(0).getDataSourceInputStream()) {
			assertThat(input.readAllBytes()).isEqualTo("attachment".getBytes(UTF_8));
		}
		assertThat(reads.get()).isEqualTo(existingReads + 1);
	}

	@Test
	void outlookInputsRetainSourceDataAndSupportAllOutputTargets(@TempDir final Path directory) throws Exception {
		final Path path = Path.of(determineResourceFolder("simple-java-mail"), "test/resources/test-messages/simple email with TO and CC.msg");
		final OutlookEmailConversionResult result = converter.fromOutlookMsg(path).toEmailBuilderWithOutlookData();
		assertThat(result.buildEmail().getSubject()).isEqualTo("Test E-Mail");
		assertThat(result.getOutlookMessageData().getHeaderValues("to")).isNotEmpty();
		assertThat(converter.fromOutlookMsg(path.toString()).toEmail().getSubject()).isEqualTo("Test E-Mail");
		assertThat(converter.fromOutlookMsg(path.toFile()).toEmailBuilder().getSubject()).isEqualTo("Test E-Mail");
		final TrackingInput input = new TrackingInput(Files.readAllBytes(path));
		assertThat(converter.fromOutlookMsg(input).toEmail().getSubject()).isEqualTo("Test E-Mail");
		assertThat(input.closed).isFalse();
		final TrackingInput legacyInput = new TrackingInput(Files.readAllBytes(path));
		assertThat(EmailConverter.outlookMsgToEmail(legacyInput).getSubject()).isEqualTo("Test E-Mail");
		assertThat(legacyInput.closed).as("the released static route retains its existing parser ownership").isTrue();
		final Session session = JakartaMailImplementation.createSession(new Properties());
		assertThat(converter.fromOutlookMsg(path).withSession(session).toMimeMessage().getSession()).isSameAs(session);
		assertThat(converter.fromOutlookMsg(path).toEml()).contains("Test E-Mail");
		assertThat(new String(converter.fromOutlookMsg(path).toEmlBytes(), UTF_8)).contains("Test E-Mail");

		final Path archive = directory.resolve("outlook.zip");
		try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + archive.toUri()), Collections.singletonMap("create", "true"))) {
			final Path zipped = zip.getPath("/source.msg");
			Files.copy(path, zipped);
			assertThat(converter.fromOutlookMsg(zipped).toEmail().getSubject()).isEqualTo("Test E-Mail");
			Files.delete(zipped);
		}
		Files.delete(archive);
	}

	@Test
	void outlookImportsRetainIndependentFactorySnapshots() {
		final Path source = Path.of(determineResourceFolder("simple-java-mail"), "test/resources/test-messages/simple email with TO and CC.msg");
		final Map<String, Object> settings = new HashMap<>();
		settings.put(ConfigLoader.Property.DEFAULT_BCC_ADDRESS.key(), "archive-a@example.org");
		final SimpleJavaMail factoryA = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("factory A", settings).load());
		settings.put(ConfigLoader.Property.DEFAULT_BCC_ADDRESS.key(), "archive-b@example.org");
		final SimpleJavaMail factoryB = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("factory B", settings).load());
		settings.put(ConfigLoader.Property.DEFAULT_BCC_ADDRESS.key(), "later-mutation@example.org");
		assertThat(factoryA.converter().fromOutlookMsg(source).toEmailBuilder().isIgnoreDefaults()).isTrue();
		assertThat(EmailConverter.outlookMsgToEmailBuilderWithOutlookData(source, null).getEmailBuilder().isIgnoreDefaults()).isTrue();

		// Outlook imports deliberately suppress defaults. Re-enable them explicitly to exercise the retained factory snapshot.
		final Email firstA = factoryA.converter().fromOutlookMsg(source).toEmailBuilder().ignoringDefaults(false)
				.buildEmailCompletedWithDefaultsAndOverrides();
		final Email emailB = factoryB.converter().fromOutlookMsg(source).toEmailBuilderWithOutlookData().getEmailBuilder()
				.ignoringDefaults(false).buildEmailCompletedWithDefaultsAndOverrides();
		final Email secondA = factoryA.converter().fromOutlookMsg(source).toEmailBuilderWithOutlookData().getEmailBuilder()
				.ignoringDefaults(false).buildEmailCompletedWithDefaultsAndOverrides();

		assertThat(firstA.getRecipients()).filteredOn(recipient -> recipient.getType() == jakarta.mail.Message.RecipientType.BCC)
				.extracting("address").containsExactly("archive-a@example.org");
		assertThat(emailB.getRecipients()).filteredOn(recipient -> recipient.getType() == jakarta.mail.Message.RecipientType.BCC)
				.extracting("address").containsExactly("archive-b@example.org");
		assertThat(secondA.getRecipients()).filteredOn(recipient -> recipient.getType() == jakarta.mail.Message.RecipientType.BCC)
				.extracting("address").containsExactly("archive-a@example.org");
	}

	@Test
	void consumedOutlookConversionDoesNotSuggestChangingItsExportOptions() {
		final Path source = Path.of(determineResourceFolder("simple-java-mail"), "test/resources/test-messages/simple email with TO and CC.msg");
		final ConfiguredEmailConverter.OutlookConversion selected = converter.fromOutlookMsg(source)
				.withSession(JakartaMailImplementation.createSession(new Properties()));
		selected.toMimeMessage();
		assertThatThrownBy(selected::toEmail).hasMessageContaining("already been used")
				.hasMessageNotContaining("reset the Session");
	}

	private static final class TrackingInput extends ByteArrayInputStream {
		private int reads;
		private boolean closed;

		TrackingInput(final byte[] bytes) {
			super(bytes);
		}

		@Override
		public synchronized int read() {
			reads++;
			return super.read();
		}

		@Override
		public synchronized int read(final byte[] bytes, final int offset, final int length) {
			reads++;
			return super.read(bytes, offset, length);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}
}
