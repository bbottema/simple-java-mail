package org.simplejavamail.openpgp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.OpenPgpSignatureResult;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.DecryptionStatus;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Base64;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class OpenPgpSignatureResultsTest {
    @BeforeAll
    static void fixtures() throws Exception { OpenPgpSignatureFixtures.initialize(); }

    @Test
    void combinedPacketsUseTheSuppliedVerificationKey() throws Exception {
        final byte[] incoming = OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first);
        final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(incoming)
                .withOpenPgpReceiveConfig(OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first)).toEmail();
        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatures()).singleElement().satisfies(result -> {
            assertThat(result.getStatus()).isEqualTo(SignatureStatus.VALID);
            assertThat(result.getSignerFingerprint()).isNotBlank();
        });
    }

    @Test
    void aValidSignatureCannotHideAnotherMissingVerificationKey() throws Exception {
        final byte[] incoming = OpenPgpSignatureFixtures.detached(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second);
        final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(incoming)
                .withOpenPgpReceiveConfig(OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first)).toEmail();
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.KEY_MISSING);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatures()).extracting(OpenPgpSignatureResult::getStatus)
                .containsExactlyInAnyOrder(SignatureStatus.VALID, SignatureStatus.KEY_MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"detached", "combined"})
    void everyOccurrenceIsRetainedForValidAndMissingKeys(final String format) throws Exception {
        final byte[] input = format.equals("detached") ? OpenPgpSignatureFixtures.detached(
                OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second, OpenPgpSignatureFixtures.first)
                : OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second, OpenPgpSignatureFixtures.first);
        for (boolean allKeys : new boolean[] {true, false}) {
            final OpenPgpReceiveConfig keys = allKeys ? OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second)
                    : OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first);
            for (ImportRoute route : ImportRoute.values()) {
                final Email parsed = read(input, keys, route);
                final OriginalOpenPgpDetails details = parsed.getOriginalOpenPgpDetails();
                assertThat(details.getSignatureStatus()).isEqualTo(allKeys ? SignatureStatus.VALID : SignatureStatus.KEY_MISSING);
                assertThat(details.getSignatures()).hasSize(3);
                assertThat(details.getSignatures()).filteredOn(result -> result.getStatus() == SignatureStatus.VALID).hasSize(allKeys ? 3 : 2);
                assertThat(details.getSignatures()).extracting(OpenPgpSignatureResult::getSignerKeyId).doesNotContainNull();
                assertMultipleShortcuts(details);
                assertThat(details.getOriginalProtectedMessage()).containsExactly(input);
                assertThat(parsed.getPlainText()).contains("Complete signature facts");
                if (format.equals("combined")) assertThat(details.getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"detached", "combined"})
    void invalidSignaturesDominateValidAndMissingResults(final String format) throws Exception {
        final byte[] input = format.equals("detached") ? OpenPgpSignatureFixtures.detachedWithInvalid(1,
                OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second)
                : OpenPgpSignatureFixtures.combinedWithInvalid(1, OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second);
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second), true);
        final OriginalOpenPgpDetails details = parsed.getOriginalOpenPgpDetails();
        assertThat(details.getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
        assertThat(details.getSignatures()).extracting(OpenPgpSignatureResult::getStatus)
                .containsExactlyInAnyOrder(SignatureStatus.VALID, SignatureStatus.INVALID);
        assertThat(details.getSignatures()).allSatisfy(signature -> assertThat(signature.getSignerFingerprint()).isNotBlank());
        assertMultipleShortcuts(details);
        assertThat(parsed.getPlainText()).contains("Complete signature facts");
        if (format.equals("combined")) assertThat(details.getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"detached", "combined"})
    void processingErrorsRetainEveryKnownOccurrenceAndEstablishedDecryption(final String format) throws Exception {
        final byte[] input = format.equals("detached") ? OpenPgpSignatureFixtures.detached(OpenPgpSignatureFixtures.first)
                : OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first);
        final OpenPgpReceiveConfig brokenVerificationKey = OpenPgpReceiveConfig.builder().addVerificationKeyRing(new byte[] {1, 2, 3})
                .addDecryptionKeyRing(OpenPgpSignatureFixtures.first.secret, OpenPgpSignatureFixtures.first.passphrase).build();
        final Email parsed = read(input, brokenVerificationKey, true);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.ERROR);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatures()).singleElement().satisfies(result -> {
            assertThat(result.getStatus()).isEqualTo(SignatureStatus.ERROR);
            assertThat(result.getSignerKeyId()).isNotNull();
            assertThat(result.getFailureReason()).isNotBlank();
        });
        if (format.equals("combined")) assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"packet-outside", "mime-outside"})
    void nestedMimeAndPacketSignaturesContributeToOneConservativeSummary(final String shape) throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.nested(shape.equals("packet-outside"),
                OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second);
        for (boolean allKeys : new boolean[] {true, false}) {
            final Email parsed = read(input, allKeys
                    ? OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second)
                    : OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
            final OriginalOpenPgpDetails details = parsed.getOriginalOpenPgpDetails();
            assertThat(details.getSignatures()).hasSize(3);
            assertThat(details.getSignatureStatus()).isEqualTo(allKeys ? SignatureStatus.VALID : SignatureStatus.KEY_MISSING);
            assertThat(details.getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
            assertThat(details.getOriginalProtectedMessage()).containsExactly(input);
            assertMultipleShortcuts(details);
        }
    }

    @Test
    void integrityFailureDoesNotBecomeKnownDecryptionOrSignatureAbsence() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.corruptIntegrity(OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first));
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        final OriginalOpenPgpDetails details = parsed.getOriginalOpenPgpDetails();
        assertThat(details.getDecryptionStatus()).isEqualTo(DecryptionStatus.FAILED);
        assertThat(details.getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(details.getSignatures()).isEmpty();
        assertThat(details.getOriginalProtectedMessage()).containsExactly(input);
    }

    @Test
    void anEscapingNativeSignatureErrorIsDistinctFromCiphertextFailure() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.combinedWithMalformedSignature();
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.ERROR);
        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.UNKNOWN);
        assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(input);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatures()).singleElement().satisfies(signature -> {
            assertThat(signature.getStatus()).isEqualTo(SignatureStatus.ERROR);
            assertThat(signature.getSignerKeyId()).isNotNull();
            assertThat(signature.getHashAlgorithm()).isEqualTo("SHA256");
        });
        final byte[] corrupted = OpenPgpSignatureFixtures.corruptIntegrity(input);
        final Email bothFailures = read(corrupted, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        assertThat(bothFailures.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.FAILED);
        assertThat(bothFailures.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(bothFailures.getOriginalOpenPgpDetails().getSignatures()).isEmpty();
        assertThat(bothFailures.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(corrupted);
    }

    @Test
    void completeUnsignedPacketsAreDistinctFromUnopenedOrUnavailablePackets() throws Exception {
        final Email unsigned = read(OpenPgpSignatureFixtures.combined(), OpenPgpSignatureFixtures.receiving(), true);
        assertThat(unsigned.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        assertThat(unsigned.getOriginalOpenPgpDetails().getSignatures()).isEmpty();
        assertThat(unsigned.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.NOT_PRESENT);
        final byte[] input = OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first);
        final Email unopened = read(input, OpenPgpReceiveConfig.builder().addVerificationKeyRing(OpenPgpSignatureFixtures.first.publicRing).build(), true);
        assertThat(unopened.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(unopened.getOriginalOpenPgpDetails().getSignatures()).isEmpty();
        assertThat(unopened.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.KEY_MISSING);
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
            final Email unavailable = SimpleJavaMail.fromDefaults().converter().fromEml(input).toEmail();
            assertThat(unavailable.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
            assertThat(unavailable.getOriginalOpenPgpDetails().getSignatures()).isEmpty();
        }
    }

    @Test
    void aValidOuterSignatureCannotCertifyUnopenedInnerSignatures() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.nested(false, OpenPgpSignatureFixtures.first);
        final OpenPgpReceiveConfig noDecryptionKey = OpenPgpReceiveConfig.builder()
                .addVerificationKeyRing(OpenPgpSignatureFixtures.first.publicRing).build();
        final Email parsed = read(input, noDecryptionKey, true);
        final OriginalOpenPgpDetails details = parsed.getOriginalOpenPgpDetails();
        assertThat(details.getSignatures()).singleElement().satisfies(signature ->
                assertThat(signature.getStatus()).isEqualTo(SignatureStatus.VALID));
        assertThat(details.getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(details.getDecryptionStatus()).isEqualTo(DecryptionStatus.KEY_MISSING);
        assertThat(details.getOriginalProtectedMessage()).containsExactly(input);
    }

    @Test
    void aLaterMimeInterpretationErrorCannotEraseSuccessfulDecryption() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.combined("not a MIME entity".getBytes(StandardCharsets.US_ASCII), -1,
                OpenPgpSignatureFixtures.first);
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        assertThat(parsed.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.ERROR);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureStatus.VALID));
        assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(input);
    }

    @Test
    void compressedOrStoppedDetachedInspectionCannotHideBehindAValidPrefix() throws Exception {
        for (byte[] input : List.of(OpenPgpSignatureFixtures.compressedDetached(false),
                OpenPgpSignatureFixtures.compressedDetached(true), OpenPgpSignatureFixtures.beyondPacketInspectionLimit())) {
            final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second), true);
            assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.ERROR);
            assertThat(parsed.getOriginalOpenPgpDetails().getSignatures()).allSatisfy(result ->
                    assertThat(result.getStatus()).isEqualTo(SignatureStatus.VALID));
            assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(input);
        }
    }

    @Test
    void copiesAndSerializationRetainResultsButCompletionRemainsOutgoing() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second);
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second), true);
        final Email copy = SimpleJavaMail.fromDefaults().emailBuilder().copying(parsed).buildEmail();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(parsed); }
        final Email restored;
        try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { restored = (Email) stream.readObject(); }
        for (Email value : List.of(parsed, copy, restored)) {
            assertThat(value.getOriginalOpenPgpDetails()).isEqualTo(parsed.getOriginalOpenPgpDetails());
            assertThat(value.getOriginalOpenPgpDetails().getSignatures()).hasSize(2);
            assertThatThrownBy(() -> value.getOriginalOpenPgpDetails().getSignatures().clear()).isInstanceOf(UnsupportedOperationException.class);
            value.getOriginalOpenPgpDetails().getOriginalProtectedMessage()[0] ^= 1;
            assertThat(value.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(input);
        }
        final Email completed = SimpleJavaMail.fromDefaults().emailBuilder().copying(parsed).buildEmailCompletedWithDefaultsAndOverrides();
        assertThat(completed.getOriginalOpenPgpDetails().getSignatures()).isEmpty();
        assertThat(completed.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.PLAIN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "invalid", "missing", "error", "encrypted-absent", "plain"})
    void earlierEmailSnapshotsPreserveSelectedFactsWithoutClaimingCompleteInspection(final String kind) throws Exception {
        final byte[] serialized;
        try (InputStream input = getClass().getResourceAsStream("/serialization/email-openpgp-details-selected-legacy-" + kind + ".base64")) {
            assertThat(input).isNotNull();
            serialized = Base64.getDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim());
        }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized))) { restored = (Email) input.readObject(); }
        final SignatureStatus expected = kind.equals("invalid") ? SignatureStatus.INVALID : kind.equals("missing") ? SignatureStatus.KEY_MISSING
                : kind.equals("error") ? SignatureStatus.ERROR : SignatureStatus.UNKNOWN;
        final Email copied = SimpleJavaMail.fromDefaults().emailBuilder().copying(restored).buildEmail();
        for (Email value : List.of(restored, copied)) {
            assertThat(value.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(expected);
            assertThat(value.getSubject()).isEqualTo("Legacy " + kind);
            if (List.of("valid", "invalid", "missing").contains(kind)) {
                assertThat(value.getOriginalOpenPgpDetails().getSignatures()).hasSize(1);
                assertThat(value.getOriginalOpenPgpDetails().getSignatures().get(0).getStatus())
                        .isEqualTo(kind.equals("valid") ? SignatureStatus.VALID : expected);
            } else assertThat(value.getOriginalOpenPgpDetails().getSignatures()).isEmpty();
            if (kind.equals("encrypted-absent")) assertThat(value.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(DecryptionStatus.DECRYPTED);
            assertThatThrownBy(() -> value.getOriginalOpenPgpDetails().getSignatures().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(copied.getOriginalOpenPgpDetails()).isEqualTo(restored.getOriginalOpenPgpDetails());
    }

    @Test
    void readingAndRestoringKnownFactsNeverCallsOptionalModules() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first);
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(serialized)) { output.writeObject(parsed); }
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class)) {
            final Email restored;
            try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray()))) { restored = (Email) stream.readObject(); }
            assertThat(restored.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
            restored.getOriginalOpenPgpDetails().getSignatures().forEach(signature -> {
                signature.getSignerKeyId(); signature.getSignerFingerprint(); signature.getSignatureAlgorithm();
                signature.getHashAlgorithm(); signature.getFailureReason();
            });
            modules.verifyNoInteractions();
        }
    }

    @Test
    void borrowedTopLevelStreamsStayOpenForValidAndFailedCombinedInput() throws Exception {
        final byte[] valid = OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first);
        for (byte[] bytes : List.of(valid, OpenPgpSignatureFixtures.corruptIntegrity(valid))) {
            try (TrackingInputStream source = new TrackingInputStream(bytes)) {
                final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(source)
                        .withOpenPgpReceiveConfig(OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first)).toEmail();
                assertThat(source.closed).isFalse();
                assertThat(parsed.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(bytes);
            }
        }
    }

    @Test
    void signaturesInsideAnAttachedMessageAreNotParentProtectionFacts() throws Exception {
        final byte[] attached = OpenPgpSignatureFixtures.detached(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second);
        final Email parent = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank().from("sender@example.test").to("receiver@example.test")
                .withPlainText("Parent content").withAttachment("attached.eml", attached, "message/rfc822").buildEmail();
        final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(EmailConverter.emailToEMLByteArray(parent)).toEmail();
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(SignatureStatus.NOT_PRESENT);
        assertThat(parsed.getOriginalOpenPgpDetails().getSignatures()).isEmpty();
        assertThat(parsed.getAttachments()).hasSize(1);
    }

    private static Email read(final byte[] input, final OpenPgpReceiveConfig keys, final boolean fluent) throws Exception {
        return read(input, keys, fluent ? ImportRoute.FLUENT_EML : ImportRoute.STATIC_MIME);
    }

    private static Email read(final byte[] input, final OpenPgpReceiveConfig keys, final ImportRoute route) throws Exception {
        switch (route) {
            case FLUENT_EML:
                return SimpleJavaMail.fromDefaults().converter().fromEml(input).withOpenPgpReceiveConfig(keys).toEmail();
            case STATIC_EML:
                return EmailConverter.emlToEmailWithOpenPgp(new ByteArrayInputStream(input), keys);
            case FLUENT_MIME:
                return SimpleJavaMail.fromDefaults().converter().fromMimeMessage(
                        new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(input)))
                        .withOpenPgpReceiveConfig(keys).toEmail();
            case STATIC_MIME:
                return EmailConverter.mimeMessageToEmail(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(input)), null, keys);
            default:
                throw new AssertionError(route);
        }
    }

    private enum ImportRoute { FLUENT_EML, STATIC_EML, FLUENT_MIME, STATIC_MIME }

    private static void assertMultipleShortcuts(final OriginalOpenPgpDetails details) {
        assertThat(details.getSignerKeyId()).isNull();
        assertThat(details.getSignerFingerprint()).isNull();
        assertThat(details.getSignatureAlgorithm()).isNull();
        assertThat(details.getHashAlgorithm()).isNull();
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;
        private TrackingInputStream(final byte[] bytes) { super(bytes); }
        @Override public void close() throws java.io.IOException { closed = true; super.close(); }
    }
}
