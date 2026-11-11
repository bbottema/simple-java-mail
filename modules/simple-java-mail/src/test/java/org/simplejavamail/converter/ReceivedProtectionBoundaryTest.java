package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSAlgorithm;
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder;
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.mail.smime.SMIMEEnvelopedGenerator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.ForwardOptions;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceivedProtectionBoundaryTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
    private static final String TOKEN = "PRIVATE-HEADER-123";
    private static final String BODY = "Confidential body";
    private static final String HTML = "<p>Protected HTML</p><img src=\"cid:logo\">";
    private static final byte[] IMAGE = {1, 2, 3, 4};
    private static X509Certificate certificate;
    private static Pkcs12Config keys;

    @BeforeAll
    static void disposableKeys() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair pair = generator.generateKeyPair();
        final X500Name name = new X500Name("CN=Disposable Received Content Fixture");
        certificate = new JcaX509CertificateConverter().setProvider(PROVIDER).getCertificate(
                new JcaX509v3CertificateBuilder(name, BigInteger.ONE, new Date(0), new Date(4102444800000L), name, pair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider(PROVIDER).build(pair.getPrivate())));
        final char[] password = "disposable-fixture".toCharArray();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password);
        store.setKeyEntry("fixture", pair.getPrivate(), password, new java.security.cert.Certificate[] {certificate});
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.store(bytes, password);
        keys = Pkcs12Config.builder().pkcs12Store(bytes.toByteArray()).storePassword(password)
                .keyAlias("fixture").keyPassword(password).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Private-Token", "name", "filename", "size"})
    void encryptingOnlyTheForwardedBodyDoesNotProtectItsConfidentialCustomHeader(final String header) throws Exception {
        final Email received = receivedHeader(header + ": " + TOKEN + "\r\n");
        assertEquals(List.of(TOKEN), List.copyOf(received.getHeaders().get(header)));
        assertEquals(EncryptionStatus.DECRYPTED, received.getInspection().getPlainText().getEncryptionStatus());
        final Email inner = encrypting(received);
        assertConfidentialForwardRefused(inner);
    }

    @Test
    void removingThePrivateBodyDoesNotAuthorizeDisclosureOfTheRetainedHeader() throws Exception {
        final Email received = receivedHeader("X-Private-Token: " + TOKEN + "\r\n");
        final Email redacted = MAIL.emailBuilder().copying(received).clearPlainText().withPlainText("Public replacement").buildEmail();
        assertConfidentialForwardRefused(redacted);
    }

    @Test
    void replacingOneConfidentialHeaderDoesNotReleaseAnother() throws Exception {
        final Email received = receivedHeader("X-First: first-secret\r\nX-Second: " + TOKEN + "\r\n");
        final Email replaced = MAIL.emailBuilder().copying(encrypting(received)).withHeader("x-first", "public", true).buildEmail();
        assertConfidentialForwardRefused(replaced);
    }

    @Test
    void aSnapshotAndCopyKeepTheConfidentialHeaderRequirement() throws Exception {
        final Email inner = encrypting(receivedHeader("X-Private-Token: " + TOKEN + "\r\n"));
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(inner); }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Email) input.readObject();
        }
        final Email copy = MAIL.emailBuilder().copying(restored).buildEmail();
        assertConfidentialForwardRefused(copy);
    }

    @Test
    void encryptingTheCompleteForwardProtectsTheNestedHeader() throws Exception {
        final Email forwarded = encrypting(forward(encrypting(receivedHeader("X-Private-Token: " + TOKEN + "\r\n"))));
        final byte[] bytes = MAIL.converter().fromEmail(forwarded).toEmlBytes();
        assertFalse(text(bytes).contains(TOKEN));
        final Email decoded = MAIL.converter().fromEml(bytes).withSmimeDecryption(keys).toEmail();
        assertEquals(1, decoded.getAttachments().size());
        assertEquals(TOKEN, decoded.getAttachments().get(0).getEmail().getHeaders().get("X-Private-Token").iterator().next());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitDisclosurePermissionStillPreservesConfiguredInnerEncryption(final boolean conversionPermission) throws Exception {
        final Email inner = encrypting(receivedHeader("X-Private-Token: " + TOKEN + "\r\n"));
        final Email forward = MAIL.emailBuilder().forwarding(inner,
                conversionPermission ? ForwardOptions.defaults() : ForwardOptions.allowUnencryptedContent())
                .from("sender@example.org").to("recipient@example.org").buildEmail();
        final byte[] bytes = conversionPermission ? MAIL.converter().fromEmail(forward).allowUnencryptedContent().toEmlBytes()
                : MAIL.converter().fromEmail(forward).toEmlBytes();
        assertTrue(text(bytes).contains(TOKEN));
        assertFalse(text(bytes).contains(BODY));
    }

    @Test
    void explicitlyClearingTheHeaderAllowsAnAlreadyEncryptedBodyToBeForwarded() throws Exception {
        final Email inner = MAIL.emailBuilder().copying(encrypting(receivedHeader("X-Private-Token: " + TOKEN + "\r\n")))
                .clearHeaders().buildEmail();
        final byte[] bytes = MAIL.converter().fromEmail(forward(inner)).toEmlBytes();
        assertFalse(text(bytes).contains(TOKEN));
        assertFalse(text(bytes).contains(BODY));
    }

    @Test
    void suppressedHistoryAndStructureDoNotRequireOuterEncryption() throws Exception {
        final Email inner = encrypting(receivedHeader("DKIM-Signature: " + TOKEN + "\r\n"));
        assertFalse(text(MAIL.converter().fromEmail(forward(inner)).toEmlBytes()).contains(TOKEN));
    }

    @Test
    void anOrdinaryCustomHeaderCanBeForwardedWithoutDisclosurePermission() {
        final Email source = MAIL.converter().fromEml(("X-Private-Token: " + TOKEN + "\r\n\r\nPublic body")
                .getBytes(StandardCharsets.UTF_8)).toEmail();
        assertTrue(text(MAIL.converter().fromEmail(forward(source)).toEmlBytes()).contains(TOKEN));
    }

    @Test
    void aRetainedPublicCarrierHeaderDoesNotInheritTheAuthoredViewsConfidentiality() throws Exception {
        final Email source = receivedHeader("", "public-route");
        assertEquals(List.of("public-route"), List.copyOf(source.getHeaders().get("X-Public-Route")));
        final byte[] bytes = MAIL.converter().fromEmail(forward(encrypting(source))).toEmlBytes();
        assertTrue(text(bytes).contains("public-route"));
        assertFalse(text(bytes).contains(BODY));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "<p>Different native rendering</p>", "<img src=\"cid:logo\">"})
    void selectedProtectedHtmlUsesAnUnambiguousSameMessageNativeImage(final String nativeHtml) throws Exception {
        final Email received = nativeEmail(html(HTML), nativeHtml, new NativeImage("logo.png", "logo", IMAGE));
        assertEquals(HTML, received.getHTMLText());
        assertEquals(1, received.getEmbeddedImages().size());
        final AttachmentResource image = received.getEmbeddedImages().get(0);
        assertEquals("logo", image.getContentId());
        assertArrayEquals(IMAGE, image.readAllBytes());
        assertEquals(EncryptionStatus.DECRYPTED, received.getInspection().getHTMLText().getEncryptionStatus());
        assertEquals(EncryptionStatus.NOT_ENCRYPTED, received.getInspection().getEmbeddedImage(0).getEncryptionStatus());
        assertTrue(received.getInspection().getEmbeddedImage(0).getDecryptions().isEmpty());
        // An independently owned ordinary file keeps that occurrence when it also supplies an inline fallback.
        assertEquals(nativeHtml != null && nativeHtml.contains("cid:logo") ? 0 : 1, received.getAttachments().size());
    }

    @Test
    void sameMessageFallbackUsesTheSelectedHtmlsNativeFilenameAlias() throws Exception {
        final Email received = nativeEmail(html("<img src=\"cid:logo.png\">"), "<img src=\"cid:logo\">",
                new NativeImage("logo.png", "logo", IMAGE));
        assertEquals(1, received.getEmbeddedImages().size());
        assertEquals("logo.png", received.getEmbeddedImages().get(0).getContentId());
        assertArrayEquals(IMAGE, received.getEmbeddedImages().get(0).readAllBytes());
        assertTrue(received.getAttachments().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambiguousNativeCandidatesStayAvailableWithoutInventingAnImageAssociation(final boolean nativeReference) throws Exception {
        final Email received = nativeEmail(html(HTML), nativeReference ? "<img src=\"cid:logo\">" : "<p>Native text</p>",
                new NativeImage("first.png", "logo", IMAGE), new NativeImage("second.png", "logo", new byte[] {5, 6}));
        assertTrue(received.getEmbeddedImages().isEmpty());
        assertEquals(2, received.getAttachments().size());
        assertArrayEquals(IMAGE, received.getAttachments().get(0).readAllBytes());
        assertArrayEquals(new byte[] {5, 6}, received.getAttachments().get(1).readAllBytes());
        assertTrue(received.getProblems().stream().anyMatch(problem -> problem.getCode() == EmailProblem.Code.CONTENT_NOT_REPRESENTED
                && problem.getSources().size() == 2));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aProtectedImageWinsAndTheUnusedNativeOccurrenceLosesOnlyItsActiveBinding(final boolean explicitFilename) throws Exception {
        final MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(html(HTML));
        final MimeBodyPart image = new MimeBodyPart();
        image.setDataHandler(new DataHandler(new ByteArrayDataSource(new byte[] {8, 9}, "image/png")));
        image.setContentID("<logo>");
        if (explicitFilename) image.setFileName("protected.png");
        image.setDisposition("inline");
        related.addBodyPart(image);
        final MimeBodyPart content = new MimeBodyPart();
        content.setContent(related);
        final Email received = nativeEmail(content, "<img src=\"cid:logo\">", new NativeImage("logo.png", "logo", IMAGE));
        assertEquals(1, received.getEmbeddedImages().size());
        assertArrayEquals(new byte[] {8, 9}, received.getEmbeddedImages().get(0).readAllBytes());
        assertEquals(EncryptionStatus.DECRYPTED, received.getInspection().getEmbeddedImage(0).getEncryptionStatus());
        assertEquals(1, received.getAttachments().size());
        assertNull(received.getAttachments().get(0).getContentId());
        assertArrayEquals(IMAGE, received.getAttachments().get(0).readAllBytes());
        assertEquals(EncryptionStatus.NOT_ENCRYPTED, received.getInspection().getAttachment(0).getEncryptionStatus());
    }

    @Test
    void anUnusedNativeImageRemainsAnOrdinaryAttachmentWithItsMetadata() throws Exception {
        final Email received = nativeEmail(html("<p>Protected HTML without images</p>"), "<img src=\"cid:logo\">",
                new NativeImage("logo.png", "logo", IMAGE));
        assertTrue(received.getEmbeddedImages().isEmpty());
        assertEquals(1, received.getAttachments().size());
        assertEquals("logo", received.getAttachments().get(0).getContentId());
        assertArrayEquals(IMAGE, received.getAttachments().get(0).readAllBytes());
    }

    private static Email receivedHeader(final String headers) throws Exception {
        return receivedHeader(headers, null);
    }

    private static Email receivedHeader(final String headers, final String publicRoute) throws Exception {
        final byte[] source = (headers + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + BODY).getBytes(StandardCharsets.UTF_8);
        final MimeBodyPart content = new MimeBodyPart();
        content.setContent(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source)), "message/rfc822");
        final MimeBodyPart encrypted = encrypt(content);
        if (publicRoute != null) encrypted.setHeader("X-Public-Route", publicRoute);
        return MAIL.converter().fromEml(bytes(encrypted)).withSmimeDecryption(keys).toEmail();
    }

    private static void assertConfidentialForwardRefused(final Email source) {
        final MailException refusal = assertThrows(MailException.class, () -> MAIL.converter().fromEmail(forward(source)).toEmlBytes());
        assertTrue(refusal.getMessage().contains("confidential"));
        assertFalse(refusal.getMessage().contains(TOKEN));
    }

    private static Email encrypting(final Email email) {
        return MAIL.emailBuilder().copying(email).encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(certificate).build()).buildEmail();
    }

    private static Email forward(final Email source) {
        return MAIL.emailBuilder().forwarding(source).from("sender@example.org").to("recipient@example.org").buildEmail();
    }

    private static MimeBodyPart html(final String value) throws Exception {
        final MimeBodyPart content = new MimeBodyPart();
        content.setContent(value, "text/html; charset=UTF-8");
        return content;
    }

    private static MimeBodyPart encrypt(final MimeBodyPart content) throws Exception {
        final SMIMEEnvelopedGenerator generator = new SMIMEEnvelopedGenerator();
        generator.addRecipientInfoGenerator(new JceKeyTransRecipientInfoGenerator(certificate).setProvider(PROVIDER));
        return generator.generate(content, new JceCMSContentEncryptorBuilder(CMSAlgorithm.AES128_CBC).setProvider(PROVIDER).build());
    }

    private static byte[] bytes(final MimeBodyPart part) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        part.writeTo(output);
        return output.toByteArray();
    }

    private static String text(final byte[] bytes) { return new String(bytes, StandardCharsets.ISO_8859_1); }

    private static Email nativeEmail(final MimeBodyPart protectedContent, final String nativeHtml, final NativeImage... images) throws Exception {
        final byte[] payload;
        try (var input = encrypt(protectedContent).getInputStream()) { payload = input.readAllBytes(); }
        try (POIFSFileSystem file = new POIFSFileSystem(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final DirectoryNode root = file.getRoot();
            property(root, "001A", "IPM.Note.SMIME");
            property(root, "0037", "Public subject");
            property(root, "007D", "From: source@example.org\r\nSubject: Public subject\r\n"
                    + "Content-Type: application/pkcs7-mime; smime-type=enveloped-data\r\n");
            if (nativeHtml != null) property(root, "1013", nativeHtml);
            nativeFile(root, 0, "smime.p7m", "application/pkcs7-mime", null, payload);
            for (int index = 0; index < images.length; index++) {
                final NativeImage image = images[index];
                nativeFile(root, index + 1, image.name, "image/png", image.cid, image.bytes);
            }
            file.writeFilesystem(output);
            return MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(output.toByteArray())).withSmimeDecryption(keys).toEmail();
        }
    }

    private static void nativeFile(final DirectoryNode root, final int index, final String name, final String type,
            final String cid, final byte[] payload) throws Exception {
        final DirectoryNode item = (DirectoryNode) root.createDirectory(String.format("__attach_version1.0_#%08X", index));
        property(item, "3707", name);
        property(item, "370E", type);
        if (cid != null) property(item, "3712", cid);
        item.createDocument("__substg1.0_37010102", new ByteArrayInputStream(payload));
    }

    private static void property(final DirectoryNode node, final String id, final String value) throws Exception {
        node.createDocument("__substg1.0_" + id + "001F", new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_16LE)));
    }

    private static final class NativeImage {
        private final String name;
        private final String cid;
        private final byte[] bytes;
        private NativeImage(final String name, final String cid, final byte[] bytes) { this.name = name; this.cid = cid; this.bytes = bytes; }
    }
}
