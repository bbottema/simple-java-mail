package org.simplejavamail.api.email.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProperty;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Collections;
import java.io.Serializable;

import static java.util.Objects.requireNonNull;

/**
 * Immutable choices for suppressing ordinary Email defaults and overrides. Mandatory configuration locks always remain binding.
 * This value records choices without reading configuration sources or message content.
 * @see EmailPopulatingBuilder#withTemplateOptions(EmailTemplateOptions)
 */
@Value
public class EmailTemplateOptions implements Serializable {
    private static final long serialVersionUID = 1L;
    boolean ignoreDefaults;
    boolean ignoreOverrides;
    @Nullable Set<EmailProperty> propertiesNotToApplyDefaultValueFor;
    @Nullable Set<EmailProperty> propertiesNotToApplyOverrideValueFor;

    private EmailTemplateOptions(final Builder builder) {
        ignoreDefaults = builder.ignoreDefaults;
        ignoreOverrides = builder.ignoreOverrides;
        propertiesNotToApplyDefaultValueFor = immutableCopy(builder.propertiesNotToApplyDefaultValueFor);
        propertiesNotToApplyOverrideValueFor = immutableCopy(builder.propertiesNotToApplyOverrideValueFor);
    }
    @Nullable private static Set<EmailProperty> immutableCopy(@Nullable final Set<EmailProperty> values) {
        return values == null ? null : Collections.unmodifiableSet(new HashSet<>(values));
    }
    /** @return A builder that permits ordinary defaults and overrides. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining these suppression choices. */
    @NotNull public Builder toBuilder() { return new Builder(this); }
    /** @see Builder#ignoringDefaults(boolean) */
    public boolean isIgnoreDefaults() { return ignoreDefaults; }
    /** @see Builder#ignoringOverrides(boolean) */
    public boolean isIgnoreOverrides() { return ignoreOverrides; }
    /**
     * @return An unmodifiable, detached set of supplied exclusions, or {@code null} when none was supplied.
     * @see Builder#dontApplyDefaultValueFor(EmailProperty...)
     */
    @Nullable public Set<EmailProperty> getPropertiesNotToApplyDefaultValueFor() { return propertiesNotToApplyDefaultValueFor; }
    /**
     * @return An unmodifiable, detached set of supplied exclusions, or {@code null} when none was supplied.
     * @see Builder#dontApplyOverrideValueFor(EmailProperty...)
     */
    @Nullable public Set<EmailProperty> getPropertiesNotToApplyOverrideValueFor() { return propertiesNotToApplyOverrideValueFor; }

    /** Collects suppression choices independently from messages, templates and configuration locks. */
    public static final class Builder {
        private boolean ignoreDefaults = EmailPopulatingBuilder.DEFAULT_IGNORE_DEFAULTS;
        private boolean ignoreOverrides = EmailPopulatingBuilder.DEFAULT_IGNORE_OVERRIDES;
        private Set<EmailProperty> propertiesNotToApplyDefaultValueFor;
        private Set<EmailProperty> propertiesNotToApplyOverrideValueFor;
        private Builder() { }
        private Builder(final EmailTemplateOptions options) {
            ignoreDefaults = options.ignoreDefaults;
            ignoreOverrides = options.ignoreOverrides;
            propertiesNotToApplyDefaultValueFor = options.propertiesNotToApplyDefaultValueFor == null ? null : new HashSet<>(options.propertiesNotToApplyDefaultValueFor);
            propertiesNotToApplyOverrideValueFor = options.propertiesNotToApplyOverrideValueFor == null ? null : new HashSet<>(options.propertiesNotToApplyOverrideValueFor);
        }
        /**
         * Configures this email to ignore all default values when governance is applied.
         *
         * @return This builder.
         * @see #ignoringDefaults(boolean)
         * @see #dontApplyDefaultValueFor(EmailProperty...)
         */
        @NotNull public Builder ignoringDefaults() { return ignoringDefaults(true); }

        /**
         * Sets whether this email should ignore all default values when governance is applied.
         *
         * {@link #resetIgnoreDefaults()} restores the published participation fallback; property exclusions remain independent.
         * Mandatory locks cannot be suppressed.
         *
         * @param ignoreDefaults Whether to ignore all default values or not for this email.
         *
         * @see #dontApplyDefaultValueFor(EmailProperty...)
         * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withEmailDefaults(Email)
         */
        @NotNull public Builder ignoringDefaults(boolean ignoreDefaults) { this.ignoreDefaults = ignoreDefaults; return this; }

        /** @see #ignoringDefaults(boolean) */
        public boolean isIgnoreDefaults() { return ignoreDefaults; }
        /** @see #ignoringDefaults(boolean) */
        @NotNull public Builder resetIgnoreDefaults() { return ignoringDefaults(EmailPopulatingBuilder.DEFAULT_IGNORE_DEFAULTS); }

        /**
         * Configures this email to ignore all override values when governance is applied.
         *
         * @return This builder.
         * @see #ignoringOverrides(boolean)
         * @see #dontApplyOverrideValueFor(EmailProperty...)
         */
        @NotNull public Builder ignoringOverrides() { return ignoringOverrides(true); }

        /**
         * Sets whether this email should ignore all override values when governance is applied.
         *
         * {@link #resetIgnoreOverrides()} restores the published participation fallback; property exclusions remain independent.
         * Mandatory locks cannot be suppressed.
         *
         * @param ignoreOverrides Whether to ignore all override values or not for this email.
         *
         * @see #dontApplyOverrideValueFor(EmailProperty...)
         * @see org.simplejavamail.api.mailer.MailerRegularBuilder#withEmailOverrides(Email)
         */
        @NotNull public Builder ignoringOverrides(boolean ignoreOverrides) { this.ignoreOverrides = ignoreOverrides; return this; }

