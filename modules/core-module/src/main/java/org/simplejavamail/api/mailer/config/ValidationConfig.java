package org.simplejavamail.api.mailer.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.EmailTooBigException;
import com.sanctionco.jmail.EmailValidator;
import com.sanctionco.jmail.JMail;
import static java.util.Objects.requireNonNull;

/**
 * Immutable supplied ValidationConfig choices. An unset warning-only choice inherits the owning Mailer factory snapshot.
 * Validator instances and maximum-size limits are Java-only choices; their fallbacks are the strict JMail validator and no size limit.
 * Construction and inspection never start workers, connect, resolve configuration sources or own borrowed resources.
 * Replacing this group on a Mailer discards its previous supplied choices.
 */
@Value
public class ValidationConfig {
    @Nullable Boolean clientValidationWarningsOnly;
    @Nullable EmailValidator emailValidator;
    boolean emailValidatorCleared;
    @Nullable Integer maximumEmailSize;
    boolean maximumEmailSizeCleared;

    private ValidationConfig(final Builder builder) {
        clientValidationWarningsOnly = builder.clientValidationWarningsOnly;
        emailValidator = builder.emailValidator;
        emailValidatorCleared = builder.emailValidatorCleared;
        maximumEmailSize = builder.maximumEmailSize;
        maximumEmailSizeCleared = builder.maximumEmailSizeCleared;
    }

    /** @return A builder containing no supplied choices. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining supplied choices and borrowed-resource identities. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /** @see Builder#withClientValidationWarningsOnly(boolean) */
    @Nullable public Boolean getClientValidationWarningsOnly() { return clientValidationWarningsOnly; }
    /** @see Builder#withEmailValidator(EmailValidator) */
    @Nullable public EmailValidator getEmailValidator() { return emailValidator; }
    /**
     * Whether this group explicitly removes the validator instead of leaving the strict-validator fallback in place.
     * @see Builder#clearEmailValidator()
     */
    public boolean isEmailValidatorCleared() { return emailValidatorCleared; }
    /** @see Builder#withMaximumEmailSize(int) */
    @Nullable public Integer getMaximumEmailSize() { return maximumEmailSize; }
    /**
     * Whether this group explicitly cleared its Java-only size-limit choice.
     * @see Builder#clearMaximumEmailSize()
     */
    public boolean isMaximumEmailSizeCleared() { return maximumEmailSizeCleared; }

