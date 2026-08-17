package org.simplejavamail.converter.internal.mimemessage;

import jakarta.activation.ActivationDataFlavor;
import jakarta.activation.CommandMap;
import jakarta.activation.DataContentHandler;
import jakarta.activation.DataSource;
import jakarta.activation.MailcapCommandMap;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.Properties;

/**
 * The small set of Jakarta Activation content handlers needed for provider-neutral MIME conversion.
 *
 * <p>The public handler types are instantiated reflectively by {@code MailcapCommandMap}. Their
 * lower-case names follow the long-established Jakarta Mail handler convention.</p>
 */
public final class ProviderNeutralDataContentHandlers {
	private static boolean installed;

	private ProviderNeutralDataContentHandlers() {
	}

	static synchronized void install() {
		if (installed) return;
		final MailcapCommandMap mailcap = (MailcapCommandMap) CommandMap.getDefaultCommandMap();
		mailcap.addMailcap("text/plain;; x-java-content-handler=" + text_plain.class.getName());
		mailcap.addMailcap("text/html;; x-java-content-handler=" + text_html.class.getName());
		mailcap.addMailcap("text/xml;; x-java-content-handler=" + text_xml.class.getName());
		mailcap.addMailcap("application/xml;; x-java-content-handler=" + text_xml.class.getName());
		mailcap.addMailcap("text/calendar;; x-java-content-handler=" + text_calendar.class.getName());
		mailcap.addMailcap("multipart/*;; x-java-content-handler=" + multipart_mixed.class.getName());
		mailcap.addMailcap("message/rfc822;; x-java-content-handler=" + message_rfc822.class.getName());
		CommandMap.setDefaultCommandMap(mailcap);
		installed = true;
	}

	public static final class text_plain extends TextHandler {
		public text_plain() {
			super("text/plain", "Plain text");
		}
	}

	public static final class text_html extends TextHandler {
		public text_html() {
			super("text/html", "HTML text");
		}
	}

	public static final class text_xml extends TextHandler {
		public text_xml() {
			super("text/xml", "XML text");
		}
	}

	public static final class text_calendar extends TextHandler {
		public text_calendar() {
			super("text/calendar", "iCalendar text");
		}
	}

	public static final class multipart_mixed implements DataContentHandler {
		private static final ActivationDataFlavor FLAVOR =
				new ActivationDataFlavor(MimeMultipart.class, "multipart/mixed", "MIME multipart");

		@Override
		public ActivationDataFlavor[] getTransferDataFlavors() {
			return new ActivationDataFlavor[] { FLAVOR };
		}

		@Override
		public Object getTransferData(final ActivationDataFlavor flavor, final DataSource dataSource) throws IOException {
			return FLAVOR.equals(flavor) ? getContent(dataSource) : null;
		}

		@Override
		public Object getContent(final DataSource dataSource) throws IOException {
			try {
				return new MimeMultipart(dataSource);
			} catch (MessagingException e) {
				throw new IOException("Unable to parse MIME multipart content", e);
			}
		}

		@Override
		public void writeTo(final Object value, final String mimeType, final OutputStream outputStream) throws IOException {
			if (!(value instanceof Multipart)) {
				throw new IOException("multipart/* DataContentHandler requires a Multipart value");
			}
			try {
				((Multipart) value).writeTo(outputStream);
			} catch (MessagingException e) {
				throw new IOException("Unable to serialize MIME multipart content", e);
			}
		}
	}

	public static final class message_rfc822 implements DataContentHandler {
		private static final ActivationDataFlavor FLAVOR =
				new ActivationDataFlavor(Message.class, "message/rfc822", "RFC 822 message");

		@Override
		public ActivationDataFlavor[] getTransferDataFlavors() {
			return new ActivationDataFlavor[] { FLAVOR };
		}

		@Override
		public Object getTransferData(final ActivationDataFlavor flavor, final DataSource dataSource) throws IOException {
			return FLAVOR.equals(flavor) ? getContent(dataSource) : null;
		}

		@Override
		public Object getContent(final DataSource dataSource) throws IOException {
			try {
				return new MimeMessage(Session.getInstance(new Properties()), dataSource.getInputStream());
			} catch (MessagingException e) {
				throw new IOException("Unable to parse nested RFC 822 message", e);
			}
		}

		@Override
		public void writeTo(final Object value, final String mimeType, final OutputStream outputStream) throws IOException {
			if (!(value instanceof Message)) {
				throw new IOException("message/rfc822 DataContentHandler requires a Message value");
			}
			try {
				((Message) value).writeTo(outputStream);
			} catch (MessagingException e) {
				throw new IOException("Unable to serialize nested RFC 822 message", e);
			}
		}
	}

	private abstract static class TextHandler implements DataContentHandler {
		private final ActivationDataFlavor flavor;

		private TextHandler(final String mimeType, final String description) {
			this.flavor = new ActivationDataFlavor(String.class, mimeType, description);
		}

		@Override
		public ActivationDataFlavor[] getTransferDataFlavors() {
			return new ActivationDataFlavor[] { flavor };
		}

		@Override
		public Object getTransferData(final ActivationDataFlavor requestedFlavor, final DataSource dataSource) throws IOException {
			return flavor.equals(requestedFlavor) ? getContent(dataSource) : null;
		}

		@Override
		public Object getContent(final DataSource dataSource) throws IOException {
			final String charset = charset(dataSource.getContentType());
			try (InputStreamReader reader = new InputStreamReader(dataSource.getInputStream(), charset)) {
				final StringBuilder content = new StringBuilder();
				final char[] buffer = new char[4096];
				int read;
				while ((read = reader.read(buffer)) != -1) content.append(buffer, 0, read);
				return content.toString();
			} catch (IllegalArgumentException e) {
				throw new UnsupportedEncodingException(charset);
			}
		}

		@Override
		public void writeTo(final Object value, final String mimeType, final OutputStream outputStream) throws IOException {
			if (!(value instanceof String)) {
				throw new IOException("text/* DataContentHandler requires a String value");
			}
			final String charset = charset(mimeType);
			try {
				final OutputStreamWriter writer = new OutputStreamWriter(outputStream, charset);
				writer.write((String) value);
				writer.close();
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
