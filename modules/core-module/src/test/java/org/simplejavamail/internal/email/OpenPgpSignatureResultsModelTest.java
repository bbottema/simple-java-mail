package org.simplejavamail.internal.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.OpenPgpSignatureResult;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.OriginalOpenPgpDetails.SignatureStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenPgpSignatureResultsModelTest {
    @Test
    void resultsAreDeeplyDetachedOrderedAndNeverDeduplicated() {
        final MutableResult result = new MutableResult(SignatureStatus.VALID, "B");
        final List<OpenPgpSignatureResult> choices = new ArrayList<>(List.of(result, signature(SignatureStatus.INVALID, "A"), result));
        final OpenPgpDetailsImpl details = OpenPgpDetailsImpl.builder().signatures(choices).signatureInspectionComplete(true).build();
        choices.clear();
        result.status = SignatureStatus.UNKNOWN;
        result.key = "changed";
        result.reads = 0;

        assertThat(details.getSignatures()).extracting(OpenPgpSignatureResult::getSignerKeyId).containsExactly("A", "B", "B");
        assertThat(details.getSignatures()).extracting(OpenPgpSignatureResult::getStatus)
                .containsExactly(SignatureStatus.INVALID, SignatureStatus.VALID, SignatureStatus.VALID);
        assertThat(details.getSignatureStatus()).isEqualTo(SignatureStatus.INVALID);
        assertThat(details.getSignerKeyId()).isNull();
        assertThat(details.getSignerFingerprint()).isNull();
        assertThat(details.getSignatureAlgorithm()).isNull();
        assertThat(details.getHashAlgorithm()).isNull();
        assertThatThrownBy(() -> details.getSignatures().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(result.reads).isZero();
    }

    @Test
    void aPositiveSummaryRequiresCompleteInspectionAndEveryOccurrenceValid() {
        final List<OpenPgpSignatureResult> valid = List.of(signature(SignatureStatus.VALID, "A"), signature(SignatureStatus.VALID, "B"));
        assertThat(OpenPgpDetailsImpl.builder().signatures(valid).build().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(OpenPgpDetailsImpl.builder().signatures(valid).signatureInspectionComplete(true).build().getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
        assertThat(OpenPgpDetailsImpl.builder().build().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(OpenPgpDetailsImpl.plain().getSignatureStatus()).isEqualTo(SignatureStatus.NOT_PRESENT);
        assertThat(OpenPgpDetailsImpl.builder().signatureStatus(SignatureStatus.ERROR).build().getSignatureStatus()).isEqualTo(SignatureStatus.ERROR);
        final List<SignatureStatus> precedence = List.of(SignatureStatus.INVALID, SignatureStatus.ERROR, SignatureStatus.KEY_MISSING,
                SignatureStatus.UNKNOWN, SignatureStatus.VALID);
        for (int first = 0; first < precedence.size(); first++) {
            final List<OpenPgpSignatureResult> mixed = new ArrayList<>();
            for (int next = first; next < precedence.size(); next++) mixed.add(signature(precedence.get(next), "K" + next));
            assertThat(OpenPgpDetailsImpl.builder().signatures(mixed).signatureInspectionComplete(true).build().getSignatureStatus())
                    .isEqualTo(precedence.get(first));
        }
    }

    @Test
    void oneRepresentedOccurrenceProvidesOnlyItsOwnShortcutFacts() {
        final OpenPgpDetailsImpl details = OpenPgpDetailsImpl.builder().signatures(List.of(signature(SignatureStatus.VALID, "A")))
                .signatureInspectionComplete(true).build();
        assertThat(details.getSignerKeyId()).isEqualTo("A");
        assertThat(details.getSignerFingerprint()).isEqualTo("fingerprint-A");
        assertThat(details.getSignatureAlgorithm()).isEqualTo("RSA");
        assertThat(details.getHashAlgorithm()).isEqualTo("SHA256");
        assertThatThrownBy(() -> signature(SignatureStatus.NOT_PRESENT, "A")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void currentForeignContractFactsPreserveCompleteMeaningAndValueEquality() {
        final List<OpenPgpSignatureResult> values = new ArrayList<>(List.of(signature(SignatureStatus.VALID, "A"), signature(SignatureStatus.VALID, "B")));
        final OpenPgpDetailsImpl expected = OpenPgpDetailsImpl.builder().openPgpMode(OriginalOpenPgpDetails.OpenPgpMode.SIGNED)
                .signatures(values).signatureInspectionComplete(true).originalProtectedMessage(new byte[] {1, 2}).build();
        final OriginalOpenPgpDetails foreign = foreign(values);
        final OpenPgpDetailsImpl copied = OpenPgpDetailsImpl.copyOf(foreign);
        values.clear();
        assertThat(copied.getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
        assertThat(copied).isEqualTo(expected).hasSameHashCodeAs(expected);
        assertThat(copied.getSignatures()).hasSize(2);
    }

    @Test
    void deserializationDetachesMutableAliasesAndPerSignatureImplementations() throws Exception {
        final MutableResult mutable = new MutableResult(SignatureStatus.VALID, "A");
        final List<OpenPgpSignatureResult> aliases = new ArrayList<>(List.of(mutable));
        final OpenPgpDetailsImpl details = OpenPgpDetailsImpl.builder().signatureInspectionComplete(true).build();
        final var signaturesField = OpenPgpDetailsImpl.class.getDeclaredField("signatures");
        signaturesField.setAccessible(true);
        signaturesField.set(details, aliases);
        final Object[] restored = roundTrip(new Object[] {details, mutable, aliases});
        final OpenPgpDetailsImpl snapshot = (OpenPgpDetailsImpl) restored[0];
        ((MutableResult) restored[1]).status = SignatureStatus.INVALID;
        ((List<?>) restored[2]).clear();
        assertThat(snapshot.getSignatureStatus()).isEqualTo(SignatureStatus.VALID);
        assertThat(snapshot.getSignatures()).singleElement().satisfies(result -> assertThat(result.getStatus()).isEqualTo(SignatureStatus.VALID));
        assertThatThrownBy(() -> snapshot.getSignatures().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "invalid", "missing", "error", "encrypted-absent", "plain"})
    void legacySelectedFactsDoNotInventCompleteInspection(final String kind) throws Exception {
        final byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream("/serialization/openpgp-details-selected-legacy-" + kind + ".base64")) {
            assertThat(input).isNotNull();
            bytes = Base64.getDecoder().decode(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim());
        }
        final OriginalOpenPgpDetails restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) { restored = (OriginalOpenPgpDetails) input.readObject(); }
        final SignatureStatus summary = kind.equals("invalid") ? SignatureStatus.INVALID : kind.equals("missing") ? SignatureStatus.KEY_MISSING
                : kind.equals("error") ? SignatureStatus.ERROR : SignatureStatus.UNKNOWN;
        assertThat(restored.getSignatureStatus()).isEqualTo(summary);
        if (List.of("valid", "invalid", "missing").contains(kind)) {
            assertThat(restored.getSignatures()).hasSize(1);
            assertThat(restored.getSignatures().get(0).getStatus()).isEqualTo(kind.equals("valid") ? SignatureStatus.VALID : summary);
            assertThat(restored.getSignerKeyId()).isEqualTo("0123456789ABCDEF");
        } else {
            assertThat(restored.getSignatures()).isEmpty();
            assertThat(restored.getSignerKeyId()).isNull();
        }
        if (kind.equals("encrypted-absent")) assertThat(restored.getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.DECRYPTED);
        final OriginalOpenPgpDetails again = roundTrip(restored);
        assertThat(again).isEqualTo(restored).hasSameHashCodeAs(restored);
        assertThat(again.getSignatureStatus()).isEqualTo(summary);
    }

    private static OpenPgpSignatureResult signature(final SignatureStatus status, final String key) {
        return new OpenPgpSignatureResultImpl(status, key, "fingerprint-" + key, "RSA", "SHA256", null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(final T value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(value); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (T) input.readObject(); }
    }

    private static OriginalOpenPgpDetails foreign(final List<OpenPgpSignatureResult> values) {
        return new OriginalOpenPgpDetails() {
            private static final long serialVersionUID = 1L;
            public OpenPgpMode getOpenPgpMode() { return OpenPgpMode.SIGNED; }
            public SignatureStatus getSignatureStatus() { return SignatureStatus.VALID; }
            public DecryptionStatus getDecryptionStatus() { return DecryptionStatus.NOT_ENCRYPTED; }
            public List<OpenPgpSignatureResult> getSignatures() { return values; }
            public String getSignerKeyId() { return null; }
            public String getSignerFingerprint() { return null; }
            public String getSignatureAlgorithm() { return null; }
            public String getHashAlgorithm() { return null; }
            public String getEncryptionAlgorithm() { return null; }
            public List<String> getRecipientKeyIds() { return List.of(); }
            public String getFailureReason() { return null; }
            public byte[] getOriginalProtectedMessage() { return new byte[] {1, 2}; }
        };
    }

    private static final class MutableResult implements OpenPgpSignatureResult {
        private static final long serialVersionUID = 1L;
        private SignatureStatus status;
        private String key;
        private int reads;
        private MutableResult(final SignatureStatus status, final String key) { this.status = status; this.key = key; }
        public SignatureStatus getStatus() { reads++; return status; }
        public String getSignerKeyId() { reads++; return key; }
        public String getSignerFingerprint() { reads++; return "fingerprint-" + key; }
        public String getSignatureAlgorithm() { reads++; return "RSA"; }
        public String getHashAlgorithm() { reads++; return "SHA256"; }
        public String getFailureReason() { reads++; return null; }
    }
}
