package org.simplejavamail.openpgp;

import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import org.bouncycastle.openpgp.PGPUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.openpgpsupport.OpenPgpSupport;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class OpenPgpTransferEncodingTest {
    private static final char[] PASSPHRASE = "openpgpjs-fixture-passphrase".toCharArray();

    @ParameterizedTest
    @ValueSource(strings = {"base64", "quoted-printable"})
    void transferEncodedSignaturesVerifyWithoutChangingTheSignedEntity(final String encoding) throws Exception {
        final byte[] original = resource("signed-mixed.eml");
        final byte[] incoming = withTransferEncoding(original, encoding);

        for (ImportRoute route : ImportRoute.values()) {
            final Email parsed = read(incoming, receiving(), route);
            assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).as(route.name()).isEqualTo(SignatureStatus.VERIFIED);
            assertReadableFixture(parsed);
            assertOriginalForRoute(parsed, incoming, route);
            assertThat(firstEntity(incoming)).containsExactly(firstEntity(original));
        }
        assertThat(new String(firstEntity(original), StandardCharsets.US_ASCII)).contains("H=C3=A9llo", "Content-Transfer-Encoding: quoted-printable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"base64", "quoted-printable"})
    void transferEncodedCiphertextDecryptsAcrossPublicConversionRoutes(final String encoding) throws Exception {
        final byte[] incoming = withTransferEncoding(resource("encrypted-mixed.eml"), encoding);
        for (ImportRoute route : ImportRoute.values()) {
            final Email parsed = read(incoming, receiving(), route);
            assertThat(parsed.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).as(route.name()).isEqualTo(DecryptionStatus.DECRYPTED);
            assertReadableFixture(parsed);
            assertOriginalForRoute(parsed, incoming, route);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"7bit", "8bit", "binary"})
    void identityEncodingsKeepOrdinarySignatureAndCiphertextInterpretation(final String encoding) throws Exception {
        final Email signed = read(withTransferEncoding(resource("signed-mixed.eml"), encoding), receiving(), ImportRoute.FLUENT_EML);
        final Email encrypted = read(withTransferEncoding(resource("encrypted-mixed.eml"), encoding), receiving(), ImportRoute.FLUENT_EML);
        assertThat(signed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(encrypted.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        assertReadableFixture(signed);
        assertReadableFixture(encrypted);
    }

    @Test
    void transferEncodingCommentsAndCaseUseJakartaMailInterpretation() throws Exception {
        final byte[] incoming = withTransferEncoding(resource("signed-mixed.eml"), "base64", "BaSe64 (ASCII armor)");
        assertThat(read(incoming, receiving(), ImportRoute.FLUENT_EML).getInspection().getPlainText().getSignatureStatus())
                .isEqualTo(SignatureStatus.VERIFIED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"base64", "quoted-printable"})
    void nestedMimeSignaturesAndCiphertextAreDecodedIndependently(final String innerEncoding) throws Exception {
        final byte[] signed = withTransferEncoding(resource("signed-mixed.eml"), innerEncoding);
        final Session session = Session.getInstance(new Properties());
        final MimeMessage signedMessage = new MimeMessage(session, new ByteArrayInputStream(signed));
        final MimeMessage encrypted = new OpenPgpSupport().encryptMessage(session,
                SimpleJavaMail.fromDefaults().emailBuilder().startingBlank().buildEmail(), signedMessage,
                OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(resource("public-key.asc")).build());
        final String outerEncoding = innerEncoding.equals("base64") ? "quoted-printable" : "base64";
        final byte[] incoming = withTransferEncoding(EmailConverter.mimeMessageToEMLByteArray(encrypted), outerEncoding);

        final Email parsed = read(incoming, receiving(), ImportRoute.FLUENT_EML);
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(parsed.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        assertThat(parsed.getInspection().getOriginalMessage().readAllBytes()).containsExactly(incoming);
        assertReadableFixture(parsed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unsupported", "base64", "quoted-printable"})
    void unsupportedOrUndecodablePayloadsRetainProtectionFailuresAndSourceEvidence(final String encoding) throws Exception {
        for (String fixture : new String[] {"signed-mixed.eml", "encrypted-mixed.eml"}) {
            final byte[] source = resource(fixture);
            final ProtectionPart part = protectionPart(source);
            final byte[] payload = encoding.equals("unsupported")
                    ? part.message.substring(part.payloadStart, part.payloadEnd).getBytes(StandardCharsets.US_ASCII)
                    : (encoding.equals("base64") ? "AA=" : "=XX").getBytes(StandardCharsets.US_ASCII);
            final byte[] incoming = replacePayload(source, encoding, payload);
            final Email parsed = read(incoming, receiving(), ImportRoute.FLUENT_EML);
            assertThat(parsed).isNotNull();
            assertThat(parsed.getInspection().getOriginalMessage().readAllBytes()).containsExactly(incoming);
            final EmailProblem.Code expectedProblem = encoding.equals("quoted-printable")
                    ? EmailProblem.Code.PROTECTION_FAILED : EmailProblem.Code.CONTENT_READ_FAILED;
            assertThat(parsed.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(expectedProblem));
            if (fixture.startsWith("signed")) {
                assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
                assertReadableFixture(parsed);
                assertThat(firstEntity(parsed.getInspection().getOriginalMessage().readAllBytes())).containsExactly(firstEntity(source));
            } else {
                assertThat(parsed.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.FAILED);
                assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
                assertThat(parsed.getPlainText()).as("failed decryption must not fabricate a clear body").isNull();
                if (!encoding.equals("quoted-printable")) {
                    assertThat(parsed.getProblems()).anySatisfy(problem -> {
                        assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
                        assertThat(problem.getSources()).anySatisfy(element -> {
                            assertThat(element).isInstanceOf(EmailSourcePart.class);
                            assertThat(((EmailSourcePart) element).getContent()).isNotNull();
                        });
                    });
                    assertThat(allParts(parsed.getSourceData()).filter(p -> p.getContent() != null)
                            .map(p -> new String(p.getContent().readAllBytes(), StandardCharsets.ISO_8859_1)))
                            .anySatisfy(raw -> assertThat(raw).contains(new String(payload, StandardCharsets.ISO_8859_1)));
                }
                final Session session = Session.getInstance(new Properties());
                final var inspected = new OpenPgpSupport().openIncomingLayer(session,
                        new MimeMessage(session, new ByteArrayInputStream(incoming)), receiving());
                assertThat(inspected.isOpened()).isFalse();
                assertThat(inspected.getDecryptions()).singleElement()
                        .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.FAILED));
                assertThat(inspected.getSignatures()).isEmpty();
            }
            final SimpleJavaMail factory = SimpleJavaMail.fromDefaults();
            final Email exact = factory.emailBuilder().startingFromExactEml(incoming).withEnvelopeRecipients("reader@example.org").buildEmail();
            assertThat(factory.converter().fromEmail(exact).toEmlBytes()).containsExactly(incoming);
        }
    }

    @Test
    void failedBase64DecodingDoesNotHideRawBytesStillAvailableOnTheSameCachedPart() throws Exception {
        final byte[] incoming = replacePayload(resource("encrypted-mixed.eml"), "base64", "AA=".getBytes(StandardCharsets.US_ASCII));
        final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(incoming));
        final MimeBodyPart encrypted = (MimeBodyPart) ((MimeMultipart) source.getContent()).getBodyPart(1);
        try (InputStream available = encrypted.getRawInputStream()) {
            assertThat(new String(available.readAllBytes(), StandardCharsets.US_ASCII)).contains("AA=");
        }

        final Email parsed = SimpleJavaMail.fromDefaults().converter().fromMimeMessage(source)
                .withOpenPgpReceiveConfig(receiving()).toEmail();

        assertThat(parsed.getInspection().getOriginalMessage()).isNull();
        assertThat(parsed.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.FAILED);
        try (InputStream stillAvailable = encrypted.getRawInputStream()) {
            assertThat(new String(stillAvailable.readAllBytes(), StandardCharsets.US_ASCII)).contains("AA=");
        }
        assertThat(allParts(parsed.getSourceData()).filter(part -> part.getContent() != null)).anySatisfy(part -> {
            assertThat(part.getContent().getRepresentation()).isEqualTo(EmailSourceContent.Representation.SOURCE_BYTES);
            assertThat(part.getContent().isFullyCaptured()).isTrue();
            assertThat(new String(part.getContent().readAllBytes(), StandardCharsets.US_ASCII)).contains("AA=");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"base64", "quoted-printable"})
    void decodedPayloadsRetainMissingKeyResults(final String encoding) throws Exception {
        final OpenPgpReceiveConfig noKeys = OpenPgpReceiveConfig.builder().build();
        final Email signed = read(withTransferEncoding(resource("signed-mixed.eml"), encoding), noKeys, ImportRoute.FLUENT_EML);
        final Email encrypted = read(withTransferEncoding(resource("encrypted-mixed.eml"), encoding), noKeys, ImportRoute.FLUENT_EML);
        assertThat(signed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNVERIFIED);
        assertThat(encrypted.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE);
        assertThat(encrypted.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
    }

    @Test
    void decodingDoesNotTurnAnInvalidSignatureOrWrongPassphraseIntoSuccess() throws Exception {
        final byte[] tampered = new String(resource("signed-mixed.eml"), StandardCharsets.US_ASCII)
                .replace("H=C3=A9llo", "J=C3=A9llo").getBytes(StandardCharsets.US_ASCII);
        final Email invalidSignature = read(withTransferEncoding(tampered, "base64"), receiving(), ImportRoute.FLUENT_EML);
        assertThat(invalidSignature.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);

        final OpenPgpReceiveConfig wrongPassphrase = OpenPgpReceiveConfig.builder()
                .addDecryptionKeyRing(resource("private-key.asc"), "wrong-passphrase").build();
        assertThatThrownBy(() -> read(withTransferEncoding(resource("encrypted-mixed.eml"), "quoted-printable"),
                wrongPassphrase, ImportRoute.FLUENT_EML)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("could not be unlocked");
    }

    @Test
    void conversionLeavesCallerOwnedStreamsOpenForValidAndFailedProtection() throws Exception {
        for (String fixture : new String[] {"signed-mixed.eml", "encrypted-mixed.eml"}) {
            final byte[] source = resource(fixture);
            for (boolean valid : new boolean[] {true, false}) {
                final byte[] incoming = valid ? withTransferEncoding(source, "base64")
                        : replacePayload(source, "unsupported", new byte[] {1, 2, 3});
                try (TrackingInputStream caller = new TrackingInputStream(incoming)) {
                    final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(caller)
                            .withOpenPgpReceiveConfig(receiving()).toEmail();
                    assertThat(parsed.getInspection().getOriginalMessage().readAllBytes()).containsExactly(incoming);
                    if (!valid) {
                        assertThat(parsed.getProblems()).isNotEmpty();
                        if (fixture.startsWith("encrypted")) {
                            assertThat(parsed.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.FAILED);
                            assertThat(parsed.getPlainText()).isNull();
                        } else {
                            assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
                            assertReadableFixture(parsed);
                        }
                    }
                    assertThat(caller.closed).isFalse();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-headers", "raw-binary"})
    void headerlessProtectionPartsRetainIdentityInterpretation(final String form) throws Exception {
        for (String fixture : new String[] {"signed-mixed.eml", "encrypted-mixed.eml"}) {
            final ProtectionPart part = protectionPart(resource(fixture));
            final int headerStart = part.message.indexOf("\r\n", part.secondDelimiter + 2) + 2;
            byte[] payload = part.message.substring(part.payloadStart, part.payloadEnd).getBytes(StandardCharsets.ISO_8859_1);
            if (form.equals("raw-binary")) {
                try (InputStream binary = PGPUtil.getDecoderStream(new ByteArrayInputStream(payload))) {
                    payload = binary.readAllBytes();
                }
                assertThat(new String(payload, StandardCharsets.ISO_8859_1)).doesNotContain("\r\n\r\n", "\n\n");
            }
            final byte[] incoming = (part.message.substring(0, headerStart) + (form.equals("empty-headers") ? "\r\n" : "")
                    + new String(payload, StandardCharsets.ISO_8859_1) + part.message.substring(part.payloadEnd))
                    .getBytes(StandardCharsets.ISO_8859_1);
            final Email parsed = read(incoming, receiving(), ImportRoute.FLUENT_EML);
            if (fixture.startsWith("signed")) {
                assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
            } else {
                assertThat(parsed.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
            }
            assertThat(parsed.getInspection().getOriginalMessage().readAllBytes()).containsExactly(incoming);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"signed-mixed.eml", "encrypted-mixed.eml"})
    void unavailableModuleStillReportsUninspectedEncodedProtection(final String fixture) throws Exception {
        final byte[] incoming = withTransferEncoding(resource(fixture), "base64");
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
            final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(incoming).toEmail();
            assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
            assertThat(parsed.getInspection().getOriginalMessage().readAllBytes()).containsExactly(incoming);
            if (fixture.startsWith("encrypted")) {
                assertThat(parsed.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE);
            }
        }
    }

    private static void assertOriginalForRoute(final Email email, final byte[] input, final ImportRoute route) {
        if (route == ImportRoute.FLUENT_EML || route == ImportRoute.STATIC_EML) {
            assertThat(email.getInspection().getOriginalMessage()).isNotNull();
            assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(input);
        } else {
            assertThat(email.getInspection().getOriginalMessage()).isNull();
        }
    }

    private static void assertReadableFixture(final Email parsed) {
        assertThat(parsed.getPlainText()).isEqualTo("Héllo from OpenPGP.js");
        assertThat(parsed.getAttachments()).singleElement().satisfies(attachment ->
                assertThat(attachment.readAllData()).isEqualTo("independent fixture"));
    }

    private static Stream<EmailSourcePart> allParts(final EmailSourceData source) {
        return Stream.concat(source.getParts().stream().flatMap(OpenPgpTransferEncodingTest::partAndChildren),
                source.getDerivedSources().stream().flatMap(OpenPgpTransferEncodingTest::allParts));
    }

    private static Stream<EmailSourcePart> partAndChildren(final EmailSourcePart part) {
        return Stream.concat(Stream.of(part), part.getChildren().stream().flatMap(OpenPgpTransferEncodingTest::partAndChildren));
    }

    private static Email read(final byte[] incoming, final OpenPgpReceiveConfig keys, final ImportRoute route) throws Exception {
        switch (route) {
            case FLUENT_EML:
                return SimpleJavaMail.fromDefaults().converter().fromEml(incoming).withOpenPgpReceiveConfig(keys).toEmail();
            case STATIC_EML:
                return EmailConverter.emlToEmailWithOpenPgp(new ByteArrayInputStream(incoming), keys);
            case FLUENT_MIME:
                return SimpleJavaMail.fromDefaults().converter().fromMimeMessage(
                        new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(incoming)))
                        .withOpenPgpReceiveConfig(keys).toEmail();
            case STATIC_MIME:
                return EmailConverter.mimeMessageToEmail(
                        new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(incoming)), null, keys);
            default:
                throw new AssertionError(route);
        }
    }

    private static OpenPgpReceiveConfig receiving() throws Exception {
        return OpenPgpReceiveConfig.builder().addVerificationKeyRing(resource("public-key.asc"))
                .addDecryptionKeyRing(resource("private-key.asc"), PASSPHRASE).build();
    }

    private static byte[] resource(final String name) throws Exception {
        try (InputStream input = OpenPgpTransferEncodingTest.class.getClassLoader().getResourceAsStream("openpgpjs/" + name)) {
            if (input == null) throw new AssertionError("Missing fixture: " + name);
            return input.readAllBytes();
        }
    }

    private static byte[] withTransferEncoding(final byte[] source, final String encoding) throws Exception {
        return withTransferEncoding(source, encoding, encoding);
    }

    private static byte[] withTransferEncoding(final byte[] source, final String encoding, final String headerValue) throws Exception {
        final ProtectionPart part = protectionPart(source);
        final byte[] payload = part.message.substring(part.payloadStart, part.payloadEnd).getBytes(StandardCharsets.ISO_8859_1);
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (OutputStream output = MimeUtility.encode(encoded, encoding)) {
            output.write(payload);
        }
        return replacePayload(source, headerValue, encoded.toByteArray());
    }

    private static byte[] replacePayload(final byte[] source, final String encoding, final byte[] payload) throws Exception {
        final ProtectionPart part = protectionPart(source);
        return (part.message.substring(0, part.payloadStart - 4) + "\r\nContent-Transfer-Encoding: " + encoding + "\r\n\r\n"
                + new String(payload, StandardCharsets.ISO_8859_1) + part.message.substring(part.payloadEnd))
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] firstEntity(final byte[] source) throws Exception {
        final ProtectionPart part = protectionPart(source);
        return part.message.substring(part.firstEntityStart, part.secondDelimiter).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static ProtectionPart protectionPart(final byte[] source) throws Exception {
        final MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source));
        final String boundary = new ContentType(mime.getContentType()).getParameter("boundary");
        final String message = new String(source, StandardCharsets.ISO_8859_1);
        final String marker = "\r\n--" + boundary;
        final int firstEntityStart = message.indexOf("--" + boundary + "\r\n") + boundary.length() + 4;
        final int secondDelimiter = message.indexOf(marker + "\r\n", firstEntityStart);
        final int payloadStart = message.indexOf("\r\n\r\n", secondDelimiter + marker.length() + 2) + 4;
        final int payloadEnd = message.indexOf(marker + "--", payloadStart);
        assertThat(secondDelimiter).isGreaterThan(firstEntityStart);
        assertThat(payloadEnd).isGreaterThan(payloadStart);
        return new ProtectionPart(message, firstEntityStart, secondDelimiter, payloadStart, payloadEnd);
    }

    private static final class ProtectionPart {
        private final String message;
        private final int firstEntityStart;
        private final int secondDelimiter;
        private final int payloadStart;
        private final int payloadEnd;

        private ProtectionPart(final String message, final int firstEntityStart, final int secondDelimiter,
                final int payloadStart, final int payloadEnd) {
            this.message = message;
            this.firstEntityStart = firstEntityStart;
            this.secondDelimiter = secondDelimiter;
            this.payloadStart = payloadStart;
            this.payloadEnd = payloadEnd;
        }
    }

    private enum ImportRoute { FLUENT_EML, STATIC_EML, FLUENT_MIME, STATIC_MIME }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;
        private TrackingInputStream(final byte[] input) { super(input); }
        @Override public void close() throws java.io.IOException { closed = true; super.close(); }
    }
}