    /** Explicit choices for this group; inspection does not build or validate an incomplete configuration. */
    public static final class Builder {
        private Boolean clientValidationWarningsOnly;
        private EmailValidator emailValidator;
        private boolean emailValidatorCleared;
        private Integer maximumEmailSize;
        private boolean maximumEmailSizeCleared;
        private Builder() { }
        private Builder(final ValidationConfig config) {
            clientValidationWarningsOnly = config.clientValidationWarningsOnly;
            emailValidator = config.emailValidator;
            emailValidatorCleared = config.emailValidatorCleared;
            maximumEmailSize = config.maximumEmailSize;
            maximumEmailSizeCleared = config.maximumEmailSizeCleared;
        }
        /**
         * Controls whether client-side validation findings block sending or are reported as warnings.
         * <p>
         * When set to {@code true}, sender and recipient completeness checks, the configured email address validator and CRLF injection scans still run,
         * but their findings are logged and sending continues. The default {@code false} keeps these validations blocking.
         * Maximum-size enforcement, security processing and other message-preparation failures keep their independent behavior.
         * <p>
         * An unset choice inherits captured configuration properties before the library fallback. Explicit {@code false} selects blocking behavior,
         * even when properties enable warning-only behavior. {@link #resetClientValidationWarningsOnly()} selects the library fallback;
         * {@link #useConfiguredClientValidationWarningsOnly()} restores inheritance. Configuration locks remain binding.
         *
         * @param clientValidationWarningsOnly Whether client-side validation findings should be non-blocking. Defaults to
         *                                           {@value org.simplejavamail.api.mailer.MailerGenericBuilder#DEFAULT_DISABLE_ALL_CLIENTVALIDATION}.
         */
        @NotNull public Builder withClientValidationWarningsOnly(final boolean clientValidationWarningsOnly) { this.clientValidationWarningsOnly = clientValidationWarningsOnly; return this; }
        /** @see #withClientValidationWarningsOnly(boolean) */
        @Nullable public Boolean getClientValidationWarningsOnly() { return clientValidationWarningsOnly; }
        /**
         * Selects the published library fallback.
         * @see #withClientValidationWarningsOnly(boolean)
         */
        @NotNull public Builder resetClientValidationWarningsOnly() { return withClientValidationWarningsOnly(MailerGenericBuilder.DEFAULT_DISABLE_ALL_CLIENTVALIDATION); }
        /**
         * Restores captured configuration for this member.
         * @see #withClientValidationWarningsOnly(boolean)
         */
        @NotNull public Builder useConfiguredClientValidationWarningsOnly() { clientValidationWarningsOnly = null; return this; }
        /**
         * Sets the email address validator used when validating and sending emails using the current <code>Mailer</code> instance.
         * <p>
         * Defaults to {@link JMail#strictValidator()}.
         *
         * @see EmailValidator
         * @see #clearEmailValidator()
         * @see #resetEmailValidator()
         * @param emailValidator Supplied Java validator; an untouched choice uses {@link JMail#strictValidator()}.
         */
        @NotNull public Builder withEmailValidator(@NotNull final EmailValidator emailValidator) { this.emailValidator = requireNonNull(emailValidator, "emailValidator"); emailValidatorCleared = false; return this; }
        /** @see #withEmailValidator(EmailValidator) */
        @Nullable public EmailValidator getEmailValidator() { return emailValidator; }
        /**
         * Removes the configurable JMail address validator. Required sender and recipient checks,
         * encoded-word address protection and CRLF injection scanning remain active. This does not change whether findings block sending.
         * @see #withEmailValidator(EmailValidator)
         */
        @NotNull public Builder clearEmailValidator() { emailValidator = null; emailValidatorCleared = true; return this; }
        /**
         * Selects the published library fallback.
         * @see #withEmailValidator(EmailValidator)
         */
        @NotNull public Builder resetEmailValidator() { return withEmailValidator(JMail.strictValidator()); }
        /**
         * Restores the strict JMail validator by removing the supplied Java choice. No property-backed validator instance is loaded.
         * @see #withEmailValidator(EmailValidator)
         */
        @NotNull public Builder useConfiguredEmailValidator() { emailValidator = null; emailValidatorCleared = false; return this; }
        /**
         * Sets a maximum size for emails (as MimeMessage) in bytes. If an email exceeds this size, exception @{@link EmailTooBigException} will be thrown (as the cause).
         *
         * @param maximumEmailSize Maximum size of an email (as MimeMessage) in bytes.
         * @see #clearMaximumEmailSize()
         */
        @NotNull public Builder withMaximumEmailSize(final int maximumEmailSize) { this.maximumEmailSize = maximumEmailSize; maximumEmailSizeCleared = false; return this; }
        /** @see #withMaximumEmailSize(int) */
        @Nullable public Integer getMaximumEmailSize() { return maximumEmailSize; }
        /**
         * Removes the supplied maximum-size limit, so no maximum-email-size check is performed.
         * @see #withMaximumEmailSize(int)
         */
        @NotNull public Builder clearMaximumEmailSize() { maximumEmailSize = null; maximumEmailSizeCleared = true; return this; }
        /**
         * Removes the supplied maximum-size limit, restoring the unlimited fallback. No property-backed size limit is loaded.
         * @see #withMaximumEmailSize(int)
         */
        @NotNull public Builder useConfiguredMaximumEmailSize() { maximumEmailSize = null; maximumEmailSizeCleared = false; return this; }
        /** @return An immutable snapshot; feature validation remains with its owning runtime. */
        @NotNull public ValidationConfig build() { return new ValidationConfig(this); }
    }
}
