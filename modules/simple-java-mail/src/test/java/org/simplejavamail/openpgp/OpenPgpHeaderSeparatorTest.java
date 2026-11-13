package org.simplejavamail.openpgp;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.openpgpsupport.OpenPgpSupport;
import org.simplejavamail.internal.util.FinalizedMimeMessage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenPgpHeaderSeparatorTest {

    private static OpenPgpReceiveConfig receiving;

    @BeforeAll
    static void prepareReceivingKeys() throws Exception {
        receiving = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(resource("public-key.asc"))
                .addDecryptionKeyRing(resource("private-key.asc"), "openpgpjs-fixture-passphrase")
                .build();
    }

    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("protectedImports")
    void firstOuterSeparatorKeepsProtectedContentReadable(final String fixture, final LineEndings endings,
            final ImportRoute route) throws Exception {
        final byte[] incoming = endings.apply(resource(fixture));
        // Jakarta Mail already recognizes these header forms; protection inspection must split the same envelope.
        final MimeMessage parsedMime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(incoming));
        assertThat(parsedMime.isMimeType(fixture.startsWith("signed") ? "multipart/signed" : "multipart/encrypted")).isTrue();

        final Email imported = route.read(incoming);

        if (fixture.startsWith("signed")) {
            assertThat(imported.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        } else {
            assertThat(imported.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        }
        assertReadableFixture(imported);
        assertThat(imported.getInspection().getOriginalMessage().readAllBytes()).containsExactly(incoming);
    }

    private static Stream<Arguments> protectedImports() {
        // Both fluent routes cover every separator; the static stream route retains CRLF and all-LF for each payload.
        return Stream.of("signed-mixed.eml", "encrypted-mixed.eml")
                .flatMap(fixture -> Arrays.stream(LineEndings.values())
                        .flatMap(endings -> Arrays.stream(ImportRoute.values())
                                .filter(route -> route != ImportRoute.STATIC_STREAM || endings == LineEndings.CRLF || endings == LineEndings.ALL_LF)
                                .map(route -> Arguments.of(fixture, endings, route))));
    }

    @Test
    void nestedSignedMessageKeepsItsContentWhenEncryptingAnLfOuterEnvelope() throws Exception {
        final byte[] signed = LineEndings.LF_OUTER.apply(resource("signed-mixed.eml"));
        final Session session = Session.getInstance(new Properties());
        final MimeMessage source = FinalizedMimeMessage.fromMessageBytes(session, signed,
                FinalizedMimeMessage.ProtectionState.NONE);
        final MimeMessage encrypted = new OpenPgpSupport().encryptMessage(session,
                SimpleJavaMail.fromDefaults().emailBuilder().startingBlank().buildEmail(), source,
                OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(resource("public-key.asc")).build());
        final byte[] incoming = EmailConverter.mimeMessageToEMLByteArray(encrypted);

        final Email imported = ImportRoute.FLUENT_BYTES.read(incoming);
        assertThat(imported.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(imported.getInspection().getOpenPgp().getDecryptions().get(0).getStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        assertThat(imported.getInspection().getOriginalMessage().readAllBytes()).containsExactly(incoming);
        assertReadableFixture(imported);
    }

    @Test
    void toleratingImportedLineEndingsDoesNotRelaxExactSubmission() throws Exception {
        final byte[] incoming = LineEndings.LF_OUTER.apply(resource("signed-mixed.eml"));
        assertThatThrownBy(() -> SimpleJavaMail.fromDefaults().emailBuilder().startingFromExactEml(incoming)
                .withEnvelopeRecipients("receiver@example.org").buildEmail())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CRLF");
    }

    private static void assertReadableFixture(final Email imported) {
        assertThat(imported.getPlainText()).isEqualTo("Héllo from OpenPGP.js");
        assertThat(imported.getAttachments()).singleElement().satisfies(attachment ->
                assertThat(attachment.readAllData()).isEqualTo("independent fixture"));
    }

    private static byte[] resource(final String name) throws Exception {
        try (InputStream input = OpenPgpHeaderSeparatorTest.class.getClassLoader().getResourceAsStream("openpgpjs/" + name)) {
            if (input == null) throw new AssertionError("Missing fixture: " + name);
            return input.readAllBytes();
        }
    }

    private enum ImportRoute {
        FLUENT_BYTES, FLUENT_STREAM, STATIC_STREAM;

        Email read(final byte[] incoming) {
            switch (this) {
                case FLUENT_BYTES:
                    return SimpleJavaMail.fromDefaults().converter().fromEml(incoming)
                            .withOpenPgpReceiveConfig(receiving).toEmail();
                case FLUENT_STREAM:
                    return SimpleJavaMail.fromDefaults().converter().fromEml(new ByteArrayInputStream(incoming))
                            .withOpenPgpReceiveConfig(receiving).toEmail();
                case STATIC_STREAM:
                    return EmailConverter.emlToEmailWithOpenPgp(new ByteArrayInputStream(incoming), receiving);
                default:
                    throw new AssertionError(this);
            }
        }
    }

    private enum LineEndings {
        CRLF, LF_OUTER, CRLF_THEN_LF, LF_THEN_CRLF, MIXED_HEADERS, ALL_LF;

        byte[] apply(final byte[] source) {
            final String message = new String(source, ISO_8859_1);
            final int separator = message.indexOf("\r\n\r\n");
            assertThat(separator).isPositive();
            final String headers = message.substring(0, separator);
            final String body = message.substring(separator + 4);
            switch (this) {
                case CRLF:
                    return source.clone();
                case LF_OUTER:
                    return (headers.replace("\r\n", "\n") + "\n\n" + body).getBytes(ISO_8859_1);
                case CRLF_THEN_LF:
                    return (headers + "\r\n\n" + body).getBytes(ISO_8859_1);
                case LF_THEN_CRLF:
                    return (headers.replace("\r\n", "\n") + "\n\r\n" + body).getBytes(ISO_8859_1);
                case MIXED_HEADERS:
                    return (headers.replaceFirst("\r\n", "\n") + "\n\n" + body).getBytes(ISO_8859_1);
                case ALL_LF:
                    return message.replace("\r\n", "\n").getBytes(ISO_8859_1);
                default:
                    throw new AssertionError(this);
            }
        }
    }
}
