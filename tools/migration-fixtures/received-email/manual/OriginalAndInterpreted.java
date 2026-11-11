package fixtures.received;

import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.ForwardOptions;
import org.simplejavamail.api.email.ReplyOptions;

/** Different explicit intents; no migration can infer which one an application wants. */
public final class OriginalAndInterpreted {
    public EmailPopulatingBuilder currentView(EmailStartingBuilder start, Email received) {
        return start.forwarding(received);
    }

    public EmailPopulatingBuilder completeOriginal(EmailStartingBuilder start, Email received) {
        return start.forwardingOriginal(received);
    }

    public AttachmentResource historicalAttachment(Email received, int selectedIndex) {
        return received.getInspection().getOriginalAttachment(selectedIndex);
    }

    public EmailPopulatingBuilder permittedDraft(EmailStartingBuilder start, Email received) {
        return start.forwarding(received, ForwardOptions.allowUnencryptedContent());
    }

    public EmailPopulatingBuilder permittedReply(EmailStartingBuilder start, Email received) {
        return start.replyingTo(received, ReplyOptions.allowUnencryptedContent());
    }

    public byte[] explicitHandoff(ConfiguredEmailConverter converter, Email draft) {
        return converter.fromEmail(draft).allowUnencryptedContent().toEmlBytes();
        // The application protects this handoff. The source draft's later send receives no permission.
    }
}
