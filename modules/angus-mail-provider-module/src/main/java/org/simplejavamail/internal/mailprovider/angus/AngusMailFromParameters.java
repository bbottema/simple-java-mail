package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.MessagingException;
import lombok.Value;
import org.jetbrains.annotations.Nullable;

/**
 * Per-submission MAIL parameters; Angus still constructs the command and owns its encoding and response handling.
 * Angus 2.0.5 adds SMTPUTF8 from its transport-wide UTF-8 flag and the server's capabilities; its mail-extension setter cannot suppress that declaration.
 * Adjust the completed command here so encoding stays enabled while each submission controls its declaration.
 */
@Value
final class AngusMailFromParameters {

    private static final String MAIL_FROM_PREFIX = "MAIL FROM:<";
    private static final String SMTP_UTF8 = "SMTPUTF8";
    boolean smtpUtf8;
    @Nullable String mailExtension;

    /** Remove Angus's automatic declaration only when this submission does not need it; retain one deliberate/required declaration. */
    String applyToCommand(final String command) throws MessagingException {
        if (!command.startsWith(MAIL_FROM_PREFIX)) {
            return command;
        }
        final int parameterStart = findParameterStart(command);
        final StringBuilder adjusted = new StringBuilder(command.substring(0, parameterStart));
        boolean utf8Declared = false;
        for (final String parameter : command.substring(parameterStart).trim().split("\\s+")) {
            if (SMTP_UTF8.equalsIgnoreCase(parameter)) {
                if (smtpUtf8 && !utf8Declared) {
                    adjusted.append(' ').append(parameter);
                    utf8Declared = true;
                }
            } else if (!parameter.isEmpty()) {
                adjusted.append(' ').append(parameter);
            }
        }
        if (smtpUtf8 && !utf8Declared) {
            adjusted.append(' ').append(SMTP_UTF8);
        }
        return adjusted.toString();
    }

    private static int findParameterStart(final String command) throws MessagingException {
        boolean quoted = false;
        boolean escaped = false;
        // HeaderTokenizer applies MIME/header comment rules; this scan only locates the SMTP path boundary without interpreting the address.
        // A quoted local part may itself contain '>' or SMTPUTF8. Only inspect parameters after the real closing bracket.
        for (int index = MAIL_FROM_PREFIX.length(); index < command.length(); index++) {
            final char character = command.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quoted && character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (!quoted && character == '>') {
                return index + 1;
            }
        }
        throw new MessagingException("The SMTP sender address has unmatched quotes or brackets. Check the email's sender and bounce-to address, "
                + "or the envelope sender supplied by your custom SMTP integration. No message was submitted.");
    }
}
