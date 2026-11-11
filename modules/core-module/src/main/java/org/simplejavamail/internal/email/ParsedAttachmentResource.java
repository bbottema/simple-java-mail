package org.simplejavamail.internal.email;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;

import java.util.Objects;

/** Library-owned association between one selected resource occurrence and its already parsed independent message. */
@ApiStatus.Internal
public final class ParsedAttachmentResource extends AttachmentResource {
    private static final long serialVersionUID = 1L;
    private final Email email;

    private ParsedAttachmentResource(final AttachmentResource selected, final Email email) {
        super(selected);
        this.email = Objects.requireNonNull(email, "email");
    }

    /** Shares selected payload ownership; does not render, copy or read the child. */
    @NotNull
    public static AttachmentResource withEmail(@NotNull final AttachmentResource selected, @NotNull final Email email) {
        return new ParsedAttachmentResource(Objects.requireNonNull(selected, "selected"), email);
    }

    @Override
    @NotNull
    public Email getEmail() { return email; }
}
