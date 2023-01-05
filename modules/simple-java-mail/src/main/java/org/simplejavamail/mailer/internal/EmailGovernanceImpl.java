package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

/**
 * @see EmailGovernance
 */
@ToString
@AllArgsConstructor
@Getter()
class EmailGovernanceImpl implements EmailGovernance {
	/**
	 * @see EmailGovernance#getEmailValidator()
	 */
	@Nullable private final EmailValidator emailValidator;
	/**
	 * @see EmailGovernance#getPkcs12ConfigForSmimeSigning()
	 */
	@Nullable private final Pkcs12Config pkcs12ConfigForSmimeSigning;
}