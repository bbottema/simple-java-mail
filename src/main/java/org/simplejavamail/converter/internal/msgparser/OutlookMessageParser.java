package org.simplejavamail.converter.internal.msgparser;

import org.simplejavamail.outlookmessageparser.model.OutlookMessage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Helper class that parses {@link OutlookMessage} instances from the {@link org.simplejavamail.outlookmessageparser.OutlookMessageParser} library.
 */
public class OutlookMessageParser {

	public static OutlookMessage parseOutlookMsg(File msgFile) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgFile);
		} catch (IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}

	public static OutlookMessage parseOutlookMsg(InputStream msgInputStream) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgInputStream);
		} catch (IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}

	public static OutlookMessage parseOutlookMsg(String msgData) {
		try {
			return new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(msgData);
		} catch (IOException e) {
			throw new OutlookMessageException(OutlookMessageException.ERROR_PARSING_OUTLOOK_MSG, e);
		}
	}
}