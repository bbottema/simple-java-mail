package org.simplejavamail.converter.internal;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.internal.util.InternalEmailConverter;

/**
 * @see InternalEmailConverter
 */
// FIXME lombok
public class InternalEmailConverterImpl implements InternalEmailConverter {

	public static final InternalEmailConverter INSTANCE = new InternalEmailConverterImpl();

	private InternalEmailConverterImpl() {}

	@Override
	public MimeMessage emailToMimeMessage(@NotNull final Email email) {
		return EmailConverter.emailToMimeMessage(email);
	}

	@Override
	public byte[] mimeMessageToEMLByteArray(@NotNull final MimeMessage mimeMessage) {
		return EmailConverter.mimeMessageToEMLByteArray(mimeMessage);
	}
}
