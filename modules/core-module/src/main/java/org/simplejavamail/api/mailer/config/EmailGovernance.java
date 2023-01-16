package org.simplejavamail.api.mailer.config;

import com.sanctionco.jmail.EmailValidator;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.io.InputStream;

/**
 * Governance for all emails being sent through the current {@link org.simplejavamail.api.mailer.Mailer} instance. That is, this class represents actions
 * taken or configuration used by default for each individual email sent through the current mailer. For example, you might want to S/MIME sign all emails
 * by default. You <em>can</em> do it manually on each email of course, but then the keystore used for this is not reused.
 * <p>
 * Also, you can supply a custom {@link org.simplejavamail.api.email.Email email} instance which will be used for defaults. For example,
 * you can set a default from address or subject.
 * <p>
 * You can set this on the {@code MailerBuilder} using {@code MailerBuilder.withEmailGovernance(EmailGovernance)}.
 */
@ToString
@AllArgsConstructor
@Getter()
public class EmailGovernance {

	public static final EmailGovernance NO_GOVERNANCE = new EmailGovernance(null, null, null, null);

	/**
	 * The effective email validator used for email validation. Can be <code>null</code> if no validation should be done.
	 * @see MailerGenericBuilder#withEmailValidator(EmailValidator)
	 * @see EmailValidator
	 */
	@Nullable private final EmailValidator emailValidator;

	/**
	 * @see EmailPopulatingBuilder#signWithSmime(Pkcs12Config)
	 * @see EmailPopulatingBuilder#signWithSmime(InputStream, String, String, String)
	 * @see MailerGenericBuilder#signByDefaultWithSmime(Pkcs12Config)
	 * @see MailerGenericBuilder#signByDefaultWithSmime(InputStream, String, String, String)
	 */
	@Nullable private final Pkcs12Config pkcs12ConfigForSmimeSigning;

	/**
	 * Reference email used for defaults if no fields are not filled in the email but are on this instance.
	 * Can be <code>null</code> if no defaults should be used.
	 * @see MailerGenericBuilder#withEmailDefaults(Email)
	 */
	@Nullable private final Email emailDefaults;

	/**
	 * Reference email used for overrides. Values from this email will trump the incoming email.
	 * Can be <code>null</code> if no overrides should be used.
	 * @see MailerGenericBuilder#withEmailOverrides(Email)
	 */
	@Nullable private final Email emailOverrides;
}