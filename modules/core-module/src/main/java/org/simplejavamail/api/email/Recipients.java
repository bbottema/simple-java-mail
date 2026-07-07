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
     * Optional default name used for recipient groups where missing names should be filled.
     */
    @Nullable String defaultName;

    /**
     * Optional fixed name used for recipient groups where parsed names should be overwritten.
     */
    @Nullable String overridingName;
}
