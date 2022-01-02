package org.simplejavamail.api.mailer.config;

import com.sanctionco.jmail.EmailValidator;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.io.InputStream;

/**
 * Governance for all emails being sent through the current {@link org.simplejavamail.api.mailer.Mailer} instance.
 * <p>
 * In simpeler terms: this class represents actions taken or configuration used by default for each individual email sent through the current mailer. For example, you might want to S/MIME sign all
 * emails by default. You can do it manually on each email of course, but then the keystore used for this not reused.
 */
public interface EmailGovernance {

	/**
	 * @return The effective email validator used for email validation. Can be <code>null</code> if no validation should be done.
	 * @see MailerGenericBuilder#withEmailValidator(EmailValidator)
	 * @see EmailValidator
	 */
	@Nullable
	EmailValidator getEmailValidator();

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(Pkcs12Config)
	 * @see EmailPopulatingBuilder#signWithSmime(InputStream, String, String, String)
	 * @see MailerGenericBuilder#signByDefaultWithSmime(Pkcs12Config)
	 * @see MailerGenericBuilder#signByDefaultWithSmime(InputStream, String, String, String)
	 */
	@Nullable
	Pkcs12Config getPkcs12ConfigForSmimeSigning();
}