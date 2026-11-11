package fixtures.received;

import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalSmimeDetails;

import java.util.List;

/** Actual 9.3.10 sites to report without changing application intent. */
public final class LegacyReceivedEmail {
    public List<AttachmentResource> openedResources(Email email) {
        return email.getDecryptedAttachments();
    }

    public Email retainedInner(Email email) {
        return email.getSmimeSignedEmail();
    }

    public Boolean flattenedValidity(Email email) {
        OriginalSmimeDetails details = email.getOriginalSmimeDetails();
        return details.getSmimeSignatureValid();
    }

    public OriginalSmimeDetails.SmimeMode mode(EmailPopulatingBuilder builder) {
        return builder.getOriginalSmimeDetails().getSmimeMode();
    }

    public void separateView(EmailPopulatingBuilder builder) {
        builder.notMergingSingleSMIMESignedAttachment();
        boolean selectedMerge = builder.isMergeSingleSMIMESignedAttachment();
        if (selectedMerge) {
            builder.clearSMIMESignedAttachmentMergingBehavior();
        }
    }
}
