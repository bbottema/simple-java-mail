package org.simplejavamail.api.email.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ContentTransferEncoding;
import java.io.Serializable;

import static java.util.Objects.requireNonNull;

/**
 * Immutable transfer-encoding choices for composed text, HTML and calendar content. Unset choices permit normal Email governance fallback.
 * This value records choices without reading configuration sources or message content.
 * @see EmailPopulatingBuilder#withContentEncodingConfig(ContentEncodingConfig)
 */
@Value
public class ContentEncodingConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    @Nullable ContentTransferEncoding contentTransferEncoding;
    @Nullable ContentTransferEncoding plainTextContentTransferEncoding;
    @Nullable ContentTransferEncoding htmlTextContentTransferEncoding;
    @Nullable ContentTransferEncoding calendarTextContentTransferEncoding;

    private ContentEncodingConfig(final Builder builder) {
        this.contentTransferEncoding = builder.contentTransferEncoding;
        this.plainTextContentTransferEncoding = builder.plainTextContentTransferEncoding;
        this.htmlTextContentTransferEncoding = builder.htmlTextContentTransferEncoding;
        this.calendarTextContentTransferEncoding = builder.calendarTextContentTransferEncoding;
    }

    /** @return A builder with no supplied choices. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining these supplied choices. */
    @NotNull public Builder toBuilder() { return new Builder(this); }

    /** @see Builder#withContentTransferEncoding(ContentTransferEncoding) */
    @Nullable public ContentTransferEncoding getContentTransferEncoding() { return contentTransferEncoding; }
    /** @see Builder#withPlainTextContentTransferEncoding(ContentTransferEncoding) */
    @Nullable public ContentTransferEncoding getPlainTextContentTransferEncoding() { return plainTextContentTransferEncoding; }
    /** @see Builder#withHTMLTextContentTransferEncoding(ContentTransferEncoding) */
    @Nullable public ContentTransferEncoding getHTMLTextContentTransferEncoding() { return htmlTextContentTransferEncoding; }
    /** @see Builder#withCalendarTextContentTransferEncoding(ContentTransferEncoding) */
    @Nullable public ContentTransferEncoding getCalendarTextContentTransferEncoding() { return calendarTextContentTransferEncoding; }

    /** Collects choices; build an immutable value and supply it to the Email builder. */
    public static final class Builder {
        private ContentTransferEncoding contentTransferEncoding;
        private ContentTransferEncoding plainTextContentTransferEncoding;
        private ContentTransferEncoding htmlTextContentTransferEncoding;
        private ContentTransferEncoding calendarTextContentTransferEncoding;

        private Builder() { }
        private Builder(final ContentEncodingConfig config) {
            contentTransferEncoding = config.contentTransferEncoding;
            plainTextContentTransferEncoding = config.plainTextContentTransferEncoding;
            htmlTextContentTransferEncoding = config.htmlTextContentTransferEncoding;
            calendarTextContentTransferEncoding = config.calendarTextContentTransferEncoding;
        }

        /**
         * Determines what encoding is applied to text/html/iCalendar content in the MimeMessage/EML. Default is {@link ContentTransferEncoding#QUOTED_PRINTABLE}, which basically means plain
         * text, so you can just read the content of the EML (if not encrypted). This acts as the shared fallback for all body parts unless a body-specific content transfer encoding is set.
         * <p>
         * However, you can choose another encoding as supported by Jakarta Mail. The list is quite extensive, but the most common alternative is base64. This might be useful for example for obfuscating
         * the content to some extent.
         *
         * {@link #clearContentTransferEncoding()} removes this local choice so ordinary Email defaults/overrides and the normal encoding fallback can apply.
         *
         * @param contentTransferEncoding The encoder to use for the text/html/iCalendar content.
         * @see #withPlainTextContentTransferEncoding(ContentTransferEncoding)
         * @see #withHTMLTextContentTransferEncoding(ContentTransferEncoding)
         * @see #withCalendarTextContentTransferEncoding(ContentTransferEncoding)
         */
        @NotNull public Builder withContentTransferEncoding(@NotNull ContentTransferEncoding contentTransferEncoding) { this.contentTransferEncoding = requireNonNull(contentTransferEncoding, "contentTransferEncoding"); return this; }

        /** @see #withContentTransferEncoding(ContentTransferEncoding) */
        @Nullable public ContentTransferEncoding getContentTransferEncoding() { return contentTransferEncoding; }
        /** @see #withContentTransferEncoding(ContentTransferEncoding) */
        @NotNull public Builder clearContentTransferEncoding() { contentTransferEncoding = null; return this; }

        /**
         * Determines what encoding is applied to the plain text body part in the MimeMessage/EML. If unset,
         * {@link #withContentTransferEncoding(ContentTransferEncoding)} is used as fallback.
         *
         * {@link #clearPlainTextContentTransferEncoding()} removes this local choice so ordinary Email defaults/overrides and the normal encoding fallback can apply.
         *
         * @param contentTransferEncoding The encoder to use for the plain text content.
         */
        @NotNull public Builder withPlainTextContentTransferEncoding(@NotNull ContentTransferEncoding contentTransferEncoding) { this.plainTextContentTransferEncoding = requireNonNull(contentTransferEncoding, "contentTransferEncoding"); return this; }

        /** @see #withPlainTextContentTransferEncoding(ContentTransferEncoding) */
        @Nullable public ContentTransferEncoding getPlainTextContentTransferEncoding() { return plainTextContentTransferEncoding; }
        /** @see #withPlainTextContentTransferEncoding(ContentTransferEncoding) */
        @NotNull public Builder clearPlainTextContentTransferEncoding() { plainTextContentTransferEncoding = null; return this; }

        /**
         * Determines what encoding is applied to the HTML body part in the MimeMessage/EML. If unset,
         * {@link #withContentTransferEncoding(ContentTransferEncoding)} is used as fallback.
         *
         * {@link #clearHTMLTextContentTransferEncoding()} removes this local choice so ordinary Email defaults/overrides and the normal encoding fallback can apply.
         *
         * @param contentTransferEncoding The encoder to use for the HTML content.
         */
        @NotNull public Builder withHTMLTextContentTransferEncoding(@NotNull ContentTransferEncoding contentTransferEncoding) { this.htmlTextContentTransferEncoding = requireNonNull(contentTransferEncoding, "contentTransferEncoding"); return this; }

        /** @see #withHTMLTextContentTransferEncoding(ContentTransferEncoding) */
        @Nullable public ContentTransferEncoding getHTMLTextContentTransferEncoding() { return htmlTextContentTransferEncoding; }
        /** @see #withHTMLTextContentTransferEncoding(ContentTransferEncoding) */
        @NotNull public Builder clearHTMLTextContentTransferEncoding() { htmlTextContentTransferEncoding = null; return this; }

        /**
         * Determines what encoding is applied to the iCalendar body part in the MimeMessage/EML. If unset,
         * {@link #withContentTransferEncoding(ContentTransferEncoding)} is used as fallback.
         *
         * {@link #clearCalendarTextContentTransferEncoding()} removes this local choice so ordinary Email defaults/overrides and the normal encoding fallback can apply.
         *
         * @param contentTransferEncoding The encoder to use for the iCalendar content.
         */
        @NotNull public Builder withCalendarTextContentTransferEncoding(@NotNull ContentTransferEncoding contentTransferEncoding) { this.calendarTextContentTransferEncoding = requireNonNull(contentTransferEncoding, "contentTransferEncoding"); return this; }

        /** @see #withCalendarTextContentTransferEncoding(ContentTransferEncoding) */
        @Nullable public ContentTransferEncoding getCalendarTextContentTransferEncoding() { return calendarTextContentTransferEncoding; }
        /** @see #withCalendarTextContentTransferEncoding(ContentTransferEncoding) */
        @NotNull public Builder clearCalendarTextContentTransferEncoding() { calendarTextContentTransferEncoding = null; return this; }

        /** @return An immutable snapshot; attachments retain their own encoding choices. */
        @NotNull public ContentEncodingConfig build() { return new ContentEncodingConfig(this); }
    }
}
