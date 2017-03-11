package org.simplejavamail.converter.internal.msgparser;

import org.simplejavamail.outlookmessageparser.model.OutlookMessage;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Helper class that parses {@link OutlookMessage} instances from the {@link org.simplejavamail.outlookmessageparser.OutlookMessageParser} library.
 */
public final class OutlookMessageParser {

	private OutlookMessageParser() {
		// util / helper class
	}

	@Nonnull
	public static OutlookMessage parseOutlookMsg(@Nonnull final File msgFile) {
		checkNonEmptyArgument(msgFile, "msgFile");
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgFile);
		} catch (final IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}

	@Nonnull
	public static OutlookMessage parseOutlookMsg(@Nonnull final InputStream msgInputStream) {
		checkNonEmptyArgument(msgInputStream, "msgInputStream");
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgInputStream);
		} catch (final IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}

	@Nonnull
	public static OutlookMessage parseOutlookMsg(@Nonnull final String msgData) {
		checkNonEmptyArgument(msgData, "msgData");
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgData);
		} catch (final IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}
}