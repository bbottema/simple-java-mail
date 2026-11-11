package fixtures.received;

import org.simplejavamail.api.email.ApprovedSigners;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ProtectionCheck;
import org.simplejavamail.api.email.ProtectionOutcome;
import org.simplejavamail.api.email.ProtectionReason;
import org.simplejavamail.api.email.ProtectionRequirements;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** A manual target: business selection and partner approval are explicit inputs. */
public final class PartnerDocument {
    private final ProtectionRequirements requirements;

    public PartnerDocument(X509Certificate approvedCurrentKey, X509Certificate approvedRotationKey) {
        ApprovedSigners approved = ApprovedSigners.builder()
                .addSmimeCertificate(approvedCurrentKey)
                .addSmimeCertificate(approvedRotationKey)
                .build();
        requirements = ProtectionRequirements.builder()
                .requireVerifiedSignatureFrom(approved)
                .requireDecryptedContent()
                .build();
    }

    public void receive(Email email, int selectedDocumentIndex, Consumer<byte[]> process,
                        BiConsumer<ProtectionOutcome, List<ProtectionReason>> hold) {
        AttachmentResource document = email.getAttachments().get(selectedDocumentIndex);
        ProtectionCheck check = requirements.check(email.getInspection().getAttachment(selectedDocumentIndex));
        if (check.isSatisfied()) {
            process.accept(document.readAllBytes());
        } else {
            hold.accept(check.getOutcome(), check.getReasons());
        }
    }

    public Email materializedChild(AttachmentResource document) {
        return document.getEmail(); // Nullable and passive; the parent's payload is unchanged.
    }
}
