package org.simplejavamail.api.email;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import java.io.Serializable;

/** Immutable choices for one interpreted forwarding operation. */
@EqualsAndHashCode
@ToString
public final class ForwardOptions implements Serializable {
    private static final long serialVersionUID = 1L;
    private final boolean unencryptedContentAllowed;

    private ForwardOptions(final Builder builder) { unencryptedContentAllowed = builder.unencryptedContentAllowed; }

    /** Starts with the confidential-content safeguard. */
    public static Builder builder() { return new Builder(); }

    /** Starts a builder with this choice. */
    public Builder toBuilder() { return new Builder().withUnencryptedContentAllowed(unencryptedContentAllowed); }

    /** Requires protection for automatically reused confidential content. */
    public static ForwardOptions defaults() { return builder().build(); }

    /** Deliberately permits this forward draft to include automatically reused confidential content in unencrypted output.
     * @see Builder#withUnencryptedContentAllowed(boolean)
     */
    public static ForwardOptions allowUnencryptedContent() { return builder().withUnencryptedContentAllowed(true).build(); }

    /** @see Builder#withUnencryptedContentAllowed(boolean) */
    public boolean isUnencryptedContentAllowed() { return unencryptedContentAllowed; }

    /** Supplied forwarding choices; building options performs no reading or cryptography. */
    public static final class Builder {
        private boolean unencryptedContentAllowed = EmailStartingBuilder.DEFAULT_ALLOW_UNENCRYPTED_CONTENT;
        private Builder() { }

        /**
         * Records deliberate permission to include confidential content from the interpreted message in unencrypted output.
         * Defaults to {@value EmailStartingBuilder#DEFAULT_ALLOW_UNENCRYPTED_CONTENT}; otherwise finalized output requires successful
         * suitable outgoing encryption. Copies of the draft retain this choice. A new forward uses its own options. Permission does not
         * disable configured or mandatory encryption, change the safe public subject, or substitute an original payload for interpreted content.
         */
        public Builder withUnencryptedContentAllowed(final boolean allowed) { unencryptedContentAllowed = allowed; return this; }

        /** @see #withUnencryptedContentAllowed(boolean) */
        public Builder resetUnencryptedContentAllowed() { unencryptedContentAllowed = EmailStartingBuilder.DEFAULT_ALLOW_UNENCRYPTED_CONTENT; return this; }

        /** @see #withUnencryptedContentAllowed(boolean) */
        public boolean isUnencryptedContentAllowed() { return unencryptedContentAllowed; }

        /** Captures immutable choices without preparing the forwarded message. */
        public ForwardOptions build() { return new ForwardOptions(this); }
    }
}
