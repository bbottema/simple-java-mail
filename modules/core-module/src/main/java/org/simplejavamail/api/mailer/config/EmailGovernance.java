package org.simplejavamail.api.mailer.config;

import com.sanctionco.jmail.EmailValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailWithDefaultsAndOverridesApplied;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

/**
 * Governance for all emails being sent through the current {@link org.simplejavamail.api.mailer.Mailer} instance. That is, this class represents actions
 * taken or configuration used by default for each individual email sent through the current mailer. For example, you might want to S/MIME sign all emails
 * by default. You <em>can</em> do it manually on each email of course, but then the keystore used for this is not reused.
 * <p>
 * Also, you can supply a custom {@link org.simplejavamail.api.email.Email email} instance which will be used for <em>defaults</em> or <em>overrides</em>. For example,
 * you can set a default from address or subject. Any fields that are not set on the email will be taken from the defaults (properties). Any fields that are set on the
 * email will be used instead of the defaults.
 */
public interface EmailGovernance {
    /**
     * @return The effective email validator used for email validation. Can be <code>null</code> if no validation should be done.
     * @see MailerGenericBuilder#withEmailValidator(EmailValidator)
     * @see EmailValidator
     */
    @Nullable EmailValidator getEmailValidator();

    /**
     * @return Determines at what size Simple Java Mail should reject a MimeMessage. Useful if you know your SMTP server has a limit.
     * @see MailerGenericBuilder#withMaximumEmailSize(int)
     */
    @Nullable Integer getMaximumEmailSize();

    /**
     * This method will apply the defaults and overrides to the given email and return the result as a new instance. The original email is not modified.
     * <p>
     * Note that this is used automatically when sending or converting an email, so you don't need to call this yourself. This method might be useful
     * if you don't want to send the email, but just want to use a helper method or wish to inspect the email without sending (for the latter case
     * {@link org.simplejavamail.api.mailer.MailerRegularBuilder#withTransportModeLoggingOnly(Boolean)} might be of interest too).
     * <p>
     * Alternatively, you can also use {@link EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides()} or
     * {@link EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides(EmailGovernance)}.
     *
     * @param provided The email to apply the defaults and overrides to. If <code>null</code>, a new empty email will be created but will still be
     *                 populated with the defaults and overrides.
     */
    @NotNull
    EmailWithDefaultsAndOverridesApplied produceEmailApplyingDefaultsAndOverrides(@Nullable Email provided);
}