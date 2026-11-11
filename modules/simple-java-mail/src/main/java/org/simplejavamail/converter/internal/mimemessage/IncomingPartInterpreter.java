package org.simplejavamail.converter.internal.mimemessage;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimePart;
import lombok.Builder;
import lombok.Getter;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.internal.converter.ImportTraversal;

/** Import-only layer opening. The parser retains grammar/occurrence ownership and never stores this callback on an Email. */
public interface IncomingPartInterpreter {
    PartSelection open(MimePart part, ProtectionScope covering, EmailSourcePart source, ImportTraversal traversal, boolean messageBody);

    @Getter
    @Builder
    final class PartSelection {
        private final MimePart content;
        private final ProtectionScope protection;
        private final MimeMessage authoredHeaders;
        private final ProtectionScope authoredProtection;
        private final boolean protectedContainer;
        private final boolean authoredHeaderViewUnavailable;
        private final Exception inputFailure;
        private final ImportTraversal contentTraversal;
    }
}
