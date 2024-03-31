package org.simplejavamail.api.email;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

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
     * @see IRecipientsBuilder#withDefaultName(String)
     */
    @Nullable String defaultName;

    /**
     * @see IRecipientsBuilder#withOverridingName(String)
     */
    @Nullable String overridingName;
}