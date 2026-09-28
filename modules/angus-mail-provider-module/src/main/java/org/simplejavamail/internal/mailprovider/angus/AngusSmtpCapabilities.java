package org.simplejavamail.internal.mailprovider.angus;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.SmtpCapabilities;
import org.simplejavamail.internal.util.SmtpCapabilityDiagnostics;
import org.simplejavamail.internal.util.SmtpSizeSupport;

import java.nio.CharBuffer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads duplicate advertisements that Angus's last-value-wins extension map cannot preserve. */
@UtilityClass
final class AngusSmtpCapabilities {

    // ExtensionReader sets the SMTP line boundaries. Preserve other control characters for validation/escaping, not regex line splitting.
    private static final Pattern EXTENSION = Pattern.compile("250[- ]([A-Za-z0-9][A-Za-z0-9-]*)(?:[ \\t]++(.*))?", Pattern.DOTALL);

    /**
     * Collects a bounded diagnostic snapshot of Angus's saved EHLO reply, for example:
     * <pre>
     * 250-mail.example.org Hello client.example.org
     * 250-SIZE 10485760
     * 250-STARTTLS
     * 250 AUTH PLAIN LOGIN
     * </pre>
     * The first line is the server greeting, not an extension. A dash after {@code 250} means another line follows; a space marks the last line.
     * This reporting path is deliberately separate from {@link #parseSizeSupport(String)}.
     */
    @Nullable
    static SmtpCapabilities parse(final String response, final Consumer<String> warning) {
        final ExtensionReader extensions = new ExtensionReader(response);
        final SmtpCapabilityDiagnostics.Collector diagnostics = new SmtpCapabilityDiagnostics.Collector();
        while (extensions.next()) {
            if (!diagnostics.add(extensions.name(), extensions.parameter())) {
                warning.accept(SmtpCapabilityDiagnostics.overflowWarning());
                break;
            }
        }
        extensions.reportMalformedLines(warning);
        return diagnostics.snapshot();
    }

    /** Inspects the complete reply, even when an unrelated extension or a later duplicate cannot fit in a probe report. */
    static SmtpSizeSupport parseSizeSupport(final String response) {
        final ExtensionReader extensions = new ExtensionReader(response);
        SmtpSizeSupport size = SmtpSizeSupport.unadvertised();
        while (extensions.next()) {
            if (extensions.isSize()) {
                size = size.withAdvertisement(extensions.parameter());
            }
        }
        return size;
    }

    /** Matches slices of the provider's existing reply; neither path splits or copies the complete response. */
    private static final class ExtensionReader {
        private final String response;
        private final Matcher extension;
        private int nextLine;
        private int malformedLines;

        private ExtensionReader(final String response) {
            this.response = response;
            extension = EXTENSION.matcher(response);
            final int greetingEnd = response.indexOf('\n');
            nextLine = greetingEnd < 0 ? response.length() : greetingEnd + 1;
        }

        private boolean next() {
            while (nextLine < response.length()) {
                final int lineStart = nextLine;
                final int newline = response.indexOf('\n', lineStart);
                int lineEnd = newline < 0 ? response.length() : newline;
                nextLine = newline < 0 ? response.length() : newline + 1;
                if (lineEnd > lineStart && response.charAt(lineEnd - 1) == '\r') {
                    lineEnd--;
                }
                if (extension.region(lineStart, lineEnd).matches()) {
                    return true;
                }
                malformedLines++;
            }
            return false;
        }

        private CharSequence name() {
            return CharBuffer.wrap(response, extension.start(1), extension.end(1));
        }

        private boolean isSize() {
            return extension.end(1) - extension.start(1) == 4 && response.regionMatches(true, extension.start(1), "SIZE", 0, 4);
        }

        private CharSequence parameter() {
            int start = extension.start(2);
            if (start < 0) {
                return "";
            }
            int end = extension.end(2);
            // Preserve the existing trim tolerance without allocating a copy of a potentially enormous parameter.
            while (start < end && response.charAt(start) <= ' ') {
                start++;
            }
            while (end > start && response.charAt(end - 1) <= ' ') {
                end--;
            }
            return CharBuffer.wrap(response, start, end);
        }

        private void reportMalformedLines(final Consumer<String> warning) {
            if (malformedLines > 0) {
                warning.accept(malformedLines == 1 ? "An invalid EHLO extension line was ignored."
                        : "Ignored " + malformedLines + " invalid EHLO extension lines.");
            }
        }
    }
}
