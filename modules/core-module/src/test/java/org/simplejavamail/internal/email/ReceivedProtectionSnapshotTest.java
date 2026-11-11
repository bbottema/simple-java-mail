package org.simplejavamail.internal.email;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.EmailSourceElement;
import org.simplejavamail.api.email.DecryptionStatus;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.SignatureInspection;
import org.simplejavamail.api.email.SignatureObservation;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.api.email.ValueInspection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReceivedProtectionSnapshotTest {
    @Test
    void foreignObservationAndCollectionAliasesAreDetachedBeforeTheValueBecomesReadable() {
        final MutableSignature mutable = new MutableSignature();
        final SignatureInspection associated = SignatureInspectionImpl.builder().observation(mutable).coverage(ProtectionCoverage.FULL).build();
        final List<SignatureInspection> aliases = new ArrayList<>(List.of(associated, associated));
        final ValueInspection value = ValueInspectionImpl.builder().availability(ValueAvailability.PRESENT)
                .signatureInspectionComplete(true).signatures(aliases).build();
        aliases.clear();
        mutable.status = SignatureVerification.INVALID;
        mutable.fingerprint = "changed";
        mutable.reads = 0;
        assertThat(value.getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(value.getSignatures()).hasSize(2);
        assertThat(value.getSignatures().get(0).getObservation().getSigningKeyFingerprint()).isEqualTo("captured-key");
        assertThat(value.getSignatures().get(0).getObservation()).isSameAs(value.getSignatures().get(1).getObservation());
        assertThatThrownBy(() -> value.getSignatures().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(mutable.reads).isZero();
    }

    @Test
    void unknownSnapshotDefaultsNeverClaimPlainOrSuccessfulInspection() {
        final ReceivedEmailInspection unknown = ReceivedEmailInspection.unknown(2, 1);
        assertThat(unknown.getPlainText().getAvailability()).isEqualTo(ValueAvailability.UNKNOWN);
        assertThat(unknown.getSubject().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(unknown.getAttachment(0).isSignatureInspectionComplete()).isFalse();
        assertThat(unknown.getSmime().isInspectionComplete()).isFalse();
        assertThat(unknown.getOpenPgp().isInspectionComplete()).isFalse();
        assertThat(unknown.getOriginalMessage()).isNull();
        assertThat(unknown.getOriginalAttachment(1)).isNull();
    }

    @Test
    void observedRecipientIdsAreImmutableDiagnosticDataAndCanRemainUncaptured() {
        final List<String> recipients = new ArrayList<>(List.of("observed-recipient-key"));
        final DecryptionInspectionImpl observation = DecryptionInspectionImpl.builder().family(ProtectionFamily.OPENPGP)
                .status(DecryptionStatus.UNAVAILABLE).recipientKeyIds(recipients).build();
        recipients.clear();
        assertThat(observation.getRecipientKeyIds()).containsExactly("observed-recipient-key");
        assertThatThrownBy(() -> observation.getRecipientKeyIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(DecryptionInspectionImpl.builder().family(ProtectionFamily.OPENPGP).status(DecryptionStatus.DEFERRED).build().getRecipientKeyIds()).isEmpty();
    }

    private static final class MutableSignature implements SignatureObservation {
        private static final long serialVersionUID = 1L;
        private final UUID reference = UUID.randomUUID();
        private SignatureVerification status = SignatureVerification.VERIFIED;
        private String fingerprint = "captured-key";
        private int reads;
        @Override public UUID getReference() { reads++; return reference; }
        @Override public ProtectionFamily getFamily() { reads++; return ProtectionFamily.OPENPGP; }
        @Override public SignatureVerification getStatus() { reads++; return status; }
        @Override public String getSignerName() { reads++; return "fixture signer"; }
        @Override public String getSignerKeyId() { reads++; return "fixture-id"; }
        @Override public String getSigningKeyFingerprint() { reads++; return fingerprint; }
        @Override public String getSignatureAlgorithm() { reads++; return "RSA"; }
        @Override public String getHashAlgorithm() { reads++; return "SHA256"; }
        @Override public String getFailureReason() { reads++; return null; }
        @Override public List<EmailSourceElement> getSources() { reads++; return List.of(); }
    }
}
