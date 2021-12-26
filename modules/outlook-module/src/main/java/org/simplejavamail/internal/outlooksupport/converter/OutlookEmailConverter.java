package org.simplejavamail.internal.outlooksupport.converter;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.general.EmailPopulatingBuilderFactory;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.internal.modules.OutlookModule;
import org.simplejavamail.internal.outlooksupport.internal.model.OutlookMessageProxy;
import org.simplejavamail.internal.util.InternalEmailConverter;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.outlookmessageparser.model.OutlookAttachment;
import org.simplejavamail.outlookmessageparser.model.OutlookFileAttachment;
import org.simplejavamail.outlookmessageparser.model.OutlookMessage;
import org.simplejavamail.outlookmessageparser.model.OutlookMsgAttachment;
import org.simplejavamail.outlookmessageparser.model.OutlookRecipient;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.simplejavamail.internal.util.MiscUtil.extractCID;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnullOrEmpty;
import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;
import static org.slf4j.LoggerFactory.getLogger;

@SuppressWarnings("unused")
public class OutlookEmailConverter implements OutlookModule {

	private static final Logger LOGGER = getLogger(OutlookEmailConverter.class);

	@Override
	public EmailFromOutlookMessage outlookMsgToEmailBuilder(
			@NotNull final File msgFile,
			@NotNull final EmailStartingBuilder emailStartingBuilder,
			@NotNull final EmailPopulatingBuilderFactory builderFactory,
			@NotNull final InternalEmailConverter internalEmailConverter) {
		return buildEmailFromOutlookMessage(
				emailStartingBuilder.ignoringDefaults().startingBlank(),
				parseOutlookMsg(checkNonEmptyArgument(msgFile, "msgFile")),
				builderFactory,
				internalEmailConverter);
	}

	@Override
	public EmailFromOutlookMessage outlookMsgToEmailBuilder(
			@NotNull final String msgFile,
			@NotNull final EmailStartingBuilder emailStartingBuilder,
			@NotNull final EmailPopulatingBuilderFactory builderFactory,
			@NotNull final InternalEmailConverter internalEmailConverter) {
		return buildEmailFromOutlookMessage(
				emailStartingBuilder.ignoringDefaults().startingBlank(),
				parseOutlookMsg(checkNonEmptyArgument(msgFile, "msgFile")),
				builderFactory,
				internalEmailConverter);
	}
	
	@Override
	public EmailFromOutlookMessage outlookMsgToEmailBuilder(
			@NotNull final InputStream msgInputStream,
			@NotNull final EmailStartingBuilder emailStartingBuilder,
			@NotNull final EmailPopulatingBuilderFactory builderFactory,
			@NotNull final InternalEmailConverter internalEmailConverter) {
		return buildEmailFromOutlookMessage(
				emailStartingBuilder.ignoringDefaults().startingBlank(),
				parseOutlookMsg(checkNonEmptyArgument(msgInputStream, "msgInputStream")),
				builderFactory,
				internalEmailConverter);
	}
	
	private static EmailFromOutlookMessage buildEmailFromOutlookMessage(
			@NotNull final EmailPopulatingBuilder builder,
			@NotNull final OutlookMessage outlookMessage,
			@NotNull final EmailPopulatingBuilderFactory builderFactory,
			@NotNull final InternalEmailConverter internalEmailConverter) {
		checkNonEmptyArgument(builder, "emailBuilder");
		checkNonEmptyArgument(outlookMessage, "outlookMessage");
		String fromEmail = ofNullable(outlookMessage.getFromEmail()).orElse("donotreply@unknown-from-address.net");
		builder.from(outlookMessage.getFromName(), fromEmail);
		builder.fixingMessageId(outlookMessage.getMessageId());
		builder.fixingSentDate(ofNullable(outlookMessage.getClientSubmitTime()).orElse(outlookMessage.getDate())); // FIXME creation date?
		if (!MiscUtil.valueNullOrEmpty(outlookMessage.getReplyToEmail())) {
			builder.withReplyTo(outlookMessage.getReplyToName(), outlookMessage.getReplyToEmail());
		}
		copyReceiversFromOutlookMessage(builder, outlookMessage);
		builder.withSubject(outlookMessage.getSubject());
		builder.withPlainText(outlookMessage.getBodyText());
		builder.withHTMLText(outlookMessage.getBodyHTML() != null ? outlookMessage.getBodyHTML() : outlookMessage.getConvertedBodyHTML());
		
		for (final Map.Entry<String, OutlookFileAttachment> cid : outlookMessage.fetchCIDMap().entrySet()) {
			final String cidName = checkNonEmptyArgument(cid.getKey(), "cid.key");
			builder.withEmbeddedImage(verifyNonnullOrEmpty(extractCID(cidName)), cid.getValue().getData(), cid.getValue().getMimeTag());
		}
		for (final OutlookFileAttachment attachment : outlookMessage.fetchTrueAttachments()) {
			String attachmentName = ofNullable(attachment.getLongFilename()).orMaybe(attachment.getFilename());
			builder.withAttachment(attachmentName, attachment.getData(), attachment.getMimeTag());
		}
		for (int i = 0; i < outlookMessage.getOutlookAttachments().size(); i++) {
			final OutlookAttachment attachment = outlookMessage.getOutlookAttachments().get(i);
			if (attachment instanceof OutlookMsgAttachment) {
				final OutlookMessage nestedMsg = ((OutlookMsgAttachment) attachment).getOutlookMessage();
				final Email email = buildEmailFromOutlookMessage(builderFactory.create(), nestedMsg, builderFactory, internalEmailConverter)
						.getEmailBuilder().buildEmail();
				final MimeMessage message = internalEmailConverter.emailToMimeMessage(email);
				final byte[] mimedata = internalEmailConverter.mimeMessageToEMLByteArray(message);
				builder.withAttachment(nestedMsg.getSubject() + ".eml", new ByteArrayDataSource(mimedata, "message/rfc822"));
			}
		}

		return new EmailFromOutlookMessage(builder, new OutlookMessageProxy(outlookMessage));
	}

	private static void copyReceiversFromOutlookMessage(@NotNull EmailPopulatingBuilder builder, @NotNull OutlookMessage outlookMessage) {
		//noinspection QuestionableName
		for (final OutlookRecipient to : outlookMessage.getToRecipients()) {
			builder.to(to.getName(), to.getAddress());
		}
		for (final OutlookRecipient cc : outlookMessage.getCcRecipients()) {
			builder.cc(cc.getName(), cc.getAddress());
		}
		for (final OutlookRecipient bcc : outlookMessage.getBccRecipients()) {
			builder.bcc(bcc.getName(), bcc.getAddress());
		}
	}
	
	@NotNull
	private static OutlookMessage parseOutlookMsg(@NotNull final File msgFile) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgFile);
		} catch (final IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}
	
	@NotNull
	private static OutlookMessage parseOutlookMsg(@NotNull final InputStream msgInputStream) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgInputStream);
		} catch (final IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}
	
	@NotNull
	private static OutlookMessage parseOutlookMsg(@NotNull final String msgFile) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgFile);
		} catch (final IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}
}
