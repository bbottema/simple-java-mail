package org.simplejavamail.mailer.internal;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.util.EnumSet;

/**
 * @see EmailGovernance
 */
class EmailGovernanceImpl implements EmailGovernance {
	@NotNull private final EnumSet<EmailAddressCriteria> emailAddressCriteria;
	@Nullable private final Pkcs12Config pkcs12ConfigForSmimeSigning;

	EmailGovernanceImpl(@NotNull final EnumSet<EmailAddressCriteria> emailAddressCriteria, @Nullable final Pkcs12Config pkcs12ConfigForSmimeSigning) {
		this.emailAddressCriteria = emailAddressCriteria;
		this.pkcs12ConfigForSmimeSigning = pkcs12ConfigForSmimeSigning;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("EmailGovernanceImpl{");
		sb.append("emailAddressCriteria=").append(emailAddressCriteria);
		sb.append(", pkcs12ConfigForSmimeSigning=").append(pkcs12ConfigForSmimeSigning);
		sb.append('}');
		return sb.toString();
	}

	/**
	 * @see EmailGovernance#getEmailAddressCriteria()
	 */
	@Override
	public @NotNull EnumSet<EmailAddressCriteria> getEmailAddressCriteria() {
		return emailAddressCriteria;
	}

	/**
	 * @see EmailGovernance#getPkcs12ConfigForSmimeSigning()
	 */
	@Override
	public @Nullable Pkcs12Config getPkcs12ConfigForSmimeSigning() {
		return pkcs12ConfigForSmimeSigning;
	}
}