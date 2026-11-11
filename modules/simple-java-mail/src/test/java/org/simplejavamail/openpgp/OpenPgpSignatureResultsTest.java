package org.simplejavamail.openpgp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
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
import org.simplejavamail.api.email.SignatureObservation;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.ProtectionInspection;
import org.simplejavamail.api.email.ApprovedSigners;
import org.simplejavamail.api.email.ProtectionRequirements;
import org.simplejavamail.api.email.ProtectionOutcome;
import org.pgpainless.key.parsing.KeyRingReader;
import org.bouncycastle.util.encoders.Hex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class OpenPgpSignatureResultsTest {
    @BeforeAll
    static void fixtures() throws Exception { OpenPgpSignatureFixtures.initialize(); }

    @Test
    void combinedPacketsUseTheSuppliedVerificationKey() throws Exception {
        final Email parsed = read(OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first),
                OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        assertThat(parsed.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(parsed.getInspection().getOpenPgp().getSignatures()).singleElement().satisfies(result -> {
            assertThat(result.getStatus()).isEqualTo(SignatureVerification.VERIFIED);
            assertThat(result.getSigningKeyFingerprint()).isNotBlank();
        });
    }

    @Test
    void aValidSignatureDoesNotHideAnotherUnavailableVerificationKey() throws Exception {
        final Email parsed = read(OpenPgpSignatureFixtures.detached(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second),
                OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.MIXED);
        assertThat(parsed.getInspection().getOpenPgp().getSignatures()).extracting(SignatureObservation::getStatus)
                .containsExactlyInAnyOrder(SignatureVerification.VERIFIED, SignatureVerification.UNVERIFIED);
        assertThat(requirements(false).check(parsed.getInspection().getPlainText()).isSatisfied()).isTrue();
        assertThat(requirements(true).check(parsed.getInspection().getPlainText()).getOutcome()).isEqualTo(ProtectionOutcome.INDETERMINATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"detached", "combined"})
    void everySignatureOccurrenceIsRetainedIncludingRepeatedSigningKeys(final String format) throws Exception {
        final byte[] input = format.equals("detached") ? OpenPgpSignatureFixtures.detached(
                OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second, OpenPgpSignatureFixtures.first)
                : OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second, OpenPgpSignatureFixtures.first);
        for (boolean allKeys : new boolean[]{true, false}) {
            final OpenPgpReceiveConfig keys = allKeys ? OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second)
                    : OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first);
            for (ImportRoute route : ImportRoute.values()) {
                final Email parsed = read(input, keys, route);
                final ProtectionInspection details = parsed.getInspection().getOpenPgp();
                assertThat(parsed.getPlainText()).contains("Complete signature facts");
                assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(allKeys ? SignatureStatus.VERIFIED : SignatureStatus.MIXED);
                assertThat(details.getSignatures()).hasSize(3);
                assertThat(details.getSignatures()).filteredOn(result -> result.getStatus() == SignatureVerification.VERIFIED).hasSize(allKeys ? 3 : 2);
                assertThat(details.getSignatures()).extracting(SignatureObservation::getSignerKeyId).doesNotContainNull();
                assertDistinctOccurrences(details);
                assertOriginal(parsed, input, route);
                if (format.equals("combined")) assertThat(parsed.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"detached", "combined"})
    void mixedSignaturesKeepTheApprovedVerifiedWitnessAndTheInvalidObservation(final String format) throws Exception {
        final byte[] input = format.equals("detached") ? OpenPgpSignatureFixtures.detachedWithInvalid(1,
                OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second)
                : OpenPgpSignatureFixtures.combinedWithInvalid(1, OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second);
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second), true);
        final ProtectionInspection details = parsed.getInspection().getOpenPgp();
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.MIXED);
        assertThat(details.getSignatures()).extracting(SignatureObservation::getStatus)
                .containsExactlyInAnyOrder(SignatureVerification.VERIFIED, SignatureVerification.INVALID);
        assertThat(details.getSignatures()).allSatisfy(signature -> assertThat(signature.getSigningKeyFingerprint()).isNotBlank());
        assertDistinctOccurrences(details);
        assertThat(parsed.getPlainText()).contains("Complete signature facts");
        final int inputProblems = parsed.getProblems().size();
        assertThat(requirements(false).check(parsed.getInspection().getPlainText()).isSatisfied()).isTrue();
        assertThat(requirements(true).check(parsed.getInspection().getPlainText()).getOutcome()).isEqualTo(ProtectionOutcome.NOT_SATISFIED);
        assertThat(parsed.getProblems()).hasSize(inputProblems);
        if (format.equals("combined")) assertThat(parsed.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"detached", "combined"})
    void unusableExplicitVerificationMaterialRemainsAnOperationFailure(final String format) throws Exception {
        final byte[] input = format.equals("detached") ? OpenPgpSignatureFixtures.detached(OpenPgpSignatureFixtures.first)
                : OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first);
        final OpenPgpReceiveConfig broken = OpenPgpReceiveConfig.builder().addVerificationKeyRing(new byte[]{1, 2, 3})
                .addDecryptionKeyRing(OpenPgpSignatureFixtures.first.secret, OpenPgpSignatureFixtures.first.passphrase).build();
        assertThatThrownBy(() -> read(input, broken, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured OpenPGP verification key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"packet-outside", "mime-outside"})
    void nestedMimeAndPacketSignaturesKeepTheirIndividualFacts(final String shape) throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.nested(shape.equals("packet-outside"),
                OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second);
        for (boolean allKeys : new boolean[]{true, false}) {
            final Email parsed = read(input, allKeys
                    ? OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second)
                    : OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
            final ProtectionInspection details = parsed.getInspection().getOpenPgp();
            assertThat(details.getSignatures()).hasSize(3);
            assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(allKeys ? SignatureStatus.VERIFIED : SignatureStatus.MIXED);
            assertThat(parsed.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
            assertOriginal(parsed, input, ImportRoute.FLUENT_EML);
            assertDistinctOccurrences(details);
        }
    }

    @Test
    void integrityFailureDoesNotBecomeKnownDecryptionOrSignatureAbsence() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.corruptIntegrity(OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first));
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        assertThat(parsed.getInspection().getOpenPgp().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.FAILED));
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(parsed.getInspection().getOpenPgp().getSignatures()).isEmpty();
        assertOriginal(parsed, input, ImportRoute.FLUENT_EML);
    }

    @Test
    void anActualNativeSignatureErrorIsDifferentFromEstablishedCiphertextIntegrityFailure() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.combinedWithMalformedSignature();
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        assertThat(parsed.getInspection().getOpenPgp().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(parsed.getInspection().getOpenPgp().getSignatures()).singleElement().satisfies(signature -> {
            assertThat(signature.getStatus()).isEqualTo(SignatureVerification.UNVERIFIED);
            assertThat(signature.getSignerKeyId()).isNotNull();
            assertThat(signature.getHashAlgorithm()).isEqualTo("SHA256");
        });
        assertOriginal(parsed, input, ImportRoute.FLUENT_EML);
        final byte[] corrupted = OpenPgpSignatureFixtures.corruptIntegrity(input);
        final Email failed = read(corrupted, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        assertThat(failed.getInspection().getOpenPgp().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.FAILED));
        assertThat(failed.getInspection().getOpenPgp().getSignatures()).isEmpty();
        assertOriginal(failed, corrupted, ImportRoute.FLUENT_EML);
    }

    @Test
    void completeUnsignedPacketsAreDifferentFromUnopenedOrUnavailablePackets() throws Exception {
        final Email unsigned = read(OpenPgpSignatureFixtures.combined(), OpenPgpSignatureFixtures.receiving(), true);
        assertThat(unsigned.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(unsigned.getInspection().getOpenPgp().getSignatures()).isEmpty();
        assertThat(unsigned.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
        final byte[] input = OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first);
        final Email unopened = read(input, OpenPgpReceiveConfig.builder().addVerificationKeyRing(OpenPgpSignatureFixtures.first.publicRing).build(), true);
        assertThat(unopened.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(unopened.getInspection().getOpenPgp().getSignatures()).isEmpty();
        assertThat(unopened.getInspection().getOpenPgp().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
            final Email unavailable = SimpleJavaMail.fromDefaults().converter().fromEml(input).toEmail();
            assertThat(unavailable.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
            assertThat(unavailable.getInspection().getOpenPgp().getSignatures()).isEmpty();
        }
    }

    @Test
    void aVerifiedOuterSignatureDoesNotCertifyUnopenedInnerSignatures() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.nested(false, OpenPgpSignatureFixtures.first);
        final Email parsed = read(input, OpenPgpReceiveConfig.builder().addVerificationKeyRing(OpenPgpSignatureFixtures.first.publicRing).build(), true);
        assertThat(parsed.getInspection().getOpenPgp().getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(parsed.getInspection().getOpenPgp().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.UNAVAILABLE));
        assertOriginal(parsed, input, ImportRoute.FLUENT_EML);
    }

    @Test
    void laterMimeInterpretationFailureDoesNotEraseEstablishedDecryptionOrSignatureFacts() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.combined("not a MIME entity".getBytes(StandardCharsets.US_ASCII), -1,
                OpenPgpSignatureFixtures.first);
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        assertThat(parsed.getInspection().getOpenPgp().getDecryptions()).singleElement()
                .satisfies(attempt -> assertThat(attempt.getStatus()).isEqualTo(DecryptionStatus.DECRYPTED));
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(parsed.getInspection().getOpenPgp().getSignatures()).singleElement()
                .satisfies(signature -> assertThat(signature.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
        assertOriginal(parsed, input, ImportRoute.FLUENT_EML);
    }

    @Test
    void stoppedDetachedInspectionDoesNotClaimCompleteEvidenceFromItsValidPrefix() throws Exception {
        for (byte[] input : List.of(OpenPgpSignatureFixtures.compressedDetached(false),
                OpenPgpSignatureFixtures.compressedDetached(true), OpenPgpSignatureFixtures.beyondPacketInspectionLimit())) {
            final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second), true);
            assertThat(parsed.getInspection().getPlainText().isSignatureInspectionComplete()).isFalse();
            assertThat(parsed.getInspection().getOpenPgp().getSignatures()).allSatisfy(result ->
                    assertThat(result.getStatus()).isEqualTo(SignatureVerification.VERIFIED));
            assertOriginal(parsed, input, ImportRoute.FLUENT_EML);
        }
    }

    @Test
    void copiesSnapshotsAndUnchangedCompletionKeepEvidenceWithoutActivatingOutgoingProtection() throws Exception {
        final byte[] input = OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second);
        final Email parsed = read(input, OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second), true);
        final Email copy = SimpleJavaMail.fromDefaults().emailBuilder().copying(parsed).buildEmail();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(parsed); }
        final Email restored;
        try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { restored = (Email) stream.readObject(); }
        final Email completed = SimpleJavaMail.fromDefaults().emailBuilder().copying(parsed).buildEmailCompletedWithDefaultsAndOverrides();
        final List<java.util.UUID> references = parsed.getInspection().getOpenPgp().getSignatures().stream()
                .map(SignatureObservation::getReference).collect(java.util.stream.Collectors.toList());
        for (Email value : List.of(parsed, copy, restored, completed)) {
            assertThat(value.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
            assertThat(value.getInspection().getOpenPgp().getSignatures()).extracting(SignatureObservation::getReference).containsExactlyElementsOf(references);
            assertThatThrownBy(() -> value.getInspection().getOpenPgp().getSignatures().clear()).isInstanceOf(UnsupportedOperationException.class);
            value.getInspection().getOriginalMessage().readAllBytes()[0] ^= 1;
            assertOriginal(value, input, ImportRoute.FLUENT_EML);
            assertThat(value.getOpenPgpSigningConfig()).isNull();
            assertThat(value.getOpenPgpEncryptionConfig()).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "invalid", "missing", "error", "encrypted-absent", "plain"})
    void oldSelectedOnlySnapshotsHaveUncapturedCurrentFieldHistory(final String kind) throws Exception {
        final byte[] serialized;
        try (InputStream input = getClass().getResourceAsStream("/serialization/email-openpgp-details-selected-legacy-" + kind + ".base64")) {
            assertThat(input).isNotNull(); serialized = Base64.getDecoder().decode(new String(input.readAllBytes(), StandardCharsets.US_ASCII).trim());
        }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized))) { restored = (Email) input.readObject(); }
        final Email copied = SimpleJavaMail.fromDefaults().emailBuilder().copying(restored).buildEmail();
        for (Email value : List.of(restored, copied)) {
            assertThat(value.getSubject()).isEqualTo("Legacy " + kind);
            assertThat(value.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
            assertThat(value.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.UNKNOWN);
            assertThat(value.getInspection().getOpenPgp().getSignatures()).isEmpty();
            assertThat(value.getInspection().getOpenPgp().isInspectionComplete()).isFalse();
        }
    }

    @Test
    void readingOrRestoringCapturedFactsDoesNotLoadOptionalModules() throws Exception {
        final Email parsed = read(OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first), OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first), true);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(parsed); }
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class)) {
            final Email restored;
            try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { restored = (Email) stream.readObject(); }
            assertThat(restored.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
            restored.getInspection().getOpenPgp().getSignatures().forEach(signature -> {
                signature.getSignerKeyId(); signature.getSigningKeyFingerprint(); signature.getSignatureAlgorithm();
                signature.getHashAlgorithm(); signature.getFailureReason();
            });
            modules.verifyNoInteractions();
        }
    }

    @Test
    void borrowedInputStreamsRemainOpenForValidAndFailedCombinedInput() throws Exception {
        final byte[] valid = OpenPgpSignatureFixtures.combined(OpenPgpSignatureFixtures.first);
        for (byte[] bytes : List.of(valid, OpenPgpSignatureFixtures.corruptIntegrity(valid))) {
            try (TrackingInputStream source = new TrackingInputStream(bytes)) {
                final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(source)
                        .withOpenPgpReceiveConfig(OpenPgpSignatureFixtures.receiving(OpenPgpSignatureFixtures.first)).toEmail();
                assertThat(source.closed).isFalse(); assertOriginal(parsed, bytes, ImportRoute.FLUENT_EML);
            }
        }
    }

    @Test
    void attachedMessageSignaturesStayChildOwned() throws Exception {
        final byte[] child = OpenPgpSignatureFixtures.detached(OpenPgpSignatureFixtures.first, OpenPgpSignatureFixtures.second);
        final Email parent = SimpleJavaMail.fromDefaults().emailBuilder().startingBlank().from("sender@example.test").to("receiver@example.test")
                .withPlainText("Parent content").withAttachment("attached.eml", child, "message/rfc822").buildEmail();
        final Email parsed = SimpleJavaMail.fromDefaults().converter().fromEml(EmailConverter.emailToEMLByteArray(parent)).toEmail();
        assertThat(parsed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
        assertThat(parsed.getInspection().getOpenPgp().getSignatures()).isEmpty();
        assertThat(parsed.getAttachments()).singleElement();
        assertThat(parsed.getAttachments().get(0).getEmail()).isNotNull();
        assertThat(parsed.getAttachments().get(0).getEmail().getInspection().getOpenPgp().getSignatures()).hasSize(2);
    }

    private static ProtectionRequirements requirements(final boolean strict) throws Exception {
        final String fingerprint = Hex.toHexString(new KeyRingReader().publicKeyRing(OpenPgpSignatureFixtures.first.publicRing).getPublicKey().getFingerprint());
        final var builder = ProtectionRequirements.builder().requireVerifiedSignatureFrom(
                ApprovedSigners.builder().addOpenPgpSigningKeyFingerprint(fingerprint).build());
        if (strict) builder.requireAllSignaturesVerified();
        return builder.build();
    }

    private static void assertDistinctOccurrences(final ProtectionInspection inspection) {
        assertThat(inspection.getSignatures()).extracting(SignatureObservation::getReference).doesNotHaveDuplicates();
        assertThatThrownBy(() -> inspection.getSignatures().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertOriginal(final Email email, final byte[] source, final ImportRoute route) {
        if (route == ImportRoute.FLUENT_EML || route == ImportRoute.STATIC_EML) {
            assertThat(email.getInspection().getOriginalMessage()).isNotNull();
            assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(source);
        } else assertThat(email.getInspection().getOriginalMessage()).isNull();
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

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;
        private TrackingInputStream(final byte[] bytes) { super(bytes); }
        @Override public void close() throws java.io.IOException { closed = true; super.close(); }
    }
}
