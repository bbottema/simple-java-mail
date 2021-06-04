package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

/**
 * @see EmailGovernance
 */
class EmailGovernanceImpl implements EmailGovernance {
	@Nullable private final EmailValidator emailValidator;
	@Nullable private final Pkcs12Config pkcs12ConfigForSmimeSigning;

	EmailGovernanceImpl(@Nullable final EmailValidator emailValidator, @Nullable final Pkcs12Config pkcs12ConfigForSmimeSigning) {
		this.emailValidator = emailValidator;
		this.pkcs12ConfigForSmimeSigning = pkcs12ConfigForSmimeSigning;
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder("EmailGovernanceImpl{");
		sb.append("emailValidator=").append(emailValidator);
		sb.append(", pkcs12ConfigForSmimeSigning=").append(pkcs12ConfigForSmimeSigning);
		sb.append('}');
		return sb.toString();
	}

	/**
	 * @see EmailGovernance#getEmailValidator()
	 */
	@Override
	public @Nullable EmailValidator getEmailValidator() {
		return emailValidator;
	}

	/**
	 * @see EmailGovernance#getPkcs12ConfigForSmimeSigning()
	 */
	@Override
	public @Nullable Pkcs12Config getPkcs12ConfigForSmimeSigning() {
		return pkcs12ConfigForSmimeSigning;
	}
}