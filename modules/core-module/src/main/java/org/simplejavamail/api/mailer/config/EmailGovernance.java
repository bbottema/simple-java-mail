package org.simplejavamail.api.mailer.config;

import com.sanctionco.jmail.EmailValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
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
     * @return The effective JMail address validator. Can be <code>null</code> when the mailer uses its remaining completeness, encoded-word and CRLF
     * checks without a configurable address policy.
     * @see org.simplejavamail.api.mailer.config.ValidationConfig.Builder#withEmailValidator(EmailValidator)
     * @see EmailValidator
     */
    @Nullable EmailValidator getEmailValidator();

    /**
     * @return Determines at what size Simple Java Mail should reject a MimeMessage. Useful if you know your SMTP server has a limit.
     * @see org.simplejavamail.api.mailer.config.ValidationConfig.Builder#withMaximumEmailSize(int)
     */
    @Nullable Integer getMaximumEmailSize();

    /**
     * This method will apply the defaults and overrides to the given email and return the result as a new instance. The original email is not modified.
     * Each ordinary completion can further enrich an already-completed Email, including with another Mailer's templates. Scalars resolve
     * in override, supplied, then default order, subject to the supplied Email's suppression choices. Applicable configuration locks remain
     * binding regardless of suppression or earlier completion; conflicting fixed values are rejected and mandatory additions are enforced.
     * <p>
     * To, Cc, Bcc, Reply-To, delivery-recipient overrides, attachments and embedded images add only missing template values. New override
     * entries precede every supplied entry; new default entries follow them. Supplied duplicates and their order remain intact. Incoming
     * entries already represented by the supplied collection or an earlier template addition are skipped. Repeating the same completion
     * therefore leaves recognized entries unchanged. Headers still replace the complete value collection of each case-insensitive name,
     * and compound settings such as signing or delivery-notification policies remain whole-value choices.
     * <p>
     * Recipients match by their complete value, including name, role, certificate and notification preferences, accounting for missing names
     * supplied by applicable locks. Explicit conflicting names still fail lock validation. Attachments and images match
     * by metadata and recognized source identity: the same resource or DataSource, the same retained library-owned path, or equal
     * library-owned byte content. Different names, descriptions, Content-IDs, MIME types or encoding choices remain distinct where applicable.
     * Duplicate detection does not read source streams, probe files or inspect arbitrary DataSource metadata. Unknown sources are retained,
     * even when their filenames look alike. This matching does not change public AttachmentResource equality.
     * <p>
     * Global and per-property suppression survives completion and copying. A reference template's own suppression does not create another
     * inheritance layer. Completion does not freeze generated MIME, Message-IDs or signing output; exact EML and retry snapshots keep their
     * separate preservation contracts.
     * <p>
     * Sending applies governance automatically. Ordinary conversion does not: use
     * {@link org.simplejavamail.api.converter.ConfiguredEmailConverter.EmailConversion#withEmailGovernance(EmailGovernance)}
     * when an export should apply these templates. You can also call this method to inspect the governed Email without sending it.
     * <p>
     * Alternatively, you can also use {@link EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides()} or
     * {@link EmailPopulatingBuilder#buildEmailCompletedWithDefaultsAndOverrides(EmailGovernance)}.
     *
     * @param provided The email to apply the defaults and overrides to. If <code>null</code>, a new empty email will be created but will still be
     *                 populated with the defaults and overrides.
     */
    @NotNull
    Email produceEmailApplyingDefaultsAndOverrides(@Nullable Email provided);
}
