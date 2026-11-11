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
    private final ConfiguredEmailConverterImpl converter;
    private final org.simplejavamail.api.mailer.config.Pkcs12Config smimeKeys;
    private final org.simplejavamail.converter.internal.mimemessage.ProtectionScope enclosing;
    private final org.simplejavamail.api.email.config.OpenPgpReceiveConfig pgpKeys;

    private InternalEmailConverterImpl() { this(ImportTraversal.unlimited(), null, null, org.simplejavamail.converter.internal.mimemessage.ProtectionScope.plain(), null); }
    private InternalEmailConverterImpl(final ImportTraversal traversal, final ConfiguredEmailConverterImpl converter,
            final org.simplejavamail.api.mailer.config.Pkcs12Config smimeKeys,
            final org.simplejavamail.converter.internal.mimemessage.ProtectionScope enclosing,
            final org.simplejavamail.api.email.config.OpenPgpReceiveConfig pgpKeys) {
        this.traversal = traversal;
        this.converter = converter;
        this.smimeKeys = smimeKeys;
        this.enclosing = enclosing;
        this.pgpKeys = pgpKeys;
    }
    static InternalEmailConverter forImport(final ImportTraversal traversal, final ConfiguredEmailConverterImpl converter,
            final org.simplejavamail.api.mailer.config.Pkcs12Config smimeKeys,
            final org.simplejavamail.converter.internal.mimemessage.ProtectionScope enclosing,
            final org.simplejavamail.api.email.config.OpenPgpReceiveConfig pgpKeys) {
        return new InternalEmailConverterImpl(traversal, converter, smimeKeys, enclosing, pgpKeys);
    }
    @Override public ImportTraversal importTraversal() { return traversal; }

    @Override public Email completeOutlookChild(final org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage child,
            final ImportTraversal childScope) {
        final ConfiguredEmailConverterImpl selected = converter != null ? converter
                : new ConfiguredEmailConverterImpl(org.simplejavamail.config.ConfigLoader.builder().load());
        return selected.toOutlookEmailConversionResult(child, smimeKeys,
                selected.prepareImport(org.simplejavamail.api.converter.config.ImportLimitsConfig.builder().build(), true).at(childScope), enclosing, pgpKeys).buildEmail();
    }

	@Override
	public MimeMessage emailToMimeMessage(@NotNull final Email email) {
		return converter == null ? EmailConverter.emailToMimeMessage(email)
                : converter.forArtifactExport().fromEmail(email).toMimeMessage();
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
