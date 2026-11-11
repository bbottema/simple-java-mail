package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.io.Serializable;

import static java.util.Objects.requireNonNull;

/** Immutable choices for one reply or reply-all operation. Obtain options with {@link #builder()}. */
@EqualsAndHashCode
@ToString
public final class ReplyOptions implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String quotingTemplate;
    private final boolean unencryptedContentAllowed;

    private ReplyOptions(final Builder builder) {
        quotingTemplate = builder.quotingTemplate;
        unencryptedContentAllowed = builder.unencryptedContentAllowed;
    }

    /** Starts with the default quote and confidential-content safeguard. */
    public static Builder builder() { return new Builder(); }

    /** Starts a builder with these choices. */
    public Builder toBuilder() { return new Builder().withQuotingTemplate(quotingTemplate).withUnencryptedContentAllowed(unencryptedContentAllowed); }

    /** Uses the default quotation and requires protection for automatically reused confidential content. */
    public static ReplyOptions defaults() { return builder().build(); }

    /** Deliberately permits this reply draft to include automatically reused confidential content in unencrypted output.
     * @see Builder#withUnencryptedContentAllowed(boolean)
     */
    public static ReplyOptions allowUnencryptedContent() { return builder().withUnencryptedContentAllowed(true).build(); }

    /** @see Builder#withQuotingTemplate(String) */
    public @NotNull String getQuotingTemplate() { return quotingTemplate; }

    /** @see Builder#withUnencryptedContentAllowed(boolean) */
    public boolean isUnencryptedContentAllowed() { return unencryptedContentAllowed; }

    /** Supplied choices for a reply operation; building options performs no reading or cryptography. */
    public static final class Builder {
        private String quotingTemplate = EmailStartingBuilder.DEFAULT_QUOTING_MARKUP;
        private boolean unencryptedContentAllowed = EmailStartingBuilder.DEFAULT_ALLOW_UNENCRYPTED_CONTENT;

        private Builder() { }

        /**
         * Chooses the HTML quote template, applied once to the selected original HTML (or its generated plain-text alternative).
         * Include {@code %s} to include the quotation, for example {@code <blockquote>%s</blockquote>}.
         * Defaults to {@link EmailStartingBuilder#DEFAULT_QUOTING_MARKUP}. Plain and HTML quotes remain independently editable.
         * Formatting and format validation occur while preparing the reply. Only original inline resources referenced by the final
         * formatted HTML are included; a template that omits the quotation does not read or include its unused images.
         * @throws NullPointerException If the template is null; use {@link #resetQuotingTemplate()} for the default.
         */
        public Builder withQuotingTemplate(final @NotNull String template) { quotingTemplate = requireNonNull(template, "quotingTemplate"); return this; }

        /** @see #withQuotingTemplate(String) */
        public Builder resetQuotingTemplate() { quotingTemplate = EmailStartingBuilder.DEFAULT_QUOTING_MARKUP; return this; }

        /** @see #withQuotingTemplate(String) */
        public @NotNull String getQuotingTemplate() { return quotingTemplate; }

        /**
         * Records deliberate permission to include automatically quoted confidential content without outgoing encryption.
         * The default is {@value EmailStartingBuilder#DEFAULT_ALLOW_UNENCRYPTED_CONTENT}. Otherwise finalized MIME/EML and sending
         * require successful suitable outgoing encryption. Copies of this reply draft preserve the choice; a new helper operation uses
         * its own options. Permission does not disable configured or mandatory encryption, and never changes safe public subject/threading choices.
         * Explicitly clearing reused components removes their obligations; editing text alone does not grant permission.
         */
        public Builder withUnencryptedContentAllowed(final boolean allowed) { unencryptedContentAllowed = allowed; return this; }

        /** @see #withUnencryptedContentAllowed(boolean) */
        public Builder resetUnencryptedContentAllowed() { unencryptedContentAllowed = EmailStartingBuilder.DEFAULT_ALLOW_UNENCRYPTED_CONTENT; return this; }

        /** @see #withUnencryptedContentAllowed(boolean) */
        public boolean isUnencryptedContentAllowed() { return unencryptedContentAllowed; }

        /** Captures immutable choices without preparing a reply or performing cryptography. */
        public ReplyOptions build() { return new ReplyOptions(this); }
    }
}
