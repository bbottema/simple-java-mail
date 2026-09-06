package org.simplejavamail.api;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigPropertyDiagnostic;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.Properties;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.config.ConfigLoader.Property.SMTP_HOST;

class Java11ApiConvenienceTest {

	private static final byte[] EML = ("Message-ID: <java11-api@simplejavamail.org>\r\n"
			+ "From: sender@example.org\r\n"
			+ "To: receiver@example.org\r\n"
			+ "Subject: Path input\r\n"
			+ "Content-Type: text/plain; charset=UTF-8\r\n"
			+ "\r\n"
			+ "body\r\n").getBytes(StandardCharsets.UTF_8);
	private static final Path TEST_RESOURCES = Path.of(determineResourceFolder("simple-java-mail"), "test", "resources");

	@Test
	void propertiesFileIsASampledPathSourceWithUsefulProvenance(@TempDir final Path tempDir) throws Exception {
		final Path propertiesFile = tempDir.resolve("mail.properties");
		Files.writeString(propertiesFile, SMTP_HOST.key() + "=first.example.test\n", StandardCharsets.UTF_8);
		final ConfigLoader loader = ConfigLoader.builder().withPropertiesFile(propertiesFile);

		final SimpleJavaMailConfig first = loader.load();
		Files.writeString(propertiesFile, SMTP_HOST.key() + "=second.example.test\n", StandardCharsets.UTF_8);
		final SimpleJavaMailConfig second = loader.load();

		assertThat(first.getStringProperty(SMTP_HOST)).isEqualTo("first.example.test");
		assertThat(second.getStringProperty(SMTP_HOST)).isEqualTo("second.example.test");
		assertThat(findDiagnostic(first, SMTP_HOST.key()).getSourceName()).isEqualTo("file:" + propertiesFile);
		assertThat(ConfigLoader.builder().withPropertiesFile("deployment file", propertiesFile).load()
				.getDiagnostics().toString()).contains("source: deployment file");
		assertThat(Files.deleteIfExists(propertiesFile)).isTrue();
	}

	@Test
	void pathInputsWorkWithoutConvertingThroughFile(@TempDir final Path tempDir) throws Exception {
		try (FileSystem fileSystem = newZipFileSystem(tempDir.resolve("path-inputs.zip"))) {
			final Path plainText = Files.writeString(fileSystem.getPath("plain.txt"), "middle", StandardCharsets.UTF_8);
			final Path plainPrefix = Files.writeString(fileSystem.getPath("plain-prefix.txt"), "before-", StandardCharsets.UTF_8);
			final Path plainSuffix = Files.writeString(fileSystem.getPath("plain-suffix.txt"), "-after", StandardCharsets.UTF_8);
			final Path htmlText = Files.writeString(fileSystem.getPath("body.html"), "<p>middle</p>", StandardCharsets.UTF_8);
			final Path htmlPrefix = Files.writeString(fileSystem.getPath("html-prefix.txt"), "<header>before</header>", StandardCharsets.UTF_8);
			final Path htmlSuffix = Files.writeString(fileSystem.getPath("html-suffix.txt"), "<footer>after</footer>", StandardCharsets.UTF_8);
			final Path exactEml = Files.write(fileSystem.getPath("exact.eml"), EML);
			final Path ordinaryEml = Files.write(fileSystem.getPath("ordinary.eml"), EML);

			final SimpleJavaMail simpleJavaMail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
			final Email bodyEmail = simpleJavaMail.emailBuilder().startingBlank()
					.withPlainText(plainText)
					.prependText(plainPrefix)
					.appendText(plainSuffix)
					.withHTMLText(htmlText)
					.prependTextHTML(htmlPrefix)
					.appendTextHTML(htmlSuffix)
					.buildEmail();
			final Email exactEmail = simpleJavaMail.emailBuilder().startingFromExactEml(exactEml)
					.withEnvelopeRecipients("receiver@example.org")
					.buildEmail();

			assertThat(bodyEmail.getPlainText()).isEqualTo("before-middle-after");
			assertThat(bodyEmail.getHTMLText()).isEqualTo("<header>before</header><p>middle</p><footer>after</footer>");
			assertThat(EmailConverter.emailToEMLByteArray(exactEmail)).containsExactly(EML);
			assertThat(EmailConverter.emlToEmail(ordinaryEml).getSubject()).isEqualTo("Path input");
			assertThat(EmailConverter.emlToEmailBuilder(ordinaryEml).buildEmail().getPlainText()).contains("body");
			assertThat(EmailConverter.emlToMimeMessage(ordinaryEml).getSubject()).isEqualTo("Path input");
			assertThat(simpleJavaMail.converter().emlToEmailBuilder(
					ordinaryEml, null, null, Session.getInstance(new Properties())).buildEmail().getSubject()).isEqualTo("Path input");

			Files.delete(exactEml);
			Files.delete(ordinaryEml);
		}
	}

