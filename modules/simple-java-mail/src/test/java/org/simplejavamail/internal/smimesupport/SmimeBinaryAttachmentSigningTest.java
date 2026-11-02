package org.simplejavamail.internal.smimesupport;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
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
import java.util.Collections;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.ContentTransferEncoding.BINARY;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class SmimeBinaryAttachmentSigningTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final byte[] PAYLOAD = {0, 10, 1, 13, 2, 13, 10, (byte) 255};
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

    @ParameterizedTest(name = "{0}, preencoded={1}, borrowed={2}")
    @MethodSource("binaryInputs")
    void signedBinaryAttachmentsKeepTheirDecodedBytes(final Protection protection, final boolean preencoded,
            final boolean borrowed) throws Exception {
        final TrackingSource source = new TrackingSource();
        final EmailPopulatingBuilder builder = MAIL.emailBuilder().startingBlank()
                .from("sender@example.org").to("reader@example.org").withPlainText("Attached");
        if (preencoded) {
            if (borrowed) builder.withPreEncodedAttachment("payload.bin", source, BINARY);
            else builder.withPreEncodedAttachment("payload.bin", PAYLOAD, "application/octet-stream", BINARY);
        } else {
            final AttachmentResource.Builder attachment = AttachmentResource.builder().withName("payload.bin")
                    .withContentTransferEncoding(BINARY);
            if (borrowed) attachment.withDataSource(source);
            else attachment.withData(PAYLOAD, "application/octet-stream");
            builder.withAttachment(attachment.build());
        }
        protection.apply(builder);
        final Email email = builder.buildEmail();
        assertThat(source.opened).isZero();

        final MimeMessage message = MAIL.converter().fromEmail(email).toMimeMessage();
        final byte[] serialized = write(message);
        assertThat(write(message)).containsExactly(serialized);
        MimeBodyPart protectedContent = new MimeBodyPart(new ByteArrayInputStream(serialized));
        if (protection.encrypted) protectedContent = SmimeCryptoSupport.decrypt(protectedContent, decryptionKey);
        if (protection.signed) {
            assertThat(SmimeCryptoSupport.checkSignature(protectedContent)).isTrue();
            protectedContent = (MimeBodyPart) ((MimeMultipart) protectedContent.getContent()).getBodyPart(0);
        }
        final MimeBodyPart attachment = (MimeBodyPart) ((MimeMultipart) protectedContent.getContent()).getBodyPart(1);
        assertThat(attachment.getFileName()).isEqualTo("payload.bin");
        try (InputStream decoded = attachment.getInputStream()) {
            assertThat(decoded.readAllBytes()).containsExactly(PAYLOAD);
        }
        assertThat(attachment.getEncoding()).isEqualTo(protection.signed ? "base64" : "binary");
        final AttachmentResource original = email.getAttachments().get(0);
        assertThat(preencoded ? original.getPreEncodedContentTransferEncoding() : original.getContentTransferEncoding()).isEqualTo(BINARY);
        assertThat(original.readAllBytes()).containsExactly(PAYLOAD);
        if (borrowed) {
            assertThat(original.getDataSource()).isSameAs(source);
            assertThat(source.opened).isPositive().isEqualTo(source.closed);
        }
    }

    private static Stream<Arguments> binaryInputs() {
        return Arrays.stream(Protection.values()).flatMap(protection -> Stream.of(false, true)
                .flatMap(preencoded -> Stream.of(false, true).map(borrowed -> Arguments.of(protection, preencoded, borrowed))));
    }

    @ParameterizedTest
    @EnumSource(value = ContentTransferEncoding.class, names = {"BIT7", "BIT8"})
    void rawNontextIdentityEncodingsAlsoKeepTheirPayload(final ContentTransferEncoding encoding) throws Exception {
        final Email email = MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Attached raw content").withAttachment(AttachmentResource.builder().withName("payload.bin")
                        .withData(PAYLOAD, "application/octet-stream").withContentTransferEncoding(encoding).build())
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(receivingKeys).build()).buildEmail();

        final MimeBodyPart signed = new MimeBodyPart(new ByteArrayInputStream(write(MAIL.converter().fromEmail(email).toMimeMessage())));
        assertThat(SmimeCryptoSupport.checkSignature(signed)).isTrue();
        final MimeBodyPart content = (MimeBodyPart) ((MimeMultipart) signed.getContent()).getBodyPart(0);
        final MimeBodyPart attachment = (MimeBodyPart) ((MimeMultipart) content.getContent()).getBodyPart(1);
        assertThat(attachment.getEncoding()).isEqualTo("base64");
        try (InputStream decoded = attachment.getInputStream()) {
            assertThat(decoded.readAllBytes()).containsExactly(PAYLOAD);
        }
    }

    @Test
    void preparingMultipartContentPreservesItsMetadataAndOneEncodingHeader() throws Exception {
        final MimeBodyPart binary = new MimeBodyPart();
        binary.setDataHandler(new DataHandler(new ByteArrayDataSource(PAYLOAD, "application/octet-stream")));
        binary.setHeader("Content-Type", "application/octet-stream");
        binary.setHeader("Content-Transfer-Encoding", "binary");
        final MimeMultipart multipart = new MimeMultipart("mixed");
        multipart.addBodyPart(binary);
        final MimeBodyPart source = new MimeBodyPart();
        source.setContent(multipart);
        source.setHeader("Content-Type", multipart.getContentType());
        source.setHeader("Content-Transfer-Encoding", "binary");
        source.setHeader("Content-Description", "Original multipart description");
        source.setHeader("Content-Language", "nl");
        final ByteArrayOutputStream before = new ByteArrayOutputStream();
        source.writeTo(before);

        final MimeBodyPart signed = SmimeCryptoSupport.sign(source, decryptionKey, "SHA256withRSA");
        final MimeMultipart signedParts = (MimeMultipart) signed.getContent();
        signed.setHeader("Content-Type", signedParts.getContentType()); // Normally finalized by the enclosing message.
        final MimeBodyPart prepared = (MimeBodyPart) signedParts.getBodyPart(0);

        assertThat(SmimeCryptoSupport.checkSignature(signed)).isTrue();
        assertThat(prepared.getContentType()).isEqualTo(source.getContentType());
        assertThat(prepared.getHeader("Content-Transfer-Encoding")).containsExactly("binary");
        assertThat(prepared.getHeader("Content-Description")).containsExactly("Original multipart description");
        assertThat(prepared.getHeader("Content-Language")).containsExactly("nl");
        final ByteArrayOutputStream after = new ByteArrayOutputStream();
        source.writeTo(after);
        assertThat(after.toByteArray()).containsExactly(before.toByteArray());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signingPreservesTheWholeAttachedMultipartResource(final boolean preencoded) throws Exception {
        final byte[] bundle = ("--bundle\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: binary\r\n\r\n" + new String(PAYLOAD, ISO_8859_1)
                + "\r\n--bundle--\r\n").getBytes(ISO_8859_1);
        final EmailPopulatingBuilder builder = MAIL.emailBuilder().startingBlank()
                .from("sender@example.org").to("reader@example.org").withPlainText("Attached multipart");
        if (preencoded) {
            builder.withPreEncodedAttachment("bundle.mime", bundle, "multipart/mixed; boundary=bundle", BINARY);
        } else {
            builder.withAttachment(AttachmentResource.builder().withName("bundle.mime")
                    .withData(bundle, "multipart/mixed; boundary=bundle").withContentTransferEncoding(BINARY).build());
        }
        final Email email = builder.signWithSmime(SmimeSigningConfig.builder().pkcs12Config(receivingKeys).build()).buildEmail();

        final MimeBodyPart signed = new MimeBodyPart(new ByteArrayInputStream(write(MAIL.converter().fromEmail(email).toMimeMessage())));
        assertThat(SmimeCryptoSupport.checkSignature(signed)).isTrue();
        final MimeBodyPart content = (MimeBodyPart) ((MimeMultipart) signed.getContent()).getBodyPart(0);
        final MimeBodyPart attachment = (MimeBodyPart) ((MimeMultipart) content.getContent()).getBodyPart(1);
        assertThat(attachment.isMimeType("multipart/mixed")).isTrue();
        try (InputStream decoded = attachment.getInputStream()) {
            assertThat(decoded.readAllBytes()).containsExactly(bundle);
        }
        assertThat(email.getAttachments().get(0).readAllBytes()).containsExactly(bundle);
    }

    @Test
    void signingPreservesAnOpaqueBinaryMessageAttachment() throws Exception {
        final byte[] innerMessage = ("From: inner@example.org\r\nContent-Type: application/octet-stream\r\n"
                + "Content-Transfer-Encoding: binary\r\n\r\n" + new String(PAYLOAD, ISO_8859_1)).getBytes(ISO_8859_1);
        final Email email = MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Attached message")
                .withPreEncodedAttachment("original.eml", innerMessage, "message/rfc822", BINARY)
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(receivingKeys).build()).buildEmail();

        final MimeBodyPart signed = new MimeBodyPart(new ByteArrayInputStream(write(MAIL.converter().fromEmail(email).toMimeMessage())));
        assertThat(SmimeCryptoSupport.checkSignature(signed)).isTrue();
        final MimeBodyPart content = (MimeBodyPart) ((MimeMultipart) signed.getContent()).getBodyPart(0);
        final MimeBodyPart attachment = (MimeBodyPart) ((MimeMultipart) content.getContent()).getBodyPart(1);
        try (InputStream decoded = attachment.getInputStream()) {
            assertThat(decoded.readAllBytes()).containsExactly(innerMessage);
        }
    }

    @Test
    void signingKeepsAnAlreadySignedBinaryChildIntact() throws Exception {
        final MimeBodyPart binary = new MimeBodyPart();
        binary.setDataHandler(new DataHandler(new ByteArrayDataSource(PAYLOAD, "application/octet-stream")));
        binary.setHeader("Content-Type", "application/octet-stream");
        binary.setHeader("Content-Transfer-Encoding", "binary");
        final SMIMESignedGenerator nativeSigner = new SMIMESignedGenerator();
        nativeSigner.addCertificates(new JcaCertStore(Collections.singletonList(certificate)));
        nativeSigner.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(SmimeCryptoSupport.BOUNCY_CASTLE)
                .build("SHA256withRSA", decryptionKey.getPrivateKey(), certificate));
        final MimeMultipart innerSigned = nativeSigner.generate(binary);
        final ByteArrayOutputStream original = new ByteArrayOutputStream();
        innerSigned.writeTo(original);
        final Email email = MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Attached signed entity")
                .withPreEncodedAttachment("protected.mime", original.toByteArray(), innerSigned.getContentType(), BINARY)
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(receivingKeys).build()).buildEmail();

        final MimeBodyPart signed = new MimeBodyPart(new ByteArrayInputStream(write(MAIL.converter().fromEmail(email).toMimeMessage())));
        assertThat(SmimeCryptoSupport.checkSignature(signed)).isTrue();
        final MimeBodyPart content = (MimeBodyPart) ((MimeMultipart) signed.getContent()).getBodyPart(0);
        final MimeBodyPart attachment = (MimeBodyPart) ((MimeMultipart) content.getContent()).getBodyPart(1);
        assertThat(SmimeCryptoSupport.checkSignature(attachment)).isTrue();
        try (InputStream decoded = attachment.getInputStream()) {
            assertThat(decoded.readAllBytes()).containsExactly(original.toByteArray());
        }
    }

    private static byte[] write(final MimeMessage message) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private static final class TrackingSource implements DataSource {
        private int opened;
        private int closed;

        @Override public InputStream getInputStream() {
            opened++;
            return new ByteArrayInputStream(PAYLOAD) {
                @Override public void close() throws IOException { closed++; super.close(); }
            };
        }
        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public String getName() { return "payload.bin"; }
    }

    private enum Protection {
        SIGNED(true, false), SIGNED_ENCRYPTED(true, true), ENCRYPTED(false, true);

        private final boolean signed;
        private final boolean encrypted;

        Protection(final boolean signed, final boolean encrypted) { this.signed = signed; this.encrypted = encrypted; }

        private void apply(final EmailPopulatingBuilder builder) {
            if (signed) builder.signWithSmime(SmimeSigningConfig.builder().pkcs12Config(receivingKeys).build());
            if (encrypted) builder.encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(certificate).build());
        }
    }
}
