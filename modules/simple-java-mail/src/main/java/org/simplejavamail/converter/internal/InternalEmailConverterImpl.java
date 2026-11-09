package org.simplejavamail.converter.internal;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.converter.internal.mimemessage.CidReferences;
import org.simplejavamail.internal.util.InternalEmailConverter;
import org.simplejavamail.internal.converter.ImportTraversal;

import java.util.Set;

/**
 * @see InternalEmailConverter
 */
public class InternalEmailConverterImpl implements InternalEmailConverter {

	public static final InternalEmailConverter INSTANCE = new InternalEmailConverterImpl();
    private final ImportTraversal traversal;

    private InternalEmailConverterImpl() { this(ImportTraversal.unlimited()); }
    private InternalEmailConverterImpl(final ImportTraversal traversal) { this.traversal = traversal; }
    static InternalEmailConverter forImport(final ImportTraversal traversal) { return new InternalEmailConverterImpl(traversal); }
    @Override public ImportTraversal importTraversal() { return traversal; }

	@Override
	public MimeMessage emailToMimeMessage(@NotNull final Email email) {
		return EmailConverter.emailToMimeMessage(email);
	}

	@Override
	public byte[] mimeMessageToEMLByteArray(@NotNull final MimeMessage mimeMessage) {
		return EmailConverter.mimeMessageToEMLByteArray(mimeMessage);
	}

	@Override
	public Set<String> referencedContentIds(@NotNull final String html) {
		return CidReferences.inHtml(html);
	}
}
