package org.simplejavamail.email.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeMessage;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailWithDefaultsAndOverridesApplied;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.spi.ContentRequirement;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.UnsupportedEncodingException;

/**
 * @deprecated for internal use only. This class hides some methods from the public API that are used internally to implement the builder API.
 */
@Deprecated
@EqualsAndHashCode(callSuper = true, exclude = {"defaultsAndOverridesApplied", "deliveryStatusNotificationRequired"})
@SuppressWarnings("DeprecatedIsStillUsed")
public class InternalEmail extends Email implements EmailWithDefaultsAndOverridesApplied {

    private static final long serialVersionUID = 1234567L;

    @Nullable
    private InternalEmail userProvidedEmail;
    private boolean defaultsAndOverridesApplied;
    private boolean deliveryStatusNotificationRequired;
    @NotNull
    private EmailSource emailSource;

    public InternalEmail(@NotNull EmailPopulatingBuilder builder) {
        this(builder, ComposedEmailSource.INSTANCE);
    }

    public InternalEmail(@NotNull final EmailPopulatingBuilder builder, final byte @NotNull [] exactEmlBytes) {
        this(builder, new ExactEmlSource(exactEmlBytes));
    }

    private InternalEmail(@NotNull final EmailPopulatingBuilder builder, @NotNull final EmailSource emailSource) {
        super(builder);
        this.emailSource = emailSource;
    }

    @NotNull
    public static InternalEmail requireInternalEmail(@NotNull final Email email) {
        if (!(email instanceof InternalEmail)) {
            throw new IllegalArgumentException("Email is not a Simple Java Mail Email implementation");
        }
        return (InternalEmail) email;
    }

    @NotNull
    public Email prepareForConversion(@NotNull final EmailGovernance emailGovernance) {
        return emailSource.prepareForConversion(this, emailGovernance);
    }

    @NotNull
    public Email prepareForSending(@NotNull final EmailGovernance emailGovernance, final boolean disableAllClientValidation) {
        return emailSource.prepareForSending(this, emailGovernance, disableAllClientValidation);
    }

    @NotNull
    public MimeMessage renderMimeMessage(@NotNull final Session session, final boolean processSecurity)
            throws MessagingException, UnsupportedEncodingException {
        return emailSource.renderMimeMessage(this, session, processSecurity);
    }

    @NotNull
    public ContentRequirement determineContentRequirement() {
        return emailSource.determineContentRequirement(this);
    }

    public boolean isExactEml() {
        return emailSource instanceof ExactEmlSource;
    }

    /** Detached original headers for lock verification; stops before the body without copying or changing the preserved bytes. */
    @NotNull
    public InternetHeaders readExactHeaders() throws MessagingException {
        if (!isExactEml()) {
            throw new IllegalStateException("Only exact EML has preserved original headers to inspect");
        }
        return ((ExactEmlSource) emailSource).readHeaders();
    }

    /** Copies resolved envelope metadata without replacing the source that owns preserved MIME bytes. */
    public InternalEmail copyPreservingSource(@NotNull final EmailPopulatingBuilder builder) {
        final InternalEmail copy = new InternalEmail(builder, emailSource);
        copy.deliveryStatusNotificationRequired = deliveryStatusNotificationRequired;
        copy.userProvidedEmail = userProvidedEmail;
        return copy;
    }

    /** Internal preparation fact; public Email preferences remain unchanged. */
    public boolean isDeliveryStatusNotificationRequired() {
        return deliveryStatusNotificationRequired;
    }

    /** Marks a governed attempt whose DSN request may not be silently omitted by an adapter. */
    public void requireDeliveryStatusNotification() {
        deliveryStatusNotificationRequired = true;
    }

    /**
     * Restores the composed strategy for Emails serialized before source strategies were introduced.
     */
    private void readObject(final ObjectInputStream input) throws IOException, ClassNotFoundException {
        input.defaultReadObject();
        if (emailSource == null) {
            emailSource = ComposedEmailSource.INSTANCE;
        }
    }

    /**
     * @deprecated Don't use this method. This method is used internally to set the reference to the original email when a copy is made to which all defaults and overrides
     * are applied. When sending the email, however, we still need a reference to the original email to be able to update the message id. userProvidedEmail can be set to
     * null in some junit tests.
     */
    public void setUserProvidedEmail(@Nullable final Email userProvidedEmail) {
        this.userProvidedEmail = userProvidedEmail == null ? null : requireInternalEmail(userProvidedEmail);
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

    @Override
    public void markAsDefaultsAndOverridesApplied() {
        this.defaultsAndOverridesApplied = true;
    }

    @Override
    public void verifyDefaultsAndOverridesApplied() {
        if (!defaultsAndOverridesApplied) {
            throw new IllegalStateException("Email was not marked as complete. This is a bug in Simple Java Mail. Please report this issue.");
        }
    }
}
