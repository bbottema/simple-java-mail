package org.simplejavamail.converter.internal;

import org.simplejavamail.api.email.*;
import org.simplejavamail.internal.email.DecryptionInspectionImpl;
import org.simplejavamail.internal.email.SignatureInspectionImpl;
import org.simplejavamail.internal.email.ValueInspectionImpl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contextual evidence for an EML representation generated from a native child; never a whole-file verification claim. */
final class MaterializedMessageInspection {
    private MaterializedMessageInspection() { }

    static ValueInspection describe(final Email child, final ValueInspection container) {
        final List<ValueInspection> components = new ArrayList<>();
        final EmailInspection inspection = child.getInspection();
        components.addAll(List.of(inspection.getPlainText(), inspection.getHTMLText(), inspection.getSubject(), inspection.getFromRecipient(),
                inspection.getToRecipients(), inspection.getCcRecipients(), inspection.getBccRecipients(), inspection.getReplyToRecipients(),
                inspection.getId(), inspection.getSentDate(), inspection.getCalendar()));
        for (int index = 0; index < child.getAttachments().size(); index++) components.add(inspection.getAttachment(index));
        for (int index = 0; index < child.getEmbeddedImages().size(); index++) components.add(inspection.getEmbeddedImage(index));
        components.removeIf(value -> value.getAvailability() != ValueAvailability.PRESENT);
        components.add(container);
        final Map<UUID, SignatureInspection> signatures = new LinkedHashMap<>();
        final Map<UUID, DecryptionInspection> decryptions = new LinkedHashMap<>();
        boolean unencrypted = true;
        for (final ValueInspection component : components) {
            unencrypted &= component.getEncryptionStatus() == EncryptionStatus.NOT_ENCRYPTED;
            for (final SignatureInspection signature : component.getSignatures()) {
                signatures.putIfAbsent(signature.getObservation().getReference(), SignatureInspectionImpl.builder().observation(signature.getObservation())
                        .coverage(signature.getCoverage() == ProtectionCoverage.UNKNOWN ? ProtectionCoverage.UNKNOWN : ProtectionCoverage.PARTIAL).build());
            }
            for (final DecryptionInspection decryption : component.getDecryptions()) {
                decryptions.putIfAbsent(decryption.getReference(), ((DecryptionInspectionImpl) DecryptionInspectionImpl.copyOf(decryption)).toBuilder()
                        .coverage(decryption.getCoverage() == ProtectionCoverage.UNKNOWN ? ProtectionCoverage.UNKNOWN : ProtectionCoverage.PARTIAL).build());
            }
        }
        return ValueInspectionImpl.builder().origin(ValueOrigin.DERIVED).derivation(ValueDerivation.UNKNOWN).availability(ValueAvailability.PRESENT)
                .sources(container.getSources()).signatures(new ArrayList<>(signatures.values())).decryptions(new ArrayList<>(decryptions.values()))
                .encryptionStatus(unencrypted ? EncryptionStatus.NOT_ENCRYPTED : EncryptionStatus.UNKNOWN).build();
    }
}
