package org.simplejavamail.internal.smimesupport;

import jakarta.activation.CommandMap;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.EmailConversionResult;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.mailer.MailerHelper;
import org.simplejavamail.utils.mail.smime.SmimeKey;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.NOT_SIGNED;
import static org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus.VALID;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeFinalizedContentEncodingTest {

    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String PLAIN_TEXT = "ASCII body";
    private static final String HTML_TEXT = "<p>Caf\u00e9</p>";
    private static final String CALENDAR_TEXT = "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nSUMMARY:Caf\u00e9\r\nEND:VCALENDAR\r\n";

    private static Pkcs12Config receivingKeys;
    private static X509Certificate certificate;
    private static SmimeKey decryptionKey;

    @BeforeAll
    static void loadKeys() throws Exception {
        receivingKeys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(receivingKeys.getPkcs12StoreData()), receivingKeys.getStorePassword());
        certificate = (X509Certificate) store.getCertificate(receivingKeys.getKeyAlias());
        decryptionKey = new SmimeKey((PrivateKey) store.getKey(receivingKeys.getKeyAlias(), receivingKeys.getKeyPassword()),
                new X509Certificate[] {certificate});
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("singleBodies")
    void protectionPreservesTheExplicitEncodingOfASingleBody(final Protection protection, final Body body) throws Exception {
        final EmailPopulatingBuilder builder = newEmailBuilder().withContentEncodingConfig(ContentEncodingConfig.builder()
                .withContentTransferEncoding(ContentTransferEncoding.BASE_64).build());
        body.configure(builder);

        final MimeBodyPart content = protectedContent(protection.apply(builder).buildEmail(), protection);

        assertThat(content.isMimeType(body.mimeType)).isTrue();
        assertThat(content.getEncoding()).isEqualTo("base64");
        assertThat(decodedText(content)).isEqualTo(body.text);
    }

    private static Stream<Arguments> singleBodies() {
        return Arrays.stream(Protection.values()).flatMap(protection -> Arrays.stream(Body.values())
                .map(body -> Arguments.of(protection, body)));
    }

    @ParameterizedTest
    @EnumSource(Protection.class)
    void multipartChildrenRetainTheirIndependentEncodings(final Protection protection) throws Exception {
        final EmailPopulatingBuilder builder = newEmailBuilder().withPlainText(PLAIN_TEXT).withHTMLText(HTML_TEXT)
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withPlainTextContentTransferEncoding(ContentTransferEncoding.BASE_64)
                        .withHTMLTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE).build());

        final MimeBodyPart content = protectedContent(protection.apply(builder).buildEmail(), protection);
        final MimeMultipart alternative = (MimeMultipart) content.getContent();
        final MimeBodyPart plain = (MimeBodyPart) alternative.getBodyPart(0);
        final MimeBodyPart html = (MimeBodyPart) alternative.getBodyPart(1);

        assertThat(plain.getEncoding()).isEqualTo("base64");
        assertThat(plain.getContent()).isEqualTo(PLAIN_TEXT);
        assertThat(html.getEncoding()).isEqualTo("quoted-printable");
        assertThat(html.getContent()).isEqualTo(HTML_TEXT);
    }

    @ParameterizedTest
    @EnumSource(Protection.class)
    void defaultBodyEncodingRemainsTheFinalizedOrdinaryDefault(final Protection protection) throws Exception {
        final Email email = protection.apply(newEmailBuilder().withPlainText(PLAIN_TEXT)).buildEmail();

        final MimeBodyPart content = protectedContent(email, protection);

        assertThat(content.getEncoding()).isEqualTo("quoted-printable");
        assertThat(content.getContent()).isEqualTo(PLAIN_TEXT);
    }

    @Test
    void contentHeadersAreRetainedWithoutMovingMessageHeadersIntoTheSignedEntity() throws Exception {
        final Email email = Protection.SIGNED.apply(newEmailBuilder().withPlainText(PLAIN_TEXT)
                .withHeader("cOnTeNt-LaNgUaGe", "nl")
                .withHeader("X-Outer", "message metadata")).buildEmail();

        final MimeBodyPart content = protectedContent(email, Protection.SIGNED);

        assertThat(content.getHeader("Content-Language")).containsExactly("nl");
        assertThat(content.getHeader("Subject")).isNull();
        assertThat(content.getHeader("From")).isNull();
        assertThat(content.getHeader("X-Outer")).isNull();
    }

    @Test
    void recipientCertificateEncryptionUsesTheSameFinalizedContentBoundary() throws Exception {
        final Email email = MAIL.emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(MAIL.recipientBuilder().withSmimeCertificate(certificate).to("reader@example.org"))
                .withPlainText(PLAIN_TEXT)
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withPlainTextContentTransferEncoding(ContentTransferEncoding.BASE_64).build())
                .buildEmail();

        final MimeBodyPart content = protectedContent(email, Protection.ENCRYPTED);

        assertThat(content.getEncoding()).isEqualTo("base64");
        assertThat(content.getContent()).isEqualTo(PLAIN_TEXT);
    }

    @Test
    void encryptingTheSameSourceAgainDoesNotReplaceItsContentHandlers() throws Exception {
        final Session session = Session.getInstance(new Properties());
        final MimeMessage source = new MimeMessage(session);
        final TrackingDataHandler originalHandler = new TrackingDataHandler();
        source.setFrom("sender@example.org");
        source.setDataHandler(originalHandler);
        source.setHeader("Content-Transfer-Encoding", "base64");
        source.saveChanges();
        final byte[] original = write(source);
        final Email encryption = Protection.ENCRYPTED.apply(newEmailBuilder()).buildEmail();

        for (int attempt = 0; attempt < 2; attempt++) {
            final MimeMessage encrypted = MailerHelper.signAndOrEncryptMessageWithSmime(session, source, encryption);
            final MimeBodyPart content = protectedContent(encrypted, Protection.ENCRYPTED);

            assertThat(content.getEncoding()).isEqualTo("base64");
            assertThat(decodedText(content)).isEqualTo(PLAIN_TEXT);
            assertThat(source.getDataHandler()).isSameAs(originalHandler);
            assertThat(originalHandler.commandMapChanges).isZero();
            assertThat(write(source)).containsExactly(original);
        }
    }

    @ParameterizedTest
    @EnumSource(Protection.class)
    void unreadableAttachmentProducesValidProtectedRemainderWithDiagnosticEvidence(final Protection protection) throws Exception {
        final IOException unreadable = new IOException("attachment unavailable");
        final AtomicInteger opened = new AtomicInteger();
        final DataSource attachment = new DataSource() {
            @Override public InputStream getInputStream() throws IOException { opened.incrementAndGet(); throw unreadable; }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "unavailable.bin"; }
        };
        final Email email = protection.apply(newEmailBuilder().withPlainText(PLAIN_TEXT)
                .withAttachment("unavailable.bin", attachment)).buildEmail();

        final EmailConversionResult<MimeMessage> converted = MAIL.converter().fromEmail(email).toMimeMessageResult();

        assertThat(converted.hasValue()).isTrue();
        assertThat(converted.getFailure()).isNull();
        assertThat(converted.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.CONVERSION);
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getMessage()).contains(unreadable.getMessage());
            final EmailSourcePart evidence = (EmailSourcePart) problem.getSources().get(0);
            assertThat(evidence.getProperties()).anySatisfy(property -> {
                assertThat(property.getName()).isEqualTo("name");
                assertThat(property.getValue()).isEqualTo("unavailable.bin");
            });
            assertThat(evidence.getContent()).as("opening failed before any source bytes were acquired").isNull();
        });
        final MimeBodyPart remainder = protectedContent(converted.getValue(), protection);
        assertThat(remainder.isMimeType("text/plain")).isTrue();
        assertThat(remainder.getContent()).isEqualTo(PLAIN_TEXT);
        assertThat(remainder.getEncoding()).isEqualTo("quoted-printable");
        assertThat(email.getAttachments()).hasSize(1);
        assertThat(opened).hasValue(1);
    }

    private static EmailPopulatingBuilder newEmailBuilder() {
        return MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withSubject("Finalized S/MIME content");
    }

    private static MimeBodyPart protectedContent(final Email email, final Protection protection) throws Exception {
        return protectedContent(MAIL.converter().fromEmail(email).toMimeMessage(), protection);
    }

    private static MimeBodyPart protectedContent(final MimeMessage message, final Protection protection) throws Exception {
        final byte[] firstWrite = write(message);
        assertThat(write(message)).containsExactly(firstWrite);
        final Email imported = MAIL.converter().fromEml(firstWrite).withSmimeDecryption(receivingKeys).toEmail();
        assertThat(imported.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(protection.signed ? VALID : NOT_SIGNED);
        if (protection.encrypted) {
            assertThat(imported.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(DECRYPTED);
        }
        MimeBodyPart content = new MimeBodyPart(new ByteArrayInputStream(firstWrite));
        if (protection.encrypted) {
            content = SmimeCryptoSupport.decrypt(content, decryptionKey);
        }
        if (protection.signed) {
            assertThat(SmimeCryptoSupport.checkSignature(content)).isTrue();
            content = (MimeBodyPart) ((MimeMultipart) content.getContent()).getBodyPart(0);
        }
        return content;
    }

    private static byte[] write(final MimeMessage message) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private static String decodedText(final MimeBodyPart content) throws Exception {
        try (InputStream input = content.getInputStream()) {
            return new String(input.readAllBytes(), UTF_8);
        }
    }

    private static final class TrackingDataHandler extends DataHandler {
        private int commandMapChanges;

        TrackingDataHandler() {
            super(PLAIN_TEXT, "text/plain; charset=UTF-8");
        }

        @Override
        public synchronized void setCommandMap(final CommandMap commandMap) {
            commandMapChanges++;
            super.setCommandMap(commandMap);
        }
    }

    private enum Protection {
        SIGNED(true, false), ENCRYPTED(false, true), SIGNED_ENCRYPTED(true, true);

        private final boolean signed;
        private final boolean encrypted;

        Protection(final boolean signed, final boolean encrypted) {
            this.signed = signed;
            this.encrypted = encrypted;
        }

        EmailPopulatingBuilder apply(final EmailPopulatingBuilder builder) {
            if (signed) {
                builder.signWithSmime(SmimeSigningConfig.builder().pkcs12Config(receivingKeys).build());
            }
            if (encrypted) {
                builder.encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(certificate).build());
            }
            return builder;
        }
    }

    private enum Body {
        PLAIN("text/plain", PLAIN_TEXT), HTML("text/html", HTML_TEXT), CALENDAR("text/calendar", CALENDAR_TEXT);

        private final String mimeType;
        private final String text;

        Body(final String mimeType, final String text) {
            this.mimeType = mimeType;
            this.text = text;
        }

        void configure(final EmailPopulatingBuilder builder) {
            switch (this) {
                case PLAIN: builder.withPlainText(text); break;
                case HTML: builder.withHTMLText(text); break;
                case CALENDAR: builder.withCalendarText(CalendarMethod.REQUEST, text); break;
                default: throw new AssertionError(this);
            }
        }
    }
}
