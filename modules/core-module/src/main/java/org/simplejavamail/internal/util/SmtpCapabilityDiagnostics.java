package org.simplejavamail.internal.util;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.SmtpCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounds retained EHLO diagnostics, never the provider's network reads or the facts used to send mail. */
public final class SmtpCapabilityDiagnostics {
    // Reporting budget, not a protocol limit: RFC 5321 section 4.5.3.1.5 allows 512 octets per reply line, with no EHLO line-count cap.
    // On 2026-09-28 IANA listed 35 keywords: even 35 full-length lines plus a greeting would be 18 KiB. 64 KiB leaves room for
    // private extensions and unusual replies, while both TLS snapshots retain at most 128 KiB of capability text (not total heap).
    // Sources: https://www.rfc-editor.org/rfc/rfc5321.html#section-4.5.3.1.5 and https://www.iana.org/assignments/smtp/
    private static final int MAX_CAPABILITY_DISPLAY_BYTES = 64 * 1024;

    private SmtpCapabilityDiagnostics() {
    }

    public static String overflowWarning() {
        return "The server supplied more capability detail than fits in the 64 KiB probe report budget; this snapshot is unavailable. "
                + "Connection setup and sending are not rejected by this reporting limit.";
    }

    /** Also checks snapshots supplied by other providers, without rendering another copy of their complete report. */
    public static boolean fits(final SmtpCapabilities capabilities) {
        final Budget budget = new Budget();
        for (final Map.Entry<String, List<String>> extension : capabilities.getExtensions().entrySet()) {
            boolean firstParameter = true;
            for (final String parameter : extension.getValue()) {
                if (!budget.include(extension.getKey(), parameter, firstParameter)) {
                    return false;
                }
                firstParameter = false;
            }
        }
        return true;
    }

    /** Owns only a bounded, all-or-nothing diagnostic copy. Stop collecting as soon as an entry does not fit. */
    public static final class Collector {
        private final Map<String, List<String>> extensions = new LinkedHashMap<>();
        private final Budget budget = new Budget();
        private boolean exceeded;

        public boolean add(final CharSequence name, final CharSequence parameter) {
            // EHLO names are ASCII. Check before copying an arbitrarily long provider-supplied name.
            if (exceeded || name.length() > MAX_CAPABILITY_DISPLAY_BYTES) {
                return discard();
            }
            final String canonicalName = name.toString().toUpperCase(Locale.ROOT);
            if (!budget.include(canonicalName, parameter, !extensions.containsKey(canonicalName))) {
                return discard();
            }
            extensions.computeIfAbsent(canonicalName, unused -> new ArrayList<>()).add(parameter.toString());
            return true;
        }

        private boolean discard() {
            exceeded = true;
            extensions.clear();
            return false;
        }

        @Nullable
        public SmtpCapabilities snapshot() {
            return exceeded ? null : new SmtpCapabilities(extensions);
        }
    }

    private static final class Budget {
        private int displayBytes = 2; // The map's opening and closing braces.
        private boolean hasExtensions;

        private boolean include(final String name, final CharSequence parameter, final boolean firstParameter) {
            // Map/List.toString(): a new NAME=[value] costs the name and "=[]"; subsequent entries/values have a ", " separator.
            final int framingBytes = firstParameter ? name.length() + 3 + (hasExtensions ? 2 : 0) : 2;
            if (framingBytes > MAX_CAPABILITY_DISPLAY_BYTES - displayBytes) {
                return false;
            }
            final int remainingBytes = MAX_CAPABILITY_DISPLAY_BYTES - displayBytes - framingBytes;
            final int parameterBytes = SmtpDiagnosticText.escapedUtf8Length(parameter, remainingBytes);
            if (parameterBytes > remainingBytes) {
                return false;
            }
            displayBytes += framingBytes + parameterBytes;
            hasExtensions = true;
            return true;
        }
    }
}
