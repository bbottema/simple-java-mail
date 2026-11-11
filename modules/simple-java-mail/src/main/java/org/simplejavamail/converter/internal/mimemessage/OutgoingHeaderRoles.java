package org.simplejavamail.converter.internal.mimemessage;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.internal.email.HeaderRoles;

/** Semantic header ownership for newly reconstructed MIME, independently of how the Email was created. */
@ApiStatus.Internal
public final class OutgoingHeaderRoles {
    private OutgoingHeaderRoles() { }

    static boolean shouldEmit(final Email email, final String name) {
        final HeaderRoles.Role role = HeaderRoles.classify(name);
        final InternalEmail represented = InternalEmail.requireInternalEmail(email);
        final boolean explicit = represented.getExplicitHeaderNames().contains(name);
        if (explicit && role == HeaderRoles.Role.PARSER_PLACEHOLDER) {
            throw new IllegalArgumentException("Header ':' is a parser placeholder, not a valid output header name.");
        }
        if (explicit && (role == HeaderRoles.Role.MIME_STRUCTURE || role == HeaderRoles.Role.DELIVERY_HISTORY)) {
            final String guidance = "Return-Path".equalsIgnoreCase(name) ? "Use withBounceTo(...) for the SMTP envelope sender."
                    : role == HeaderRoles.Role.MIME_STRUCTURE ? "Use the body, resource and content-encoding API so the producer can build the MIME structure."
                    : "Use exact EML or forwardingOriginal(...) when preserving received delivery/authentication history.";
            throw new IllegalArgumentException("Header '" + name + "' is owned by "
                    + (role == HeaderRoles.Role.MIME_STRUCTURE ? "the MIME producer" : "delivery or authentication processing")
                    + " and cannot be supplied as a current claim on reconstructed output. " + guidance);
        }
        return isEmitted(represented, name, role, explicit);
    }

    /** Typed authorities have their own validation paths. Explicit generic declarations still receive normal scanning. */
    public static boolean shouldScanGenericHeader(final Email email, final String name) {
        if (!(email instanceof InternalEmail)) return true;
        final InternalEmail represented = (InternalEmail) email;
        final HeaderRoles.Role role = HeaderRoles.classify(name);
        if (role == HeaderRoles.Role.TYPED) return false;
        final boolean explicit = represented.getExplicitHeaderNames().contains(name);
        return explicit || isEmitted(represented, name, role, explicit);
    }

    private static boolean isEmitted(final InternalEmail email, final String name, final HeaderRoles.Role role, final boolean explicit) {
        if (role == HeaderRoles.Role.ADDITIONAL) {
            return explicit || !HeaderRoles.isThreading(name) || !email.isAuthoredHeaderViewUnavailable();
        }
        return role == HeaderRoles.Role.EXPLICIT_AUTHORED && explicit;
    }
}
