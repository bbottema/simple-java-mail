package org.simplejavamail.internal.outlooksupport.converter;

import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.internal.modules.OutlookModule;
import org.simplejavamail.internal.outlooksupport.internal.model.OutlookMessageProxy;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.outlookmessageparser.model.OutlookFileAttachment;
import org.simplejavamail.outlookmessageparser.model.OutlookMessage;
import org.simplejavamail.outlookmessageparser.model.OutlookRecipient;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import static org.simplejavamail.internal.util.MiscUtil.extractCID;
import static org.simplejavamail.internal.util.Preconditions.assumeNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;

@SuppressWarnings("unused")
public class OutlookEmailConverter implements OutlookModule {

	@Override
	public EmailFromOutlookMessage outlookMsgToEmailBuilder(@Nonnull File msgFile, @Nonnull EmailStartingBuilder emailStartingBuilder) {
		return buildEmailFromOutlookMessage(
				emailStartingBuilder.ignoringDefaults().startingBlank(),
				parseOutlookMsg(checkNonEmptyArgument(msgFile, "msgFile")));
	}

	@Override
	public EmailFromOutlookMessage outlookMsgToEmailBuilder(@Nonnull String msgFile, @Nonnull EmailStartingBuilder emailStartingBuilder) {
		return buildEmailFromOutlookMessage(
				emailStartingBuilder.ignoringDefaults().startingBlank(),
				parseOutlookMsg(checkNonEmptyArgument(msgFile, "msgFile")));
	}
	
	@Override
	public EmailFromOutlookMessage outlookMsgToEmailBuilder(@Nonnull InputStream msgInputStream, @Nonnull EmailStartingBuilder emailStartingBuilder) {
		return buildEmailFromOutlookMessage(
				emailStartingBuilder.ignoringDefaults().startingBlank(),
				parseOutlookMsg(checkNonEmptyArgument(msgInputStream, "msgInputStream")));
	}
	
	private static EmailFromOutlookMessage buildEmailFromOutlookMessage(
			@Nonnull final EmailPopulatingBuilder builder,
			@Nonnull final OutlookMessage outlookMessage) {
		checkNonEmptyArgument(builder, "emailBuilder");
		checkNonEmptyArgument(outlookMessage, "outlookMessage");
		String fromEmail = ofNullable(outlookMessage.getFromEmail()).orElse("donotreply@unknown-from-address.net");
		builder.from(outlookMessage.getFromName(), fromEmail);
		if (!MiscUtil.valueNullOrEmpty(outlookMessage.getReplyToEmail())) {
			builder.withReplyTo(outlookMessage.getReplyToName(), outlookMessage.getReplyToEmail());
		}
		copyReceiversFromOutlookMessage(builder, outlookMessage);
		builder.withSubject(outlookMessage.getSubject());
		builder.withPlainText(outlookMessage.getBodyText());
		builder.withHTMLText(outlookMessage.getBodyHTML() != null ? outlookMessage.getBodyHTML() : outlookMessage.getConvertedBodyHTML());
		
		for (final Map.Entry<String, OutlookFileAttachment> cid : outlookMessage.fetchCIDMap().entrySet()) {
			final String cidName = checkNonEmptyArgument(cid.getKey(), "cid.key");
			builder.withEmbeddedImage(assumeNonNull(extractCID(cidName)), cid.getValue().getData(), cid.getValue().getMimeTag());
		}
		for (final OutlookFileAttachment attachment : outlookMessage.fetchTrueAttachments()) {
			String attachmentName = ofNullable(attachment.getLongFilename()).orMaybe(attachment.getFilename());
			builder.withAttachment(attachmentName, attachment.getData(), attachment.getMimeTag());
		}

		return new EmailFromOutlookMessage(builder, new OutlookMessageProxy(outlookMessage));
	}
	
	private static void copyReceiversFromOutlookMessage(@Nonnull EmailPopulatingBuilder builder, @Nonnull OutlookMessage outlookMessage) {
		for (final OutlookRecipient to : outlookMessage.getRecipients()) {
			builder.to(to.getName(), to.getAddress());
		}
		//noinspection QuestionableName
		for (final OutlookRecipient cc : outlookMessage.getCcRecipients()) {
			builder.cc(cc.getName(), cc.getAddress());
		}
		for (final OutlookRecipient bcc : outlookMessage.getBccRecipients()) {
			builder.bcc(bcc.getName(), bcc.getAddress());
		}
	}
	
	@Nonnull
	private static OutlookMessage parseOutlookMsg(@Nonnull final File msgFile) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgFile);
		} catch (final IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}
	
	@Nonnull
	private static OutlookMessage parseOutlookMsg(@Nonnull final InputStream msgInputStream) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgInputStream);
		} catch (final IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}
	
	@Nonnull
	private static OutlookMessage parseOutlookMsg(@Nonnull final String msgFile) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgFile);
		} catch (final IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}
}
