package org.simplejavamail.api.email;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.unmodifiableList;

/**
 * An immutable recipients object, with an optional default or overriding name.
 *
 * @see IRecipientsBuilder
 */
@Value
public class Recipients implements Serializable {

    private static final long serialVersionUID = 1234567L;

    /**
     * @see IRecipientsBuilder#withRecipient(Recipient)
     * @see IRecipientsBuilder#withRecipients(Recipient...)
     */
    @NotNull List<Recipient> recipients;

    /**
     * Optional default name used for recipient groups where missing names should be filled.
     */
    @Nullable String defaultName;

    /**
     * Optional fixed name used for recipient groups where parsed names should be overwritten.
     */
    @Nullable String overridingName;

    /** Keeps an independent list of recipient occurrences, in their supplied order. */
    public Recipients(@NotNull final List<Recipient> recipients, @Nullable final String defaultName, @Nullable final String overridingName) {
        this.recipients = unmodifiableList(new ArrayList<>(recipients));
        this.defaultName = defaultName;
        this.overridingName = overridingName;
    }

    /**
     * Returns the immutable recipient list, retaining duplicates and input order.
     * @see IRecipientsBuilder#withRecipient(Recipient)
     * @see IRecipientsBuilder#withRecipients(Recipient...)
     */
    @NotNull
    public List<Recipient> getRecipients() {
        return recipients;
    }

    /**
     * Returns the optional name used when a recipient has no name.
     * @see IRecipientsBuilder#withRecipientsWithDefaultName(String, java.util.Collection, jakarta.mail.Message.RecipientType)
     */
    @Nullable
    public String getDefaultName() {
        return defaultName;
    }

    /**
     * Returns the optional name that replaces names supplied on individual recipients.
     * @see IRecipientsBuilder#withRecipientsWithFixedName(String, java.util.Collection, jakarta.mail.Message.RecipientType)
     */
    @Nullable
    public String getOverridingName() {
        return overridingName;
    }

    /** Older snapshots can contain mutable lists shared elsewhere in the same serialized object graph. */
    private Object readResolve() {
        return new Recipients(recipients, defaultName, overridingName);
    }
}
