package org.simplejavamail.mailer.internal;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

final class SessionDebugOutputResolver {

	private static final Logger JAVAMAIL_DEBUG_LOGGER = LoggerFactory.getLogger("org.simplejavamail.javamail.debug");

	private SessionDebugOutputResolver() {
	}

	@NotNull
	static PrintStream resolve(@NotNull final SessionDebugOutput debugOutput) {
		switch (debugOutput) {
			case STDOUT:
				return System.out;
			case STDERR:
				return System.err;
			case SLF4J:
				return new PrintStream(new Slf4jDebugOutputStream(), true);
			default:
				throw new IllegalArgumentException("Unknown session debug output: " + debugOutput);
		}
	}

	private static class Slf4jDebugOutputStream extends OutputStream {

		private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

		@Override
		public synchronized void write(final int b) {
			if (b == '\n') {
				flushLine();
			} else if (b != '\r') {
				lineBuffer.write(b);
			}
		}

		@Override
		public synchronized void flush() {
			flushLine();
		}

		private void flushLine() {
			if (lineBuffer.size() > 0) {
				JAVAMAIL_DEBUG_LOGGER.debug(new String(lineBuffer.toByteArray(), Charset.defaultCharset()));
				lineBuffer.reset();
			}
		}
	}
}