	@Test
	void outlookPathUsesTheStreamBasedParserAndClosesItsOwnInput(@TempDir final Path tempDir) throws Exception {
		try (FileSystem fileSystem = newZipFileSystem(tempDir.resolve("outlook-input.zip"))) {
			final Path msgPath = Files.copy(
					TEST_RESOURCES.resolve("test-messages/simple email with TO and CC.msg"),
					fileSystem.getPath("message.msg"));

			final OutlookEmailConversionResult conversion = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(msgPath);
			assertThat(conversion.buildEmail().getSubject()).isEqualTo("Test E-Mail");
			assertThat(EmailConverter.outlookMsgToEmail(msgPath).getSubject()).isEqualTo("Test E-Mail");
			assertThat(EmailConverter.outlookMsgToEmailBuilder(msgPath).buildEmail().getSubject()).isEqualTo("Test E-Mail");
			assertThat(SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).converter()
					.outlookMsgToEmailBuilderWithOutlookData(msgPath, null).buildEmail().getSubject()).isEqualTo("Test E-Mail");
			assertThat(Files.deleteIfExists(msgPath)).isTrue();
		}
	}

	@Test
	void cryptographicPathInputsAndLegacyFileDelegatesAreReadImmediatelyAndClosed(@TempDir final Path tempDir) throws Exception {
		final Path dkimPath = Files.copy(TEST_RESOURCES.resolve("dkim/dkim_dummy_key.der"), tempDir.resolve("dkim.der"));
		final Path pkcs12Path = Files.copy(TEST_RESOURCES.resolve("pkcs12/smime_keystore.pkcs12"), tempDir.resolve("smime.p12"));
		final Path certificatePath = Files.copy(
				TEST_RESOURCES.resolve("pkcs12/smime_test_user.pem.standard.crt"), tempDir.resolve("recipient.pem"));

		final DkimConfig dkimConfig = DkimConfig.builder().dkimPrivateKeyPath(dkimPath)
				.dkimSigningDomain("example.org")
				.dkimSelector("selector")
				.build();
		final Pkcs12Config pkcs12Config = Pkcs12Config.builder().pkcs12Store(pkcs12Path)
				.storePassword("letmein")
				.keyAlias("smime_test_user_alias_rsa")
				.keyPassword("letmein")
				.build();
		final SmimeSigningConfig signingConfig = SmimeSigningConfig.builder()
				.pkcs12Config(pkcs12Path, "letmein", "smime_test_user_alias_rsa", "letmein")
				.build();
		final SmimeEncryptionConfig encryptionConfig = SmimeEncryptionConfig.builder().x509Certificate(certificatePath).build();
		final DkimConfig fileDkimConfig = DkimConfig.builder().dkimPrivateKeyPath(dkimPath.toFile()).build();
		final Pkcs12Config filePkcs12Config = Pkcs12Config.builder().pkcs12Store(pkcs12Path.toFile()).build();
		final SmimeSigningConfig fileSigningConfig = SmimeSigningConfig.builder()
				.pkcs12Config(pkcs12Path.toFile(), "letmein", "smime_test_user_alias_rsa", "letmein")
				.build();
		final SmimeEncryptionConfig fileEncryptionConfig = SmimeEncryptionConfig.builder()
				.x509Certificate(certificatePath.toFile())
				.build();
		final SimpleJavaMail simpleJavaMail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

		assertThat(dkimConfig.getDkimPrivateKeyData()).containsExactly(Files.readAllBytes(dkimPath));
		assertThat(pkcs12Config.getPkcs12StoreData()).containsExactly(Files.readAllBytes(pkcs12Path));
		assertThat(signingConfig.getPkcs12Config().getPkcs12StoreData()).containsExactly(Files.readAllBytes(pkcs12Path));
		assertThat(encryptionConfig.getX509Certificate()).isNotNull();
		assertThat(fileDkimConfig.getDkimPrivateKeyData()).containsExactly(Files.readAllBytes(dkimPath));
		assertThat(filePkcs12Config.getPkcs12StoreData()).containsExactly(Files.readAllBytes(pkcs12Path));
		assertThat(fileSigningConfig.getPkcs12Config().getPkcs12StoreData()).containsExactly(Files.readAllBytes(pkcs12Path));
		assertThat(fileEncryptionConfig.getX509Certificate()).isNotNull();
		assertThat(simpleJavaMail.emailBuilder().startingBlank()
				.signWithSmime(pkcs12Path, "letmein", "smime_test_user_alias_rsa", "letmein", null)
				.buildEmail().getSmimeSigningConfig()).isNotNull();
		assertThat(simpleJavaMail.emailBuilder().startingBlank()
				.encryptWithSmime(certificatePath, null, null)
				.buildEmail().getSmimeEncryptionConfig()).isNotNull();
		assertThat(simpleJavaMail.emailBuilder().startingBlank()
				.signWithSmime(pkcs12Path.toFile(), "letmein", "smime_test_user_alias_rsa", "letmein", null)
				.buildEmail().getSmimeSigningConfig()).isNotNull();
		assertThat(simpleJavaMail.emailBuilder().startingBlank()
				.encryptWithSmime(certificatePath.toFile(), null, null)
				.buildEmail().getSmimeEncryptionConfig()).isNotNull();

		assertThat(Files.deleteIfExists(dkimPath)).isTrue();
		assertThat(Files.deleteIfExists(pkcs12Path)).isTrue();
		assertThat(Files.deleteIfExists(certificatePath)).isTrue();
	}

	@Test
	void instantSentDateUsesTheExistingMillisecondBasedStorage() {
		final Instant supplied = Instant.parse("2026-09-02T12:34:56.123456789Z");
		final Instant stored = supplied.truncatedTo(ChronoUnit.MILLIS);
		final EmailPopulatingBuilder builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig())
				.emailBuilder().startingBlank().fixingSentDate(supplied);
		final Email email = builder.buildEmail();

		assertThat(builder.getSentDateAsInstant()).isEqualTo(stored);
		assertThat(builder.getSentDate()).isEqualTo(Date.from(stored));
		assertThat(email.getSentDateAsInstant()).isEqualTo(stored);
		assertThat(email.getSentDate()).isEqualTo(Date.from(stored));
		assertThat(builder.clearSentDate().getSentDateAsInstant()).isNull();
	}

	@Test
	void java11BuilderOverloadsDoNotDuplicateExistingCliOptions() throws Exception {
		assertExcluded(EmailStartingBuilder.class, "startingFromExactEml", Path.class);
		assertExcluded(EmailPopulatingBuilder.class, "withPlainText", Path.class);
		assertExcluded(EmailPopulatingBuilder.class, "prependText", Path.class);
		assertExcluded(EmailPopulatingBuilder.class, "appendText", Path.class);
		assertExcluded(EmailPopulatingBuilder.class, "withHTMLText", Path.class);
		assertExcluded(EmailPopulatingBuilder.class, "prependTextHTML", Path.class);
		assertExcluded(EmailPopulatingBuilder.class, "appendTextHTML", Path.class);
		assertExcluded(EmailPopulatingBuilder.class, "signWithSmime", Path.class, String.class, String.class, String.class, String.class);
		assertExcluded(EmailPopulatingBuilder.class, "encryptWithSmime", Path.class, String.class, String.class);
		assertExcluded(EmailPopulatingBuilder.class, "fixingSentDate", Instant.class);
	}

	private static ConfigPropertyDiagnostic findDiagnostic(final SimpleJavaMailConfig config, final String propertyName) {
		return config.getDiagnostics().getGroups().stream()
				.flatMap(group -> config.getDiagnostics().getProperties(group).stream())
				.filter(property -> property.getPropertyName().equals(propertyName))
				.findFirst()
				.orElseThrow(AssertionError::new);
	}

	private static FileSystem newZipFileSystem(final Path archive) throws IOException {
		final URI uri = URI.create("jar:" + archive.toUri());
		return FileSystems.newFileSystem(uri, Collections.singletonMap("create", "true"));
	}

	private static void assertExcluded(final Class<?> builderType, final String methodName, final Class<?>... parameterTypes)
			throws NoSuchMethodException {
		final Method method = builderType.getMethod(methodName, parameterTypes);
		assertThat(method.getAnnotation(Cli.ExcludeApi.class)).isNotNull();
	}
}
