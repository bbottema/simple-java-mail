package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageParser;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReturnPathImportTest {

	private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	private static final String CONTENT = "From: Sender <sender@example.org>\r\n"
			+ "To: recipient@example.org\r\nCc: cc@example.org\r\n"
			+ "Reply-To: Reply <reply@example.org>\r\n"
			+ "Disposition-Notification-To: receipt@example.org\r\n"
			+ "Return-Receipt-To: return-receipt@example.org\r\n"
			+ "Subject: Readable mail\r\nX-Context: retained\r\n"
			+ "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n"
			+ "--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nPlain body\r\n"
			+ "--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>HTML body</p>\r\n"
			+ "--parts\r\nContent-Disposition: attachment\r\n\r\nAttachment bytes\r\n--parts--\r\n";

	@ParameterizedTest
	@MethodSource("uninterpretableImports")
	void uninterpretableReturnPathKeepsSourceWithoutBounce(final ImportRoute route, final String source) throws Exception {
		final Email email = convert(route, "Return-Path: " + source + "\r\n" + CONTENT);
		assertThat(email.getBounceToRecipient()).isNull();
		assertThat(email.getHeaders().get("Return-Path")).containsExactly(source);
		assertReadableContent(email);
	}

	static Stream<Arguments> uninterpretableImports() {
		return Arrays.stream(ImportRoute.values()).flatMap(route -> Stream.of("(comment only)", "broken <address")
				.map(source -> Arguments.of(route, source)));
	}

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void returnPathRecognitionIsCaseInsensitive(final ImportRoute route) throws Exception {
		final Email malformed = convert(route, "rEtUrN-pAtH: (comment only)\r\n" + CONTENT);
		assertThat(malformed.getBounceToRecipient()).isNull();
		assertThat(malformed.getHeaders().get("Return-Path")).containsExactly("(comment only)");
		final Email valid = convert(route, "rEtUrN-pAtH: Named <bounce@example.org>\r\n" + CONTENT);
		assertThat(valid.getBounceToRecipient().getAddress()).isEqualTo("bounce@example.org");
		assertThat(valid.getBounceToRecipient().getName()).isEqualTo("Named");
	}

	@ParameterizedTest
	@EnumSource(ImportRoute.class)
	void repeatedUninterpretableValuesRetainTheirSourceOrder(final ImportRoute route) throws Exception {
		final Email email = convert(route, "Return-Path: (first)\r\nreturn-path: broken <address\r\n"
				+ "RETURN-PATH: (last)\r\n" + CONTENT);
		assertThat(email.getBounceToRecipient()).isNull();
		assertThat(email.getHeaders().get("Return-Path")).containsExactly("(first)", "broken <address", "(last)");
		assertReadableContent(email);
	}

	@ParameterizedTest
	@MethodSource("interpretableImports")
	void successfulInterpretationKeepsItsExistingMailboxAndName(final ImportRoute route, final String source,
			final String expectedName, final String expectedAddress) throws Exception {
		final Email email = convert(route, "Return-Path: " + source + "\r\n" + CONTENT);
		assertThat(email.getBounceToRecipient().getAddress()).isEqualTo(expectedAddress);
		assertThat(email.getBounceToRecipient().getName()).isEqualTo(expectedName);
		assertThat(email.getHeaders()).doesNotContainKey("Return-Path");
	}

	static Stream<Arguments> interpretableImports() {
		return Arrays.stream(ImportRoute.values()).flatMap(route -> Stream.of(
				Arguments.of(route, "\"Doe, Jane\" <jane@example.org>", "Doe, Jane", "jane@example.org"),
				Arguments.of(route, "First <first@example.org>, Second <second@example.org>", "First", "first@example.org"),
				Arguments.of(route, "Team:first@example.org;", null, "Team:first@example.org;")));
	}

	@ParameterizedTest
	@MethodSource("absentBounceImports")
	void absentBlankAndNullReturnPathsDoNotEstablishABounce(final ImportRoute route, final String header) throws Exception {
		assertThat(convert(route, header + CONTENT).getBounceToRecipient()).isNull();
	}

	static Stream<Arguments> absentBounceImports() {
		return Arrays.stream(ImportRoute.values()).flatMap(route -> Stream.of("", "Return-Path:\r\n", "Return-Path:   \r\n", "Return-Path: <>\r\n")
				.map(header -> Arguments.of(route, header)));
	}

	@Test
	void retainedValuesAreDetachedThroughBuildCopyAndSerialization() throws Exception {
		final EmailPopulatingBuilder imported = MAIL.converter().fromEml("Return-Path: (first)\r\nReturn-Path: broken <address\r\n" + CONTENT)
				.toEmailBuilder();
		final Email original = imported.buildEmail();
		final Email copy = MAIL.emailBuilder().copying(original).buildEmail();
		final Email restored = roundTrip(original);
		imported.withHeader("Return-Path", "later");
		for (final Email email : Arrays.asList(original, copy, restored)) {
			assertThat(email.getBounceToRecipient()).isNull();
			assertThat(email.getHeaders().get("Return-Path")).containsExactly("(first)", "broken <address");
			assertThatThrownBy(() -> email.getHeaders().clear()).isInstanceOf(UnsupportedOperationException.class);
			assertThatThrownBy(() -> email.getHeaders().get("Return-Path").clear()).isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Test
	void returnPathInterpretationDoesNotReadLazyAttachmentContent() throws Exception {
		final AtomicInteger reads = new AtomicInteger();
		final MimeBodyPart attachment = new MimeBodyPart();
		attachment.setDisposition(Part.ATTACHMENT);
		attachment.setFileName("known.bin");
		attachment.setDataHandler(new DataHandler(new DataSource() {
			@Override public InputStream getInputStream() {
				reads.incrementAndGet();
				return new ByteArrayInputStream(new byte[] {1, 2, 3});
			}
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
			@Override public String getContentType() { return "application/octet-stream"; }
			@Override public String getName() { return "known.bin"; }
		}));
		final MimeMultipart parts = new MimeMultipart();
		parts.addBodyPart(attachment);
		final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
		message.setHeader("Return-Path", "(comment only)");
		message.setContent(parts);
		assertThat(MimeMessageParser.parseMimeMessage(message, false).getBounceToAddress()).isNull();
		assertThat(reads.get()).isZero();
	}

	@Test
	void exactAndProtectedRepresentationsKeepTheirOriginalBytes() throws Exception {
		final byte[] exactSource = ("Return-Path: (comment only)\r\n" + CONTENT).getBytes(UTF_8);
		final Email exact = MAIL.emailBuilder().startingFromExactEml(exactSource).withEnvelopeRecipients("recipient@example.org").buildEmail();
		assertThat(exact.getBounceToRecipient()).isNull();
		assertThat(exact.getHeaders().get("Return-Path")).containsExactly("(comment only)");
		assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).isEqualTo(exactSource);
		final byte[] protectedSource = ("Return-Path: broken <address\r\nFrom: sender@example.org\r\n"
				+ "MIME-Version: 1.0\r\nContent-Type: multipart/signed; boundary=signed; protocol=\"application/pgp-signature\"\r\n\r\n"
				+ "--signed\r\nContent-Type: text/plain\r\n\r\nSigned body\r\n--signed\r\n"
				+ "Content-Type: application/pgp-signature\r\n\r\nOriginal signature\r\n--signed--\r\n").getBytes(UTF_8);
		for (final Email email : Arrays.asList(MAIL.converter().fromEml(protectedSource).toEmail(),
				EmailConverter.emlToEmail(new ByteArrayInputStream(protectedSource)))) {
			assertThat(email.getBounceToRecipient()).isNull();
			assertThat(email.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).isEqualTo(protectedSource);
		}
	}

	private static void assertReadableContent(final Email email) {
		assertThat(email.getSubject()).isEqualTo("Readable mail");
		assertThat(email.getFromRecipient().getAddress()).isEqualTo("sender@example.org");
		assertThat(email.getRecipients()).extracting("address").containsExactly("recipient@example.org", "cc@example.org");
		assertThat(email.getReplyToRecipients()).extracting("address").containsExactly("reply@example.org");
		assertThat(email.getDispositionNotificationToRecipients()).extracting("address").containsExactly("receipt@example.org");
		assertThat(email.getReturnReceiptToRecipients()).extracting("address").containsExactly("return-receipt@example.org");
		assertThat(email.getPlainText()).isEqualTo("Plain body");
		assertThat(email.getHTMLText()).isEqualTo("<p>HTML body</p>");
		assertThat(email.getHeaders().get("X-Context")).containsExactly("retained");
		assertThat(email.getAttachments()).extracting("name").containsExactly("UnknownAttachment");
		assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("Attachment bytes".getBytes(UTF_8));
	}

	private static Email convert(final ImportRoute route, final String eml) throws Exception {
		switch (route) {
			case STATIC_EML:
				return EmailConverter.emlToEmail(eml);
			case FLUENT_EML_EAGER:
				return MAIL.converter().fromEml(eml).withFetchAttachmentData(true).toEmail();
			case FLUENT_EML_LAZY:
				return MAIL.converter().fromEml(eml).withFetchAttachmentData(false).toEmail();
			default:
				final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8)));
				final boolean eager = route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.FLUENT_MIME_EAGER;
				if (route == ImportRoute.STATIC_MIME_EAGER || route == ImportRoute.STATIC_MIME_LAZY) {
					return EmailConverter.mimeMessageToEmail(message, null, eager);
				}
				return MAIL.converter().fromMimeMessage(message).withFetchAttachmentData(eager).toEmail();
		}
	}

	private static Email roundTrip(final Email email) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(email);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (Email) input.readObject();
		}
	}

	private enum ImportRoute {
		STATIC_EML, FLUENT_EML_EAGER, FLUENT_EML_LAZY,
		STATIC_MIME_EAGER, STATIC_MIME_LAZY, FLUENT_MIME_EAGER, FLUENT_MIME_LAZY
	}
}
