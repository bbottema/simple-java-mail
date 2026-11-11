package org.simplejavamail.email.internal;

import lombok.EqualsAndHashCode;
import java.io.Serializable;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Draft-local helper reuse obligations, independent of received evidence and outgoing credentials. */
@EqualsAndHashCode
public final class ConfidentialContentState implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final ConfidentialContentState NONE = new ConfidentialContentState(EnumSet.noneOf(Component.class), false);

    public enum Component { PLAIN_TEXT, HTML_TEXT, EMBEDDED_IMAGES, ATTACHMENTS, FORWARDED_MESSAGE }

    private final Set<Component> components;
    private final boolean unencryptedContentAllowed;

    private ConfidentialContentState(final Set<Component> components, final boolean unencryptedContentAllowed) {
        this.components = Collections.unmodifiableSet(components.isEmpty() ? EnumSet.noneOf(Component.class) : EnumSet.copyOf(components));
        this.unencryptedContentAllowed = unencryptedContentAllowed;
    }

    public static ConfidentialContentState none() { return NONE; }

    public static ConfidentialContentState requiring(final Set<Component> components, final boolean unencryptedContentAllowed) {
        return new ConfidentialContentState(components, unencryptedContentAllowed);
    }

    public ConfidentialContentState without(final Component component) {
        final EnumSet<Component> remaining = components.isEmpty() ? EnumSet.noneOf(Component.class) : EnumSet.copyOf(components);
        remaining.remove(component);
        return new ConfidentialContentState(remaining, unencryptedContentAllowed);
    }

    public boolean requires(final Component component) { return components.contains(component); }
    public boolean isRequired() { return !components.isEmpty(); }
    public boolean isUnencryptedContentAllowed() { return unencryptedContentAllowed; }
}
