package org.simplejavamail.recipient;

import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.Recipient;

import java.security.cert.X509Certificate;

import static org.simplejavamail.internal.util.MiscUtil.defaultTo;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.recipient.RecipientException.MISSING_ADDRESS;

/**
 * @see IRecipientBuilder
 */
public class RecipientBuilder implements IRecipientBuilder {

    /**
     * @see IRecipientBuilder#withName(String)
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
     * @see IRecipientBuilder#build()
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
     * @see IRecipientBuilder#withType(RecipientType)
     */
    @Override
    public IRecipientBuilder withType(@NotNull RecipientType type) {
        this.type = checkNonEmptyArgument(type, "RecipientType");
        return this;
    }

    /**
     * @see IRecipientBuilder#withName(String)
     */
    @Override
    public IRecipientBuilder withName(@Nullable String name) {
        this.name = name;
        return this;
    }

    /**
     * @see IRecipientBuilder#withAddress(String)
     */
    @Override
    public IRecipientBuilder withAddress(@NotNull String oneOrMoreAddresses) {
        this.address = checkNonEmptyArgument(oneOrMoreAddresses, "oneOrMoreAddresses");
        return this;
    }

    /**
     * @see IRecipientBuilder#withAddressOnlyFrom(InternetAddress)
     */
    @Override
    public IRecipientBuilder withAddressOnlyFrom(@NotNull InternetAddress address) {
        return withAddress(checkNonEmptyArgument(address, "address").getAddress());
    }

    /**
     * @see IRecipientBuilder#withAddressOnlyFrom(String)
     */
    @Override
    public IRecipientBuilder withAddressOnlyFrom(@NotNull String oneOrMoreAddresses) {
        return interpretAddressData(null, false, checkNonEmptyArgument(oneOrMoreAddresses, "oneOrMoreAddresses"));
    }

    /**
     * @see IRecipientBuilder#withAddressAndNameOrDefault(InternetAddress, String)
     */
    @Override
    public IRecipientBuilder withAddressAndNameOrDefault(@NotNull InternetAddress address, @Nullable String defaultName) {
        return withAddressOnlyFrom(checkNonEmptyArgument(address, "address"))
                .withName(defaultTo(address.getPersonal(), defaultName));
    }

    /**
     * @see IRecipientBuilder#withAddressAndNameOrDefault(String, String)
     */
    @Override
    public IRecipientBuilder withAddressAndNameOrDefault(@NotNull String oneOrMoreAddresses, @Nullable String defaultName) {
        return interpretAddressData(defaultName, false, checkNonEmptyArgument(oneOrMoreAddresses, "oneOrMoreAddresses"));
    }

    /**
     * @see IRecipientBuilder#withAddressAndFixedNameOrProvided(InternetAddress, String)
     */
    @Override
    public IRecipientBuilder withAddressAndFixedNameOrProvided(@NotNull InternetAddress address, @Nullable String fixedName) {
        return withAddressOnlyFrom(checkNonEmptyArgument(address, "address"))
                .withName(defaultTo(fixedName, address.getPersonal()));
    }

    /**
     * @see IRecipientBuilder#withAddressAndFixedNameOrProvided(String, String)
     */
    @Override
    public IRecipientBuilder withAddressAndFixedNameOrProvided(@NotNull String oneOrMoreAddresses, @Nullable String fixedName) {
        return interpretAddressData(fixedName, true, checkNonEmptyArgument(oneOrMoreAddresses, "oneOrMoreAddresses"));
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
     * @param name         The name to use as fixed name or as default (depending on <code>fixedName</code> flag). Regardless of that flag, if a name
     *                     is <code>null</code>, the other one will be used.
     * @param fixedName    Determines if the given name should be used as override.
     * @param emailAddress An RFC2822 compliant email address, which can contain a name inside as well.
     *
     * @deprecated Temporary use until MiscUtil has a method for this that returns a Pair type of name and address.
     */
    @NotNull
    @Deprecated
    private IRecipientBuilder interpretAddressData(@Nullable final String name, boolean fixedName, @NotNull final String emailAddress) {
        try {
            final InternetAddress parsedAddress = InternetAddress.parse(emailAddress, false)[0];
            return withName((fixedName || parsedAddress.getPersonal() == null)
                    ? defaultTo(name, parsedAddress.getPersonal())
                    : defaultTo(parsedAddress.getPersonal(), name))
                    .withAddress(parsedAddress.getAddress());
        } catch (final AddressException e) {
            // InternetAddress failed to parse the email address even in non-strict mode
            // just assume the address was too complex rather than plain wrong, and let our own email validation
            // library take care of it when sending the email
            return withName(name)
                    .withAddress(emailAddress);
        }
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