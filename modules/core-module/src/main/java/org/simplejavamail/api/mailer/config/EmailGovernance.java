package org.simplejavamail.api.mailer.config;

import org.hazlewood.connor.bottema.emailaddress.EmailAddressCriteria;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.io.InputStream;
import java.util.EnumSet;

/**
 * Governance for all emails being sent through the current {@link org.simplejavamail.api.mailer.Mailer} instance.
 * <p>
 * In simpeler terms: this class represents actions taken or configuration used by default for each individual email sent through the current mailer. For example, you might want to S/MIME sign all
 * emails by default. You can do it manually on each email of course, but then the keystore used for this not reused.
 */
public interface EmailGovernance {

	/**
	 * @return The effective validation criteria used for email validation. Returns an empty set if no validation should be done.
	 * @see MailerGenericBuilder#withEmailAddressCriteria(EnumSet)
	 * @see EmailAddressCriteria
	 */
	@NotNull
	EnumSet<EmailAddressCriteria> getEmailAddressCriteria();

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(Pkcs12Config)
	 * @see EmailPopulatingBuilder#signWithSmime(InputStream, String, String, String)
	 * @see MailerGenericBuilder#signByDefaultWithSmime(Pkcs12Config)
	 * @see MailerGenericBuilder#signByDefaultWithSmime(InputStream, String, String, String)
	 */
	@Nullable
	Pkcs12Config getPkcs12ConfigForSmimeSigning();
}
