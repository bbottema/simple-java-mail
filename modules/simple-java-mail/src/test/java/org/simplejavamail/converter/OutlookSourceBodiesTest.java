package org.simplejavamail.converter;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails.SmimeMode;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.outlook.OutlookEmailConversionResult;
import org.simplejavamail.api.outlook.OutlookMessageData;
import org.simplejavamail.outlookmessageparser.OutlookMessageParser;
import org.simplejavamail.outlookmessageparser.model.OutlookMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class OutlookSourceBodiesTest {

	private static final String RESOURCES = determineResourceFolder("simple-java-mail") + "/test/resources/test-messages/";
	private static final String PLAIN_RTF = "{\\rtf1\\ansi\\fromtext native plain body\\par second line}";

	@Test
	void nativeHtmlWinsOverRtfWithoutChangingTheCapturedSource() throws Exception {
		final String html = "<p>native HTML</p>\r\n";
		final OutlookEmailConversionResult result = convert(minimalMsg(html, PLAIN_RTF, "native plain body"));
		assertThat(result.getOutlookMessageData().getBodyHTML()).isEqualTo(html);
		assertThat(result.getOutlookMessageData().getBodyRTF()).isEqualTo(PLAIN_RTF);
		assertThat(result.buildEmail().getHTMLText()).isEqualTo(html);
		assertThat(result.buildEmail().getPlainText()).isEqualTo("native plain body");

		final Email plainOutput = result.getEmailBuilder().clearHTMLText().buildEmail();
		assertThat(plainOutput.getHTMLText()).isNull();
		assertThat(plainOutput.getPlainText()).isEqualTo("native plain body");
		assertThat(result.getOutlookMessageData().getBodyHTML()).isEqualTo(html);
		assertThat(result.getOutlookMessageData().getBodyRTF()).isEqualTo(PLAIN_RTF);
	}

	@Test
	void explicitlyEmptyNativeHtmlDoesNotBecomeMissingOrSelectRtfFallback() throws Exception {
		final OutlookEmailConversionResult result = convert(minimalMsg("", PLAIN_RTF, "native plain body"));
		assertThat(result.getOutlookMessageData().getBodyHTML()).isEmpty();
		assertThat(result.getOutlookMessageData().getBodyRTF()).isEqualTo(PLAIN_RTF);
		assertThat(result.buildEmail().getHTMLText()).isEmpty();
	}

	@Test
	void plainTextRtfKeepsTheExistingHtmlFallback() throws Exception {
		final OutlookEmailConversionResult result = convert(minimalMsg(null, PLAIN_RTF, "native plain body"));
		assertThat(result.getOutlookMessageData().getBodyHTML()).isNull();
		assertThat(result.getOutlookMessageData().getBodyRTF()).isEqualTo(PLAIN_RTF);
		assertThat(result.buildEmail().getHTMLText()).contains("native plain body", "second line").doesNotContain("{\\rtf");
		assertThat(result.buildEmail().getPlainText()).isEqualTo("native plain body");
	}

	@Test
	void absentRtfAndAbsentBodiesStayAbsent() throws Exception {
		final OutlookEmailConversionResult htmlOnly = convert(minimalMsg("<p>HTML only</p>", null, null));
		assertThat(htmlOnly.getOutlookMessageData().getBodyHTML()).isEqualTo("<p>HTML only</p>");
		assertThat(htmlOnly.getOutlookMessageData().getBodyRTF()).isNull();
		assertThat(htmlOnly.buildEmail().getHTMLText()).isEqualTo("<p>HTML only</p>");

		final OutlookEmailConversionResult noBody = convert(minimalMsg(null, null, null));
		assertThat(noBody.getOutlookMessageData().getBodyHTML()).isNull();
		assertThat(noBody.getOutlookMessageData().getBodyRTF()).isNull();
		assertThat(noBody.buildEmail().getHTMLText()).isNull();
		assertThat(noBody.buildEmail().getPlainText()).isNull();
	}

	@Test
	void explicitlyEmptyRtfRemainsCaptured() throws Exception {
		final OutlookEmailConversionResult result = convert(minimalMsg(null, "", null));
		assertThat(result.getOutlookMessageData().getBodyHTML()).isNull();
		assertThat(result.getOutlookMessageData().getBodyRTF()).isEmpty();
	}

	@Test
	void encapsulatedHtmlAndRichTextRemainAvailableAsRtf() throws Exception {
		final OutlookEmailConversionResult encapsulated = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(
				new File(RESOURCES + "simple email with TO and CC.msg"));
		assertThat(encapsulated.getOutlookMessageData().getBodyHTML()).isNull();
		assertThat(encapsulated.getOutlookMessageData().getBodyRTF()).contains("\\fromhtml", "Just a test to get an email");
		assertThat(encapsulated.buildEmail().getHTMLText()).contains("<div dir=\"auto\">Just a test");

		final OutlookEmailConversionResult richText = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(
				new File(RESOURCES + "#576 RtfSampleEmail.msg"));
		assertThat(richText.getOutlookMessageData().getBodyHTML()).isNull();
		assertThat(richText.getOutlookMessageData().getBodyRTF()).startsWith("{\\rtf").contains("BOOK ONE").doesNotContain("\\fromtext", "\\fromhtml");
		assertThat(richText.buildEmail().getHTMLText()).contains("BOOK ONE: 1805").doesNotContain("{\\rtf");
	}

	@ParameterizedTest
	@ValueSource(strings = {"simple email with TO and CC.msg", "#651 simple sent.msg", "#576 RtfSampleEmailWithAttachment.msg", "#869 embedded image.msg"})
	void publicFilePathAndStreamJourneysCaptureTheReaderValues(String fixture) throws Exception {
		final File file = new File(RESOURCES + fixture);
		final OutlookMessage expected = new OutlookMessageParser().parseMsg(file);
		assertBodies(EmailConverter.outlookMsgToEmailBuilderWithOutlookData(file), expected);
		assertBodies(EmailConverter.outlookMsgToEmailBuilderWithOutlookData(file.getPath()), expected);
		assertBodies(EmailConverter.outlookMsgToEmailBuilderWithOutlookData(file, null), expected);
		assertBodies(EmailConverter.outlookMsgToEmailBuilderWithOutlookData(file.getPath(), null), expected);
		try (TrackingInputStream stream = new TrackingInputStream(Files.readAllBytes(file.toPath()))) {
			final OutlookEmailConversionResult result = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(stream);
			assertThat(stream.closed).isTrue(); // Preserve the existing reader's stream ownership.
			stream.forbidReads = true;
			assertBodies(result, expected);
			assertThat(result.buildEmail().getPlainText()).isEqualTo(expected.getBodyText());
		}
		try (TrackingInputStream stream = new TrackingInputStream(Files.readAllBytes(file.toPath()))) {
			assertBodies(EmailConverter.outlookMsgToEmailBuilderWithOutlookData(stream, null), expected);
		}
	}

	@Test
	void protectedContentSelectionDoesNotReattributeNativeSourceBodies() throws Exception {
		final File file = new File(RESOURCES + "SMIME (signed and clear text).msg");
		final OutlookEmailConversionResult result = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(file, null);
		final Email email = result.buildEmail();
		assertThat(result.getOutlookMessageData().getBodyHTML()).isNull();
		assertThat(result.getOutlookMessageData().getBodyRTF()).isNull();
		assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(SmimeMode.SIGNED);
		assertThat(email.getHTMLText()).contains("Invio messaggio SMIME (signed and clear text)");
		assertThat(email.getSmimeSignedEmail()).isNotNull();
		assertThat(email.getAttachments()).extracting("name").containsExactlyInAnyOrder("smime.p7m", "3 crucial benefits of Cloud computing.docx");
		assertThat(result.getOutlookMessageData().getBodyHTML()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"S_MIME test message encrypted.msg", "S_MIME test message signed & encrypted.msg"})
	void selectedDecryptionOptionsKeepNativeSourceDataAcrossInputRoutes(String fixture) throws Exception {
		final File file = new File(determineResourceFolder("simple-java-mail") + "/test/resources/pkcs12/test messages/" + fixture);
		final OutlookMessage expected = new OutlookMessageParser().parseMsg(file);
		final Pkcs12Config key = loadPkcs12KeyStore();
		final OutlookEmailConversionResult result = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(file, key);
		assertBodies(result, expected);
		assertThat(result.buildEmail().getHTMLText()).contains("This is an encrypted message");
		assertThat(result.buildEmail().getOriginalSmimeDetails().getSmimeMode()).isIn(SmimeMode.ENCRYPTED, SmimeMode.SIGNED_ENCRYPTED);
		assertBodies(EmailConverter.outlookMsgToEmailBuilderWithOutlookData(file.getPath(), key), expected);
		try (TrackingInputStream stream = new TrackingInputStream(Files.readAllBytes(file.toPath()))) {
			final OutlookEmailConversionResult fromStream = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(stream, key);
			assertBodies(fromStream, expected);
			assertThat(fromStream.buildEmail().getHTMLText()).isEqualTo(result.buildEmail().getHTMLText());
		}
	}

	@Test
	void sourceBodiesRemainDetachedAfterTheMsgFileIsRemoved(@TempDir Path directory) throws Exception {
		final Path file = Files.write(directory.resolve("source.msg"), minimalMsg("<p>detached</p>", PLAIN_RTF, "text"));
		final OutlookMessageData source = EmailConverter.outlookMsgToEmailBuilderWithOutlookData(file.toFile()).getOutlookMessageData();
		Files.delete(file);
		assertThat(source.getBodyHTML()).isEqualTo("<p>detached</p>");
		assertThat(source.getBodyRTF()).isEqualTo(PLAIN_RTF);
	}

	private static OutlookEmailConversionResult convert(byte[] msg) {
		return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(new ByteArrayInputStream(msg));
	}

	private static void assertBodies(OutlookEmailConversionResult result, OutlookMessage expected) {
		assertThat(result.getOutlookMessageData().getBodyHTML()).isEqualTo(expected.getBodyHTML());
		assertThat(result.getOutlookMessageData().getBodyRTF()).isEqualTo(expected.getBodyRTF());
	}

	private static byte[] minimalMsg(String html, String rtf, String plainText) throws IOException {
		try (POIFSFileSystem fileSystem = new POIFSFileSystem(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			fileSystem.createDocument(new ByteArrayInputStream("IPM.Note".getBytes(UTF_16LE)), "__substg1.0_001A001F");
			fileSystem.createDocument(new ByteArrayInputStream("Date: Mon, 9 Nov 2026 10:00:00 +0000\r\n".getBytes(UTF_16LE)), "__substg1.0_007D001F");
			final byte[] fileTime = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
					.putLong((1600000000000L + 11644473600000L) * 10000L).array();
			fileSystem.createDocument(new ByteArrayInputStream(fileTime), "__substg1.0_30070040");
			fileSystem.createDocument(new ByteArrayInputStream(fileTime), "__substg1.0_30080040");
			if (plainText != null) {
				fileSystem.createDocument(new ByteArrayInputStream(plainText.getBytes(UTF_16LE)), "__substg1.0_1000001F");
			}
			if (html != null) {
				fileSystem.createDocument(new ByteArrayInputStream(html.getBytes(UTF_16LE)), "__substg1.0_1013001F");
			}
			if (rtf != null) {
				final byte[] text = rtf.getBytes(Charset.forName("windows-1252"));
				// The MSG RTF property accepts the standard uncompressed MELA representation.
				final byte[] property = ByteBuffer.allocate(text.length + 16).order(ByteOrder.LITTLE_ENDIAN)
						.putInt(text.length + 12).putInt(text.length).putInt(0x414c454d).putInt(0).put(text).array();
				fileSystem.createDocument(new ByteArrayInputStream(property), "__substg1.0_10090102");
			}
			fileSystem.writeFilesystem(output);
			return output.toByteArray();
		}
	}

	private static final class TrackingInputStream extends FilterInputStream {
		private boolean closed;
		private boolean forbidReads;

		private TrackingInputStream(byte[] bytes) {
			super(new ByteArrayInputStream(bytes));
		}

		@Override
		public int read() throws IOException {
			if (forbidReads) throw new AssertionError("Source getter read the MSG stream again");
			return super.read();
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			if (forbidReads) throw new AssertionError("Source getter read the MSG stream again");
			return super.read(bytes, offset, length);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}
}
