package org.simplejavamail.recipient;

import jakarta.mail.Message.RecipientType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.Recipient;

import java.security.cert.X509Certificate;

import static org.simplejavamail.recipient.RecipientException.MISSING_ADDRESS;

/**
 * @see IRecipientBuilder
 */
public class RecipientBuilder implements IRecipientBuilder {

    /**
     * @see IRecipientBuilder#withName(String)
     * @see IRecipientBuilder#clearingName()
     */
    @Nullable
    private String name;

    /**
     * @see IRecipientBuilder#withAddress(String)
     */
    @Nullable
    private String address;

    /**
     * @see IRecipientBuilder#withType(RecipientType)
     */
    @Nullable
    private RecipientType type;

    /**
     * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
     */
    @Nullable
    private X509Certificate smimeCertificate;

    /**
     * Creates a new builder instance, but first checks if address is set and throws an exception if not.
     *
     * @see Recipient#Recipient(String, String, RecipientType, X509Certificate)
     */
    @Override
    @NotNull
    public Recipient build() {
        if (address == null) {
            throw new RecipientException(MISSING_ADDRESS);
        }
        return new Recipient(name, address, type, smimeCertificate);
    }

    /**
     * @see IRecipientBuilder#withName(String)
     */
    @Override
    public IRecipientBuilder withName(@NotNull String name) {
        this.name = name;
        return this;
    }

    /**
     * @see IRecipientBuilder#clearingName()
     */
    @Override
    public IRecipientBuilder clearingName() {
        this.name = null;
        return this;
    }

    /**
     * @see IRecipientBuilder#withAddress(String)
     */
    @Override
    public IRecipientBuilder withAddress(@NotNull String address) {
        this.address = address;
        return this;
    }

    /**
     * @see IRecipientBuilder#withType(RecipientType)
     */
    @Override
    public IRecipientBuilder withType(@NotNull RecipientType type) {
        this.type = type;
        return this;
    }

    /**
     * @see IRecipientBuilder#withSmimeCertificate(X509Certificate)
     */
    @Override
    public IRecipientBuilder withSmimeCertificate(@NotNull X509Certificate smimeCertificate) {
        this.smimeCertificate = smimeCertificate;
        return this;
    }

    /**
     * @see IRecipientBuilder#clearingSmimeCertificate()
     */
    @Override
    public IRecipientBuilder clearingSmimeCertificate() {
        this.smimeCertificate = null;
        return this;
    }

    /**
     * @see IRecipientBuilder#getName()
     */
    @Override
    @Nullable
    public String getName() {
        return name;
    }

    /**
     * @see IRecipientBuilder#getAddress()
     */
    @Override
    @Nullable
    public String getAddress() {
        return address;
    }

    /**
     * @see IRecipientBuilder#getType()
     */
    @Override
    @Nullable
    public RecipientType getType() {
        return type;
    }

    /**
     * @see IRecipientBuilder#getSmimeCertificate()
     */
    @Override
    @Nullable
    public X509Certificate getSmimeCertificate() {
        return smimeCertificate;
    }
}