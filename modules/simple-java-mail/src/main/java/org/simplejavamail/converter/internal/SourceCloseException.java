package org.simplejavamail.converter.internal;

import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.internal.util.InterruptedIoGuard;

import java.io.IOException;

/** Internal handoff of an already parsed value when only the owned input's cleanup failed. */
@SuppressWarnings("serial")
final class SourceCloseException extends EmailConverterException {

	private final transient MimeMessage mimeMessage;

	SourceCloseException(final MimeMessage mimeMessage, final IOException cause) {
		super("The received input was parsed but its stream could not be closed", cause);
		InterruptedIoGuard.rethrowIfInterrupted(cause);
		this.mimeMessage = mimeMessage;
	}

	MimeMessage getMimeMessage() {
		return mimeMessage;
	}
}
