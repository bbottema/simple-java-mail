package org.simplejavamail.api.mailer.config;

import com.sanctionco.jmail.EmailValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.internal.config.EmailProperty;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Governance for all emails being sent through the current {@link org.simplejavamail.api.mailer.Mailer} instance. That is, this class represents actions
 * taken or configuration used by default for each individual email sent through the current mailer. For example, you might want to S/MIME sign all emails
 * by default. You <em>can</em> do it manually on each email of course, but then the keystore used for this is not reused.
 * <p>
 * Also, you can supply a custom {@link org.simplejavamail.api.email.Email email} instance which will be used for <em>defaults</em> or <em>overrides</em>. For example,
 * you can set a default from address or subject. Any fields that are not set on the email will be taken from the defaults (properties). Any fields that are set on the
 * email will be used instead of the defaults.
 */
public interface EmailGovernance {
    /**
     * @return The effective email validator used for email validation. Can be <code>null</code> if no validation should be done.
     * @see MailerGenericBuilder#withEmailValidator(EmailValidator)
     * @see EmailValidator
     */
    @Nullable EmailValidator getEmailValidator();

    /**
     * @return Determines at what size Simple Java Mail should reject a MimeMessage. Useful if you know your SMTP server has a limit.
     * @see MailerGenericBuilder#withMaximumEmailSize(int)
     */
    @Nullable Integer getMaximumEmailSize();

    /**
     * Resolves a property by first checking overrides (if the override wasn't disabled globally, or for this property specifically), then checking the email itself, and finally
     * checking the defaults (again checking if it was disabled). If the property is not set on any of these, <code>null</code> is returned.
     */
    @Nullable <T> T resolveEmailProperty(@Nullable Email email, @NotNull EmailProperty emailProperty);

    /**
     * Resolves a collection property by first checking overrides (if the override wasn't disabled globally, or for this property specifically), then checking the email itself, and finally
     * checking the defaults (again checking if it was disabled). If the property is not set on any of these, an empty <code>List</code> is returned.
     * <br>
     * The collections are merged from these sources, with the overrides taking precedence.
     */
    @NotNull <T> List<T> resolveEmailCollectionProperty(@Nullable Email email, @NotNull EmailProperty emailProperty);

    /**
     * Specifically resolves the headers by first checking overrides (if the override wasn't disabled globally, or for this property specifically), then checking the email itself, and finally
     * checking the defaults (again checking if it was disabled). If the property is not set on any of these, an empty <code>List</code> is returned.
     * <br>
     * The header maps are merged from these sources, with the overrides taking precedence. The keys are added to the map, but their associated value collections are not merged, but replaced.
     */
    Map<String, Collection<String>> resolveEmailHeadersProperty(@Nullable Email email);
}