package org.simplejavamail.converter.internal;

import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.EmailSourceData;
import org.simplejavamail.api.email.EmailSourceIdentity;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.internal.email.EmailInspection;
import org.simplejavamail.internal.util.InterruptedIoGuard;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

/** A known input acquisition/framing failure with the evidence acquired by that operation, never a live input owner. */
@SuppressWarnings("serial")
final class SourceReadException extends EmailConverterException {

	private final EmailSourceData sourceData;
	private final EmailProblem problem;

	SourceReadException(final EmailSourceData.Format format, final byte[] captured, final boolean fullyCaptured,
			final EmailProblem.Code code, final Exception cause) {
		super("Unable to interpret the received " + format + " input; available source data was retained", cause);
		InterruptedIoGuard.rethrowIfInterrupted(cause);
		final EmailSourceIdentity origin = EmailInspection.origin(null);
		final EmailSourceContent content = captured == null ? null
				: EmailInspection.content(captured, EmailSourceContent.Representation.SOURCE_BYTES, fullyCaptured);
		final EmailSourcePart part = EmailInspection.part(origin, "source/parts[0]", emptyList(), emptyList(), emptyList(), null, content);
		sourceData = EmailInspection.source(origin, format, EmailSourceData.Representation.RECEIVED,
				emptyList(), emptyList(), singletonList(part), emptyList());
		problem = EmailInspection.problem(origin, EmailProblem.Operation.IMPORT, code, part.getLocation(), null,
				cause.getMessage() != null ? cause.getMessage() : "Received input could not be interpreted", singletonList(part));
	}

	EmailSourceData getSourceData() {
		return sourceData;
	}

	EmailProblem getProblem() {
		return problem;
	}

	/** Reads an input once without taking its closing ownership; prefixes survive an IOException. */
	static byte[] acquire(final InputStream input, final EmailSourceData.Format format) {
		final ByteArrayOutputStream captured = new ByteArrayOutputStream();
		final byte[] buffer = new byte[8192];
		try {
			for (int count; (count = input.read(buffer)) != -1;) {
				if (count == 0) {
					final int next = input.read();
					if (next == -1) break;
					captured.write(next);
				} else {
					captured.write(buffer, 0, count);
				}
			}
			return captured.toByteArray();
		} catch (IOException readFailure) {
			InterruptedIoGuard.rethrowIfInterrupted(readFailure);
			throw new SourceReadException(format, captured.toByteArray(), false, EmailProblem.Code.CONTENT_READ_FAILED, readFailure);
		}
	}
}
