package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.experimental.UtilityClass;
import org.eclipse.angus.mail.util.CRLFOutputStream;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

/** Measures prepared SMTP content without retaining a second copy or counting DATA framing and dot transparency. */
@UtilityClass
final class AngusMessageSize {

    // These are the headers SMTPTransport omits. PRESERVE_ALL_BYTES facades deliberately ignore this list.
    private static final String[] OMITTED_HEADERS = {"Bcc", "Content-Length"};

    static void validateDeclaration(@Nullable final String extension, final String source, final Address[] recipients)
            throws MailTransportCompatibilityException {
        final String[] declarations = sizeDeclarations(extension);
        if (declarations.length > 1 || (declarations.length == 1 && !declarations[0].matches("(?i)SIZE=[0-9]{1,20}"))) {
            throw new MailTransportCompatibilityException("The advanced SMTP options in " + source
                    + " contain a repeated or invalid SIZE declaration. Remove the SIZE option; Simple Java Mail will calculate it for each email. "
                    + "If your integration supplies its own estimate, use one SIZE=<number of bytes> option. No message was submitted.", recipients);
        }
    }

    static String withDeclaration(@Nullable final String extension, final long messageSize) {
        if (sizeDeclarations(extension).length > 0) {
            return extension;
        }
        return (extension == null || extension.isEmpty() ? "" : extension + " ") + "SIZE=" + messageSize;
    }

    private static String[] sizeDeclarations(@Nullable final String extension) {
        return extension == null ? new String[0] : Arrays.stream(extension.split("\\s+"))
                .filter(parameter -> parameter.equalsIgnoreCase("SIZE") || parameter.regionMatches(true, 0, "SIZE=", 0, 5))
                .toArray(String[]::new);
    }

    static long measure(final MimeMessage message, final BooleanSupplier stopped) throws MessagingException {
        final CountingStream counter = new CountingStream(stopped);
        final CanonicalContentStream canonicalContent = new CanonicalContentStream(counter);
        try {
            message.writeTo(canonicalContent, OMITTED_HEADERS);
            canonicalContent.finish();
            counter.checkStopped();
            return counter.count;
        } catch (IOException failure) {
            throw new MessagingException("Could not finish reading this email to check its size. Check that its attachments and other content "
                    + "can still be read, unless the send was cancelled or timed out. No message was submitted.", failure);
        }
    }

    /** Reuse Angus's own newline normalization, including finishData's final CRLF, but omit SMTPOutputStream's dot escaping. */
    private static final class CanonicalContentStream extends CRLFOutputStream {
        private CanonicalContentStream(final OutputStream counter) {
            super(counter);
        }

        private void finish() throws IOException {
            if (!atBOL) {
                writeln();
            }
        }
    }

    private static final class CountingStream extends OutputStream {
        private final BooleanSupplier stopped;
        private long count;

        private CountingStream(final BooleanSupplier stopped) {
            this.stopped = stopped;
        }

        @Override
        public void write(final int value) throws IOException {
            add(1);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) throws IOException {
            add(length);
        }

        private void add(final int length) throws IOException {
            checkStopped();
            try {
                count = Math.addExact(count, length);
            } catch (ArithmeticException overflow) {
                throw new IOException("The email is too large to count reliably.", overflow);
            }
        }

        private void checkStopped() throws IOException {
            if (stopped.getAsBoolean()) {
                throw new IOException("The mail send was stopped while reading its content.");
            }
        }
    }
}
