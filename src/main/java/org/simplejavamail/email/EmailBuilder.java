package org.simplejavamail.email;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.email.internal.EmailPopulatingBuilderImpl;
import org.simplejavamail.email.internal.EmailStartingBuilderImpl;

import javax.annotation.Nonnull;
import javax.mail.internet.MimeMessage;

/**
 * Builder starter which offers initial starting places after which control is passed on to the {@link EmailPopulatingBuilderImpl} for filling in details.
 * <p>
 * Starter methods are all variations of one of the following:
 * <ul>
 * <li>startingBlank()</li>
 * <li>replyingTo()</li>
 * <li>replyingToAll()</li>
 * <li>forwarding()</li>
 * <li>copying()</li>
 * </ul>
 */
public class EmailBuilder {
	
	/**
	 * Configures this builder to create an email ignoring the normal (optional) defaults that apply from property config files.
	 */
	@SuppressWarnings("deprecation")
	public static EmailStartingBuilder ignoringDefaults() {
		return new EmailStartingBuilderImpl().ignoringDefaults();
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#replyingTo(Email)}.
	 */
	@SuppressWarnings("deprecation")
	public static EmailPopulatingBuilder replyingTo(@Nonnull final Email email) {
		return new EmailStartingBuilderImpl().replyingTo(email);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#replyingToAll(Email)}.
	 */
	@SuppressWarnings("deprecation")
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final Email email) {
		return new EmailStartingBuilderImpl().replyingToAll(email);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#replyingToAll(Email, String)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return new EmailStartingBuilderImpl().replyingToAll(email, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#replyingTo(Email, String)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder replyingTo(@Nonnull final Email email, @Nonnull final String customQuotingTemplate) {
		return new EmailStartingBuilderImpl().replyingTo(email, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#replyingTo(MimeMessage)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email) {
		return new EmailStartingBuilderImpl().replyingTo(email);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#replyingToAll(MimeMessage, String)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
		return new EmailStartingBuilderImpl().replyingToAll(email, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#replyingTo(MimeMessage, String)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder replyingTo(@Nonnull final MimeMessage email, @Nonnull final String customQuotingTemplate) {
		return new EmailStartingBuilderImpl().replyingTo(email, customQuotingTemplate);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#replyingToAll(MimeMessage)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder replyingToAll(@Nonnull final MimeMessage email) {
		return new EmailStartingBuilderImpl().replyingToAll(email);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#forwarding(Email)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder forwarding(@Nonnull final Email email) {
		return new EmailStartingBuilderImpl().forwarding(email);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#forwarding(MimeMessage)}.
	 */
	@SuppressWarnings("deprecation")
	public static EmailPopulatingBuilder forwarding(@Nonnull final MimeMessage emailMessage) {
		return new EmailStartingBuilderImpl().forwarding(emailMessage);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#copying(MimeMessage)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder copying(@Nonnull final MimeMessage message) {
		return new EmailStartingBuilderImpl().copying(message);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#copying(EmailPopulatingBuilder)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder copying(@Nonnull final EmailPopulatingBuilder emailBuilder) {
		return new EmailStartingBuilderImpl().copying(emailBuilder);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#copying(Email)}.
	 */
	@SuppressWarnings({"deprecation", "unused"})
	public static EmailPopulatingBuilder copying(@Nonnull final Email email) {
		return new EmailStartingBuilderImpl().copying(email);
	}
	
	/**
	 * Delegates to {@link EmailStartingBuilder#startingBlank()}.
	 */
	@SuppressWarnings("deprecation")
	public static EmailPopulatingBuilder startingBlank() {
		return new EmailStartingBuilderImpl().startingBlank();
	}
	
	private EmailBuilder() {
	}
	
}