        /** @see #ignoringOverrides(boolean) */
        public boolean isIgnoreOverrides() { return ignoreOverrides; }
        /** @see #ignoringOverrides(boolean) */
        @NotNull public Builder resetIgnoreOverrides() { return ignoringOverrides(EmailPopulatingBuilder.DEFAULT_IGNORE_OVERRIDES); }

        /**
         * Allows you to prevent a property to be configured with default values. This might be useful if you have defined defaults (either through (system) properties,
         * config files, or on mailer level on the {@link org.simplejavamail.api.mailer.config.EmailGovernance}), but for a specific case or under certain conditions
         * you might want to have this email untouched by the defaults.
         * Repeated calls add to the existing local exclusions; an empty call changes nothing. Use {@link #allowDefaultValueFor(EmailProperty...)}
         * to remove individual exclusions or {@link #clearDefaultValueSuppressions()} to remove them all. These operations do not change
         * {@link #ignoringDefaults(boolean)} or configuration locks.
         * <br>
         * <strong>Note:</strong> This is irrelevant for Email instances used to set on {@link org.simplejavamail.api.mailer.config.EmailGovernance}
         * as defaults or overrides reference.
         *
         * @param configProperties The properties that should not be configured with default values, if any, when sending the email.
         * @see #ignoringDefaults()
         * @see #ignoringDefaults(boolean)
         */
        @NotNull public Builder dontApplyDefaultValueFor(@NotNull EmailProperty @NotNull ...configProperties) { requireNonNull(configProperties, "configProperties"); if (configProperties.length > 0) { if (propertiesNotToApplyDefaultValueFor == null) propertiesNotToApplyDefaultValueFor = new HashSet<>(); propertiesNotToApplyDefaultValueFor.addAll(Arrays.asList(configProperties)); } return this; }

        /**
         * Removes the supplied properties from this builder's local default exclusions. Missing properties and an empty call change nothing.
         * Global default suppression and configuration locks remain unchanged.
         * @see #dontApplyDefaultValueFor(EmailProperty...)
         */
        @NotNull public Builder allowDefaultValueFor(@NotNull EmailProperty @NotNull ...configProperties) { requireNonNull(configProperties, "configProperties"); if (propertiesNotToApplyDefaultValueFor != null) propertiesNotToApplyDefaultValueFor.removeAll(Arrays.asList(configProperties)); return this; }

        /**
         * Removes all local default exclusions without changing global default suppression or configuration locks.
         * @see #dontApplyDefaultValueFor(EmailProperty...)
         */
        @NotNull public Builder clearDefaultValueSuppressions() { propertiesNotToApplyDefaultValueFor = new HashSet<>(); return this; }

        /**
         * @return A detached, modifiable copy of the supplied exclusions, or {@code null} when none was supplied. Editing it does not configure this builder.
         * @see #dontApplyDefaultValueFor(EmailProperty...)
         */
        @Nullable public Set<EmailProperty> getPropertiesNotToApplyDefaultValueFor() { return propertiesNotToApplyDefaultValueFor == null ? null : new HashSet<>(propertiesNotToApplyDefaultValueFor); }
        /**
         * Allows you to prevent a property to be configured with override values. This might be useful if you have defined overrides on mailer level on the
         * {@link org.simplejavamail.api.mailer.config.EmailGovernance}), but for a specific case or under certain conditions you might want
         * to have this email untouched by the overrides.
         * Repeated calls add to the existing local exclusions; an empty call changes nothing. Use {@link #allowOverrideValueFor(EmailProperty...)}
         * to remove individual exclusions or {@link #clearOverrideValueSuppressions()} to remove them all. These operations do not change
         * {@link #ignoringOverrides(boolean)} or configuration locks.
         *
         * @param configProperties The properties that should not be overridden when sending the email.
         */
        @NotNull public Builder dontApplyOverrideValueFor(@NotNull EmailProperty @NotNull ...configProperties) { requireNonNull(configProperties, "configProperties"); if (configProperties.length > 0) { if (propertiesNotToApplyOverrideValueFor == null) propertiesNotToApplyOverrideValueFor = new HashSet<>(); propertiesNotToApplyOverrideValueFor.addAll(Arrays.asList(configProperties)); } return this; }

        /**
         * Removes the supplied properties from this builder's local override exclusions. Missing properties and an empty call change nothing.
         * Global override suppression and configuration locks remain unchanged.
         * @see #dontApplyOverrideValueFor(EmailProperty...)
         */
        @NotNull public Builder allowOverrideValueFor(@NotNull EmailProperty @NotNull ...configProperties) { requireNonNull(configProperties, "configProperties"); if (propertiesNotToApplyOverrideValueFor != null) propertiesNotToApplyOverrideValueFor.removeAll(Arrays.asList(configProperties)); return this; }

        /**
         * Removes all local override exclusions without changing global override suppression or configuration locks.
         * @see #dontApplyOverrideValueFor(EmailProperty...)
         */
        @NotNull public Builder clearOverrideValueSuppressions() { propertiesNotToApplyOverrideValueFor = new HashSet<>(); return this; }

        /**
         * @return A detached, modifiable copy of the supplied exclusions, or {@code null} when none was supplied. Editing it does not configure this builder.
         * @see #dontApplyOverrideValueFor(EmailProperty...)
         */
        @Nullable public Set<EmailProperty> getPropertiesNotToApplyOverrideValueFor() { return propertiesNotToApplyOverrideValueFor == null ? null : new HashSet<>(propertiesNotToApplyOverrideValueFor); }
        /** @return An immutable snapshot with detached suppression sets. */
        @NotNull public EmailTemplateOptions build() { return new EmailTemplateOptions(this); }
    }
}
