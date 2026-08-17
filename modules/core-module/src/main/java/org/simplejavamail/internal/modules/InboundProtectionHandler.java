package org.simplejavamail.internal.modules;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.internal.protectionsupport.model.InboundProtectionResult;

/** Contract for optional protection modules that inspect original MIME before generic multipart parsing. */
public interface InboundProtectionHandler<C, D> {
    @NotNull InboundProtectionResult<D> processIncoming(@NotNull Session session,
                                                        @NotNull MimeMessage originalMessage,
                                                        @Nullable C receiveConfig);
}
