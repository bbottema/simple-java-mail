package fixtures.application;

import java.util.Collections;
import java.util.List;

/** Names alone are never evidence of an SJM migration site. */
public final class SameNamedApplication {
    public List<String> getDecryptedAttachments() { return Collections.emptyList(); }
    public SameNamedApplication getSmimeSignedEmail() { return this; }
    public String getOriginalSmimeDetails() { return "application data"; }
    public boolean isMergeSingleSMIMESignedAttachment() { return false; }
    public SameNamedApplication notMergingSingleSMIMESignedAttachment() { return this; }
    public SameNamedApplication clearSMIMESignedAttachmentMergingBehavior() { return this; }
}
