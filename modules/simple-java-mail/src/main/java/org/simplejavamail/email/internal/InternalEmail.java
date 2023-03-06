package org.simplejavamail.email.internal;

import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;

/**
 * @deprecated for internal use only. This class hides some methods from the public API that are used internally to implement the builder API.
 */
@Deprecated
@EqualsAndHashCode(callSuper = true)
@SuppressWarnings("DeprecatedIsStillUsed")
public class InternalEmail extends Email {

    private static final long serialVersionUID = 1234567L;

    @Nullable
    private InternalEmail userProvidedEmail;

    public InternalEmail(@NotNull EmailPopulatingBuilder builder) {
        super(builder);
    }

    /**
     * @deprecated Don't use this method. This method is used internally to set the reference to the original email when a copy is made to which all defaults and overrides
     * are applied. When sending the email, however, we still need a reference to the original email to be able to update the message id. userProvidedEmail can be set to
     * null in some junit tests.
     */
    public void setUserProvidedEmail(@NotNull final Email userProvidedEmail) {
        this.userProvidedEmail = (InternalEmail) userProvidedEmail;
    }

    /**
     * @deprecated Don't use this method, refer to {@link EmailPopulatingBuilder#fixingMessageId(String)} instead. This method is used internally to
     * update the message id once a mail has been sent.
     */
    public void updateId(@NotNull final String id) {
        this.id = id;
        if (this.userProvidedEmail != null) {
            this.userProvidedEmail.updateId(id);
        }
    }

    /**
     * @deprecated Don't use this method. This method is used internally when using the builder API to copy an email that
     * contains an S/MIME signed message. Without this method, we don't know if the copy should also be merged to match the
     * copied email.
     */
    public boolean wasMergedWithSmimeSignedMessage() {
        return wasMergedWithSmimeSignedMessage;
    }

}