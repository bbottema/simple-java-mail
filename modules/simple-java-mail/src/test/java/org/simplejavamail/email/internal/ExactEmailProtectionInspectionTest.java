package org.simplejavamail.email.internal;

import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.ContentType;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.mail.smime.SMIMESignedGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.smimesupport.SMIMESupport;
import org.simplejavamail.internal.smimesupport.SmimePreprocessingResultImpl;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import testutil.smtp.RecordingSmtpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Properties;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class ExactEmailProtectionInspectionTest {

    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void encryptedSmimeKeepsMissingKeyAndUnknownVerificationWithoutChangingTheRawView() throws Exception {
        final byte[] source = resource("pkcs12/test messages/S_MIME test message encrypted.eml");

        final Email exact = exact(source);

        assertThat(exact.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.ENCRYPTED);
        assertThat(exact.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.KEY_MISSING);
        assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.UNKNOWN);
        assertThat(exact.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
        assertThat(exact.getPlainText()).isNull();
        assertThat(exact.getSmimeSignedEmail()).isNull();
        assertThat(exact.getDecryptedAttachments()).isEmpty();
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
    }

    @Test
    void selfContainedSmimeSignatureRetainsEstablishedVerification() throws Exception {
        final byte[] source = signedSmime();

        final Email exact = exact(source);

        assertThat(exact.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.SIGNED);
        assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(exact.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED);
        assertThat(exact.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
        assertThat(exact.getPlainText()).contains("For testing purposes");
        assertThat(exact.getSmimeSignedEmail()).isNull();
        assertThat(exact.getDecryptedAttachments()).isEmpty();
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
    }

    @Test
    void openPgpSignatureKeepsMissingVerificationKeyInsteadOfClaimingNoSignature() throws Exception {
        final byte[] source = resource("openpgpjs/signed-mixed.eml");

        final Email exact = exact(source);

        assertThat(exact.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.SIGNED);
        assertThat(exact.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.KEY_MISSING);
        assertThat(exact.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.NOT_ENCRYPTED);
        assertThat(exact.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(source);
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
    }

    @Test
    void openPgpCiphertextKeepsMissingKeyAndUnknownInnerSignature() throws Exception {
        final byte[] source = resource("openpgpjs/encrypted-mixed.eml");

        final Email exact = exact(source);

        assertThat(exact.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.ENCRYPTED);
        assertThat(exact.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.KEY_MISSING);
        assertThat(exact.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.UNKNOWN);
        assertThat(exact.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(source);
        assertThat(exact.getPlainText()).isNull();
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
    }

    @Test
    void tamperedSmimeSignatureKeepsAnEstablishedInvalidResult() throws Exception {
        final String original = new String(signedSmime(), ISO_8859_1);
        final String tampered = original.replace("For testing purposes", "For altered purposes");
        assertThat(tampered).isNotEqualTo(original);
        final byte[] source = tampered.getBytes(ISO_8859_1);

        final Email exact = exact(source);

        assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.INVALID);
        assertThat(exact.getOriginalSmimeDetails().getSmimeSignatureValid()).isFalse();
        assertThat(exact.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
    }

    @Test
    void malformedOpenPgpEnvelopeKeepsAnActualFailureInsteadOfBlanketUnknown() {
        final byte[] source = ("Content-Type: multipart/encrypted; protocol=\"application/pgp-encrypted\"; boundary=one\r\n\r\n"
                + "--one\r\nContent-Type: application/pgp-encrypted\r\n\r\nVersion: 1\r\n--one--\r\n").getBytes(US_ASCII);

        final Email exact = exact(source);

        assertThat(exact.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.ENCRYPTED);
        assertThat(exact.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.FAILED);
        assertThat(exact.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.UNKNOWN);
        assertThat(exact.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(source);
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
    }

    @Test
    void plainExactInputDoesNotInitializeOptionalProtectionHandlers() {
        final byte[] source = "Subject: Plain\r\nContent-Type: text/plain\r\n\r\nPlain content\r\n".getBytes(US_ASCII);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            final Email exact = exact(source);

            assertThat(exact.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.PLAIN);
            assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.NOT_SIGNED);
            assertThat(exact.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.PLAIN);
            assertThat(exact.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.NOT_PRESENT);
            modules.verifyNoInteractions();
        }
    }

    @Test
    void unrelatedEncryptedAttachmentIsNotInspectedOrDecrypted() {
        final byte[] source = ("Subject: Ordinary multipart\r\nContent-Type: multipart/mixed; boundary=plain\r\n\r\n"
                + "--plain\r\nContent-Type: text/plain\r\n\r\nVisible body\r\n"
                + "--plain\r\nContent-Type: application/pkcs7-mime; smime-type=enveloped-data; name=sealed.p7m\r\n"
                + "Content-Disposition: attachment; filename=sealed.p7m\r\nContent-Transfer-Encoding: base64\r\n\r\nAQID\r\n"
                + "--plain--\r\n").getBytes(US_ASCII);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            final Email exact = exact(source);

            assertThat(exact.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.PLAIN);
            assertThat(exact.getDecryptedAttachments()).isEmpty();
            assertThat(exact.getAttachments()).singleElement().satisfies(attachment ->
                    assertThat(attachment.readAllBytes()).containsExactly(1, 2, 3));
            assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
            modules.verifyNoInteractions();
        }
    }

    @Test
    void requiredRawContentFailureIsNotSwallowedAsUnavailableInspection() {
        final byte[] source = ("Content-Type: application/octet-stream\r\nContent-Transfer-Encoding: unsupported-exact-encoding\r\n"
                + "Content-Disposition: attachment; filename=required.bin\r\n\r\nRequired content\r\n").getBytes(US_ASCII);

        assertThatThrownBy(() -> exact(source)).hasMessageContaining("Unable to parse exact EML");
    }

    @Test
    void malformedProtectionClassificationRemainsUnknownWhenRawContentCanBeParsed() {
        final byte[] source = ("Subject: Unclassified\r\nContent-Type: application/x-unclassified; missing-value\r\n\r\n"
                + "Readable source bytes\r\n").getBytes(US_ASCII);

        final Email exact = exact(source);

        assertThat(exact.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.UNKNOWN);
        assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.UNKNOWN);
        assertThat(exact.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.UNKNOWN);
        assertThat(exact.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.UNKNOWN);
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
    }

    @Test
    void constructorAndGettersRemainPassiveWhileEachBuildInspectsItsSnapshot() throws Exception {
        final byte[] source = signedSmime();
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            final ExactEmailBuilder builder = MAIL.emailBuilder().startingFromExactEml(source)
                    .withEnvelopeRecipients("receiver@example.org");
            assertThat(builder.getDeliveryStatusReport()).isNull();
            modules.verify(ModuleLoader::loadSmimeModule, never());

            final Email first = builder.buildEmail();
            assertThat(first.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
            first.getOriginalSmimeDetails().getSmimeSignatureValid();
            first.getOriginalSmimeDetails().getOriginalProtectedMessage();
            modules.verify(ModuleLoader::loadSmimeModule, times(1));

            assertThat(builder.buildEmail().getOriginalSmimeDetails().getVerificationStatus())
                    .isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
            modules.verify(ModuleLoader::loadSmimeModule, times(2));
        }
    }

    @Test
    void unexpectedHandlerFailureIsNotSwallowedAsAnExactMimeParsingFailure() throws Exception {
        final ExactEmailBuilder builder = MAIL.emailBuilder().startingFromExactEml(signedSmime())
                .withEnvelopeRecipients("receiver@example.org");
        final IllegalStateException failure = new IllegalStateException("broken protection handler");
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::smimeModuleAvailable).thenReturn(true);
            modules.when(ModuleLoader::loadSmimeModule).thenThrow(failure);

            assertThatThrownBy(builder::buildEmail).isSameAs(failure);
        }
    }

    @ParameterizedTest
    @EnumSource(Source.class)
    void sourceOwnershipRepeatedBuildCopyAndSerializationKeepFactsAndAuthority(final Source source,
            @TempDir final Path directory) throws Exception {
        final byte[] original = signedSmime();
        final byte[] supplied = original.clone();
        final TrackingInput input = new TrackingInput(supplied);
        final Path path = Files.write(directory.resolve("exact.eml"), supplied);
        final ExactEmailBuilder builder;
        switch (source) {
            case BYTES: builder = MAIL.emailBuilder().startingFromExactEml(supplied); break;
            case STREAM: builder = MAIL.emailBuilder().startingFromExactEml(input); break;
            case PATH: builder = MAIL.emailBuilder().startingFromExactEml(path); break;
            default: throw new AssertionError(source);
        }
        supplied[0] ^= 1;
        Files.delete(path);
        if (source == Source.STREAM) {
            assertThat(input.available()).isZero();
            assertThat(input.closed).isFalse();
        }
        builder.withEnvelopeRecipients("receiver@example.org");
        final Email first = builder.buildEmail();
        final byte[] exposed = first.getOriginalSmimeDetails().getOriginalProtectedMessage();
        exposed[0] ^= 1;
        final Email second = builder.buildEmail();
        final Email restored = roundTrip(first);
        final Email copied = MAIL.emailBuilder().copying(first).buildEmail();
        for (Email snapshot : new Email[] {first, second, restored, copied}) {
            assertThat(snapshot.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
            assertThat(snapshot.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(original);
        }
        for (Email exact : new Email[] {first, second, restored}) {
            assertThat(((InternalEmail) exact).isExactEml()).isTrue();
            assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(original);
        }
        assertThat(((InternalEmail) copied).isExactEml()).isFalse();
    }

    @Test
    void protectedFactsCannotChangeExactExportRehearsalOrActualSubmission() throws Exception {
        try (RecordingSmtpServer server = new RecordingSmtpServer();
                Mailer mailer = MAIL.mailerBuilder().withSMTPServer("localhost", server.port())
                        .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("probe.example.test").build()).buildMailer()) {
            final byte[][] sources = {signedSmime(), resource("pkcs12/test messages/S_MIME test message encrypted.eml"),
                    resource("openpgpjs/signed-mixed.eml"), resource("openpgpjs/encrypted-mixed.eml")};
            for (byte[] source : sources) {
                final Email exact = MAIL.emailBuilder().startingFromExactEml(source)
                        .withEnvelopeRecipients("receiver@example.org").withEnvelopeSender("sender@example.org").buildEmail();
                assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
                assertThat(mailer.rehearse(exact, false).getEmlBytes()).containsExactly(source);
                assertThat(mailer.rehearse(exact, true).getEmlBytes()).containsExactly(source);
                mailer.sync().sendMail(exact);
            }
            assertThat(server.messages()).hasSize(sources.length);
            for (int i = 0; i < sources.length; i++) {
                assertThat(server.messages().get(i)).containsExactly(sources[i]);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedSmimeWrapperKeepsUnopenedCiphertextUnknown(final boolean tampered) throws Exception {
        final MimeBodyPart ciphertext = new MimeBodyPart(new ByteArrayInputStream(
                resource("pkcs12/test messages/S_MIME test message encrypted.eml")));
        ciphertext.setHeader("X-Inner-Marker", "original");
        final byte[] signed = sign(ciphertext, false);
        final String wire = new String(signed, ISO_8859_1);
        assertThat(wire).contains("X-Inner-Marker: original");
        final byte[] source = tampered ? wire.replace("X-Inner-Marker: original", "X-Inner-Marker: changed").getBytes(ISO_8859_1) : signed;

        final Email exact = exact(source);

        assertThat(exact.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.SIGNED_ENCRYPTED);
        assertThat(exact.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.UNKNOWN);
        assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(tampered
                ? OriginalSmimeDetails.VerificationStatus.INVALID : OriginalSmimeDetails.VerificationStatus.UNKNOWN);
        assertThat(exact.getOriginalSmimeDetails().getSmimeSignedBy()).isNotEmpty();
        assertThat(exact.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
        assertThat(exact.getPlainText()).isNull();
        assertThat(exact.getSmimeSignedEmail()).isNull();
        assertThat(exact.getDecryptedAttachments()).isEmpty();
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);

        // The exact facts-only hook does not broaden ordinary conversion's existing inspection scope.
        final Email ordinary = MAIL.converter().fromEml(source).toEmail();
        assertThat(ordinary.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(tampered
                ? OriginalSmimeDetails.VerificationStatus.INVALID : OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(ordinary.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED);
    }

    @Test
    void opaqueSignatureInspectionDoesNotReplaceTheRawCiphertextAttachmentView() throws Exception {
        final AtomicInteger imageRequests = new AtomicInteger();
        final HttpServer images = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        images.createContext("/inspection.png", exchange -> {
            imageRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, 3);
            try (var response = exchange.getResponseBody()) { response.write(new byte[] {1, 2, 3}); }
        });
        images.start();
        try {
            final String html = "<p>Opaque content is inspection-only</p><img src=\"http://127.0.0.1:"
                    + images.getAddress().getPort() + "/inspection.png\">";
            final MimeBodyPart clear = new MimeBodyPart();
            clear.setContent(html, "text/html; charset=UTF-8");
            final byte[] source = sign(clear, true);
            final Properties settings = new Properties();
            settings.setProperty("simplejavamail.embeddedimages.dynamicresolution.enable.url", "true");
            final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(settings).load());

            final Email exact = configured.emailBuilder().startingFromExactEml(source)
                    .withEnvelopeRecipients("receiver@example.org").buildEmail();

            assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
            assertThat(exact.getPlainText()).isNull();
            assertThat(exact.getHTMLText()).isNull();
            assertThat(exact.getAttachments()).hasSize(1);
            assertThat(exact.getSmimeSignedEmail()).isNull();
            assertThat(exact.getDecryptedAttachments()).isEmpty();
            assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
            assertThat(imageRequests).hasValue(0);
            assertThat(MAIL.converter().fromEml(source).toEmail().getHTMLText()).contains("Opaque content is inspection-only");
        } finally {
            images.stop(0);
        }
    }

    @Test
    void signedInnerDeclarationCannotPretendToBeEncryption() throws Exception {
        final MimeBodyPart signedInner = new MimeBodyPart(new ByteArrayInputStream(signedSmime()));
        final ContentType type = new ContentType(signedInner.getContentType());
        type.setParameter("smime-type", "enveloped-data");
        signedInner.setHeader("Content-Type", type.toString());

        final Email exact = exact(sign(signedInner, false));

        assertThat(exact.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.SIGNED);
        assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(exact.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED);
    }

    @Test
    void ambiguousPkcs7DeclarationCanStillUseAvailableFeatureInspection() throws Exception {
        final MimeBodyPart clear = new MimeBodyPart();
        clear.setText("A self-contained opaque signature", "UTF-8");
        final MimeMessage envelope = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(sign(clear, true)));
        final ContentType type = new ContentType(envelope.getContentType());
        type.getParameterList().remove("smime-type");
        envelope.setHeader("Content-Type", type.toString());
        final byte[] source = EmailConverter.mimeMessageToEMLByteArray(envelope);

        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            final Email exact = exact(source);

            modules.verify(ModuleLoader::loadSmimeModule, times(1));
            assertThat(exact.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.UNKNOWN);
            assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.UNKNOWN);
            assertThat(exact.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.UNKNOWN);
            assertThat(exact.getOriginalSmimeDetails().getFailureReason()).contains("did not establish");
            assertThat(exact.getPlainText()).isNull();
            assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
        }
    }

    @Test
    void establishedVerificationErrorOutranksUnopenedInnerCiphertext() throws Exception {
        final MimeBodyPart ciphertext = new MimeBodyPart(new ByteArrayInputStream(
                resource("pkcs12/test messages/S_MIME test message encrypted.eml")));
        final byte[] source = sign(ciphertext, false, false);

        final Email exact = exact(source);

        assertThat(exact.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.SIGNED_ENCRYPTED);
        assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.ERROR);
        assertThat(exact.getOriginalSmimeDetails().getSmimeSignatureValid()).isFalse();
        assertThat(exact.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.UNKNOWN);
        assertThat(exact.getOriginalSmimeDetails().getFailureReason()).contains("Unable to verify");
        assertThat(exact.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(source);
    }

    @ParameterizedTest
    @EnumSource(EstablishedResult.class)
    void ambiguousDeclarationCannotEraseAnEstablishedFeatureResult(final EstablishedResult result) {
        final byte[] source = ("Content-Type: application/pkcs7-mime; smime-type=unsupported\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n").getBytes(US_ASCII);
        final OriginalSmimeDetails facts = OriginalSmimeDetailsImpl.builder()
                .smimeMode(result == EstablishedResult.DECRYPTION_FAILED ? OriginalSmimeDetails.SmimeMode.ENCRYPTED : OriginalSmimeDetails.SmimeMode.SIGNED)
                .verificationStatus(result.verification).decryptionStatus(result.decryption)
                .failureReason(result == EstablishedResult.VERIFICATION_VALID ? null : "An established feature failure")
                .originalProtectedMessage(source).build();
        final SMIMESupport support = spy(new SMIMESupport());
        doAnswer(invocation -> SmimePreprocessingResultImpl.builder().recognized(true)
                .effectiveMimeMessage(invocation.getArgument(1)).details(facts)
                .protectedAttachments(List.of()).decryptedArtifacts(List.of()).build())
                .when(support).processIncoming(any(Session.class), any(MimeMessage.class), isNull());
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::smimeModuleAvailable).thenReturn(true);
            modules.when(ModuleLoader::loadSmimeModule).thenReturn(support);

            final Email exact = exact(source);

            assertThat(exact.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(result.verification);
            assertThat(exact.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(result.decryption);
            assertThat(exact.getOriginalSmimeDetails().getFailureReason()).isEqualTo(facts.getFailureReason());
            assertThat(exact.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(source);
        }
    }

    private enum EstablishedResult {
        VERIFICATION_VALID(OriginalSmimeDetails.VerificationStatus.VALID, OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED),
        VERIFICATION_INVALID(OriginalSmimeDetails.VerificationStatus.INVALID, OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED),
        VERIFICATION_ERROR(OriginalSmimeDetails.VerificationStatus.ERROR, OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED),
        DECRYPTION_FAILED(OriginalSmimeDetails.VerificationStatus.UNKNOWN, OriginalSmimeDetails.DecryptionStatus.FAILED);

        private final OriginalSmimeDetails.VerificationStatus verification;
        private final OriginalSmimeDetails.DecryptionStatus decryption;

        EstablishedResult(final OriginalSmimeDetails.VerificationStatus verification, final OriginalSmimeDetails.DecryptionStatus decryption) {
            this.verification = verification;
            this.decryption = decryption;
        }
    }

    private static byte[] sign(final MimeBodyPart content, final boolean opaque) throws Exception {
        return sign(content, opaque, true);
    }

    private static byte[] sign(final MimeBodyPart content, final boolean opaque, final boolean embedCertificate) throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        final SMIMESignedGenerator generator = new SMIMESignedGenerator();
        if (embedCertificate) generator.addCertificates(new JcaCertStore(List.of(certificate)));
        generator.addSignerInfoGenerator(new JcaSimpleSignerInfoGeneratorBuilder().setProvider(new BouncyCastleProvider())
                .build("SHA256withRSA", (PrivateKey) store.getKey(keys.getKeyAlias(), keys.getKeyPassword()), certificate));
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setSubject("Read-only protected inspection");
        if (opaque) {
            final MimeBodyPart encapsulated = generator.generateEncapsulated(content);
            final byte[] cms;
            try (InputStream input = encapsulated.getInputStream()) { cms = input.readAllBytes(); }
            // Produce canonical outer MIME framing without changing the signed CMS entity.
            return ("From: sender@example.org\r\nSubject: Read-only protected inspection\r\nMIME-Version: 1.0\r\n"
                    + "Content-Type: " + encapsulated.getContentType() + "\r\nContent-Transfer-Encoding: base64\r\n\r\n"
                    + Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encodeToString(cms) + "\r\n").getBytes(ISO_8859_1);
        } else {
            message.setContent(generator.generate(content));
        }
        message.saveChanges();
        return EmailConverter.mimeMessageToEMLByteArray(message);
    }

    private enum Source { BYTES, STREAM, PATH }

    private static final class TrackingInput extends ByteArrayInputStream {
        private boolean closed;
        private TrackingInput(final byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }

    private static Email exact(final byte[] source) {
        return MAIL.emailBuilder().startingFromExactEml(source)
                .withEnvelopeRecipients("receiver@example.org").buildEmail();
    }

    private static byte[] signedSmime() throws Exception {
        return resource("pkcs12/test messages/S_MIME test message signed.eml");
    }

    private static byte[] resource(final String path) throws Exception {
        try (InputStream input = ExactEmailProtectionInspectionTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new AssertionError("Missing fixture: " + path);
            return input.readAllBytes();
        }
    }
}
