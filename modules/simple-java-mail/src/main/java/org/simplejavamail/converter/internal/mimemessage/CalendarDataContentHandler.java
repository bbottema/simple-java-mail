package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.ActivationDataFlavor;
import jakarta.activation.DataContentHandler;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.activation.MailcapCommandMap;
import jakarta.mail.internet.ContentType;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;

/** Keeps Simple Java Mail's iCalendar handler local to the {@link DataHandler} that needs it. */
final class CalendarDataContentHandler {

	private CalendarDataContentHandler() {
	}

	static void configure(final DataHandler dataHandler) {
		final MailcapCommandMap calendarCommands = new MailcapCommandMap();
		calendarCommands.addMailcap("text/calendar;; x-java-content-handler=" + text_calendar.class.getName());
		dataHandler.setCommandMap(calendarCommands);
	}

	/** Public because Jakarta Activation instantiates content handlers reflectively. */
	public static final class text_calendar implements DataContentHandler {
		private static final ActivationDataFlavor FLAVOR =
				new ActivationDataFlavor(String.class, "text/calendar", "iCalendar text");

		@Override
		public ActivationDataFlavor[] getTransferDataFlavors() {
			return new ActivationDataFlavor[] {FLAVOR};
		}

		@Override
		public Object getTransferData(final ActivationDataFlavor requestedFlavor, final DataSource dataSource)
				throws IOException {
			return FLAVOR.equals(requestedFlavor) ? getContent(dataSource) : null;
		}

		@Override
		public Object getContent(final DataSource dataSource) throws IOException {
			final String charset = charset(dataSource.getContentType());
			try (InputStreamReader reader = new InputStreamReader(dataSource.getInputStream(), charset)) {
				final StringBuilder content = new StringBuilder();
				final char[] buffer = new char[4096];
				int read;
				while ((read = reader.read(buffer)) != -1) {
					content.append(buffer, 0, read);
				}
				return content.toString();
			} catch (IllegalArgumentException e) {
				throw new UnsupportedEncodingException(charset);
			}
		}

		@Override
		public void writeTo(final Object value, final String mimeType, final OutputStream outputStream)
				throws IOException {
			if (!(value instanceof String)) {
				throw new IOException("text/calendar DataContentHandler requires a String value");
			}
			final String charset = charset(mimeType);
			try {
				final OutputStreamWriter writer = new OutputStreamWriter(outputStream, charset);
				writer.write((String) value);
				writer.flush();
			} catch (IllegalArgumentException e) {
				throw new UnsupportedEncodingException(charset);
			}
		}
	}

	private static String charset(final String mimeType) {
		try {
			final String configured = new ContentType(mimeType).getParameter("charset");
			return configured != null ? configured : "us-ascii";
		} catch (Exception ignored) {
			return "us-ascii";
		}
	}
}
