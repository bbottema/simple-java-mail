package org.simplejavamail.recipient;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.Recipients;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @see IRecipientsBuilder
 */
public class RecipientsBuilder implements IRecipientsBuilder {

    /**
     * @see IRecipientsBuilder#withRecipient(Recipient)
     * @see IRecipientsBuilder#withRecipients(Recipient...)
     */
    @NotNull
    private final List<Recipient> recipients = new ArrayList<>();

    /**
     * @see IRecipientsBuilder#withDefaultName(String)
     */
    @Nullable
    private String defaultName;

    /**
     * @see IRecipientsBuilder#withOverridingName(String)
     */
    @Nullable
    private String overridingName;

    /**
     * @see IRecipientsBuilder#withDefaultSmimeCertificate(X509Certificate)
     */
    @Nullable
    private X509Certificate defaultSmimeCertificate;

    /**
     * @see IRecipientsBuilder#withDefaultName(String)
     */
    @Override
    public IRecipientsBuilder withDefaultName(@Nullable String defaultName) {
        this.defaultName = defaultName;
        return this;
    }

    /**
     * @see IRecipientsBuilder#withOverridingName(String)
     */
    @Override
    public IRecipientsBuilder withOverridingName(@Nullable String overridingName) {
        this.overridingName = overridingName;
        return this;
    }

    /**
     * @see IRecipientsBuilder#withRecipient(Recipient)
     * @see IRecipientsBuilder#withRecipients(Recipient...)
     */
    @Override
    public IRecipientsBuilder withRecipient(@NotNull Recipient recipient) {
        recipients.add(recipient);
        return this;
    }

    /**
     * @see IRecipientsBuilder#withRecipient(Recipient)
     * @see IRecipientsBuilder#withRecipients(Recipient...)
     */
    @Override
    public IRecipientsBuilder withRecipients(@NotNull Recipient... recipients) {
        this.recipients.addAll(Arrays.asList(recipients));
        return this;
    }

    /**
     * @see IRecipientsBuilder#withDefaultSmimeCertificate(X509Certificate)
     */
    @Override
    public IRecipientsBuilder withDefaultSmimeCertificate(@Nullable X509Certificate defaultSmimeCertificate) {
        this.defaultSmimeCertificate = defaultSmimeCertificate;
        return this;
    }

    /**
     * @see IRecipientsBuilder#build()
     */
    @NotNull
    public Recipients build() {
        return new Recipients(recipients, defaultName, overridingName, defaultSmimeCertificate);
    }

    /**
     * @see IRecipientsBuilder#withDefaultName(String)
     */
    @Override
    @Nullable
    public String getDefaultName() {
        return defaultName;
    }

    /**
     * @see IRecipientsBuilder#withOverridingName(String)
     */
    @Override
    @Nullable
    public String getOverridingName() {
        return overridingName;
    }

    /**
     * @see IRecipientsBuilder#withRecipients(Recipient...)
     */
    @Override
    @NotNull
    public List<Recipient> getRecipients() {
        return recipients;
    }

    /**
     * @see IRecipientsBuilder#withDefaultSmimeCertificate(X509Certificate)
     */
    @Override
    @Nullable
    public X509Certificate getDefaultSmimeCertificate() {
        return defaultSmimeCertificate;
    }
}