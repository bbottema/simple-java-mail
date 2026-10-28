package org.simplejavamail.email.internal;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayOutputStream;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.EmailStartingBuilder.DEFAULT_QUOTING_MARKUP;

class ReplyBodyAlternativesTest {

    private static final String CUSTOM_QUOTE = "<section class=\"original-quote\">%s</section>";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @EnumSource(ReplyRoute.class)
    void plainOnlyOriginalGetsAnHtmlQuoteAcrossEveryStarter(final ReplyRoute route) {
        final Email original = original().withPlainText("Please ship the order.").buildEmail();
        final Email reply = reply(route, original).buildEmail();

        assertThat(reply.getPlainText()).isEqualTo("> Please ship the order.");
        assertThat(reply.getHTMLText()).isEqualTo(format(quoteTemplate(route), "Please ship the order."));
        assertRoutingAndThread(reply, route);
    }

    @ParameterizedTest
    @EnumSource(ReplyRoute.class)
    void htmlOnlyOriginalGetsAPlainQuoteAcrossEveryStarter(final ReplyRoute route) {
        final String html = "<p>Please <b>ship</b> the order.</p>";
        final Email original = original().withHTMLText(html).buildEmail();
        final Email reply = reply(route, original).buildEmail();

        assertThat(reply.getPlainText()).isEqualTo("> Please ship the order.");
        assertThat(reply.getHTMLText()).isEqualTo(format(quoteTemplate(route), html));
        assertRoutingAndThread(reply, route);
        assertThat(original.getHTMLText()).isEqualTo(html);
        assertThat(original.getPlainText()).isNull();
    }

    @ParameterizedTest
    @EnumSource(ReplyRoute.class)
    void suppliedAlternativesKeepTheirIndependentWords(final ReplyRoute route) {
        final Email original = original().withPlainText("Plain summary.").withHTMLText("<p>Different HTML wording.</p>").buildEmail();
        final Email reply = reply(route, original).buildEmail();

        assertThat(reply.getPlainText()).isEqualTo("> Plain summary.");
        assertThat(reply.getHTMLText()).isEqualTo(format(quoteTemplate(route), "<p>Different HTML wording.</p>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void plainTextIsEscapedWithItsLineBreaksBeforeCustomQuoting(final String lineBreak) {
        final Email original = original().withPlainText("Ship <parcel> & box" + lineBreak + "Next 100% ready.").buildEmail();
        final Email reply = mail.emailBuilder().replyingTo(original, CUSTOM_QUOTE).buildEmail();

        assertThat(reply.getHTMLText()).isEqualTo("<section class=\"original-quote\">Ship &lt;parcel&gt; &amp; box<br>Next 100% ready.</section>");
        assertThat(reply.getHTMLText()).containsOnlyOnce("class=\"original-quote\"");
        assertThat(original.getPlainText()).isEqualTo("Ship <parcel> & box" + lineBreak + "Next 100% ready.");
    }

    @Test
    void htmlTextRetainsParagraphsListsLinksAndAltWithoutFetchingOrScriptStyleText() {
        final String html = "<p>Hello &amp; welcome.</p><p>Visit <a href=\"https://example.org/order?x=1&amp;y=2\">your order</a>."
                + "<br>Next line.</p><ul><li>First parcel</li><li>Second parcel <img src=\"https://127.0.0.1:1/unreachable.png\" "
                + "alt=\"Label &amp; logo\"></li></ul><script>unwantedScript()</script><style>.unwantedStyle { color:red }</style>";
        final Email original = original().withHTMLText(html).buildEmail();
        final Email reply = mail.emailBuilder().replyingTo(original).buildEmail();

        assertThat(reply.getPlainText()).contains("Hello & welcome.", "your order", "https://example.org/order?x=1&y=2", "Label & logo")
                .doesNotContain("unwantedScript", "unwantedStyle", "unreachable.png", "<p>", "<script>");
        assertThat(reply.getPlainText().lines()).anySatisfy(line -> assertThat(line).contains("First parcel").doesNotContain("Second parcel"))
                .anySatisfy(line -> assertThat(line).contains("Second parcel").doesNotContain("First parcel"))
                .anySatisfy(line -> assertThat(line).isEqualTo("> Next line."));
        assertThat(reply.getPlainText()).contains("welcome.\n> \n> Visit");
        assertThat(reply.getHTMLText()).isEqualTo(format(DEFAULT_QUOTING_MARKUP, html));
        assertThat(original.getHTMLText()).isEqualTo(html);
    }

    @Test
    void generatedAlternativesAreIndependentAfterPriming() {
        final EmailPopulatingBuilder reply = mail.emailBuilder().replyingTo(original().withPlainText("Original.").buildEmail());
        final String generatedHtml = reply.buildEmail().getHTMLText();
        reply.withPlainText("Replacement plain.");
        assertThat(reply.buildEmail().getHTMLText()).isEqualTo(generatedHtml);
        reply.prependHTMLText("<p>HTML answer.</p>");
        assertThat(reply.buildEmail().getPlainText()).isEqualTo("Replacement plain.");
        reply.clearPlainText();
        assertThat(reply.buildEmail().getPlainText()).isNull();
        assertThat(reply.buildEmail().getHTMLText()).startsWith("<p>HTML answer.</p>");
        reply.clearHTMLText().appendPlainText("Only plain now.");
        assertThat(reply.buildEmail().getHTMLText()).isNull();
        assertThat(reply.buildEmail().getPlainText()).isEqualTo("Only plain now.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<html><head><title>Internal subject</title><style>.hidden { color:red }</style></head><body><p>Visible quote.</p></body></html>",
            "<p>Visible quote.</p>"
    })
    void fullDocumentsAndFragmentsQuoteOnlyBodyText(final String html) {
        final Email original = original().withHTMLText(html).buildEmail();
        final Email reply = mail.emailBuilder().replyingTo(original).buildEmail();
        assertThat(reply.getPlainText()).isEqualTo("> Visible quote.");
        assertThat(reply.getHTMLText()).isEqualTo(format(DEFAULT_QUOTING_MARKUP, html));
        assertThat(original.getHTMLText()).isEqualTo(html);
    }

    @Test
    void suppliedBodiesStillFollowOrdinaryDefaultsAndOverridesAfterPriming() throws Exception {
        final Email defaults = mail.emailBuilder().startingBlank().withPlainText("Default plain.").withHTMLText("Default HTML.").buildEmail();
        final Email overrides = mail.emailBuilder().startingBlank().withHTMLText("Override HTML.").buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServerHost("localhost").withEmailDefaults(defaults).withEmailOverrides(overrides).buildMailer()) {
            final EmailPopulatingBuilder reply = mail.emailBuilder().replyingTo(original().withPlainText("Original.").buildEmail());
            final Email completed = reply.buildEmailCompletedWithDefaultsAndOverrides(mailer.getEmailGovernance());
            assertThat(completed.getPlainText()).isEqualTo("> Original.");
            assertThat(completed.getHTMLText()).isEqualTo("Override HTML.");
            final Email cleared = reply.clearPlainText().buildEmailCompletedWithDefaultsAndOverrides(mailer.getEmailGovernance());
            assertThat(cleared.getPlainText()).isEqualTo("Default plain.");
            assertThat(cleared.getHTMLText()).isEqualTo("Override HTML.");
        }
    }

    @Test
    void replyRetainsSourceMimeBytesAndEmbeddedImages() throws Exception {
        final AttachmentResource image = AttachmentResource.builder().withName("logo.png").withContentId("logo@example.org")
                .withData(new byte[] {0, 1, 2, 3}, "image/png").build();
        final String html = "<p>Source <img src=\"cid:logo@example.org\" alt=\"Parcel logo\"></p>";
        final Email original = original().withHTMLText(html).withEmbeddedImage(image).buildEmail();
        final MimeMessage source = EmailConverter.emailToMimeMessage(original);
        final ByteArrayOutputStream before = new ByteArrayOutputStream();
        source.writeTo(before);
        final Email reply = mail.emailBuilder().replyingToAll(source, CUSTOM_QUOTE).buildEmail();
        final ByteArrayOutputStream after = new ByteArrayOutputStream();
        source.writeTo(after);

        assertThat(after.toByteArray()).isEqualTo(before.toByteArray());
        assertThat(reply.getEmbeddedImages()).extracting(AttachmentResource::getContentId).containsExactly("logo@example.org");
        assertThat(reply.getEmbeddedImages().get(0).readAllBytes()).isEqualTo(new byte[] {0, 1, 2, 3});
        assertThat(reply.getPlainText()).contains("Parcel logo");
        assertThat(reply.getHTMLText()).isEqualTo(format(CUSTOM_QUOTE, html));
        assertThat(original.getEmbeddedImages()).containsExactly(image);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain", "html"})
    void emptyOrAbsentBodyRetainsTheExistingFallback(final String emptyFormat) {
        final EmailPopulatingBuilder original = original();
        if ("plain".equals(emptyFormat)) original.withPlainText("");
        if ("html".equals(emptyFormat)) original.withHTMLText("");
        final Email reply = mail.emailBuilder().replyingTo(original.buildEmail()).buildEmail();
        assertThat(reply.getPlainText()).isEmpty();
        assertThat(reply.getHTMLText()).isEqualTo(format(DEFAULT_QUOTING_MARKUP, ""));
    }

    @Test
    void whitespaceOnlySuppliedRepresentationsAreNotRegenerated() {
        final Email original = original().withPlainText("   ").withHTMLText("<p>Different HTML.</p>").buildEmail();
        final Email reply = mail.emailBuilder().replyingTo(original).buildEmail();
        assertThat(reply.getPlainText()).isEqualTo(">    ");
        assertThat(reply.getHTMLText()).isEqualTo(format(DEFAULT_QUOTING_MARKUP, "<p>Different HTML.</p>"));
        final Email whitespaceHtml = original().withPlainText("Plain original.").withHTMLText("   ").buildEmail();
        assertThat(mail.emailBuilder().replyingTo(whitespaceHtml).buildEmail().getHTMLText())
                .isEqualTo(format(DEFAULT_QUOTING_MARKUP, "   "));
    }

    private EmailPopulatingBuilder original() {
        return mail.emailBuilder().startingBlank().from("alice@example.org").to("bob@example.org").cc("carol@example.org")
                .withReplyTo("reply@example.org").withSubject("Order").fixingMessageId("<source@example.org>")
                .withHeader("References", "<earlier@example.org>");
    }

    private EmailPopulatingBuilder reply(final ReplyRoute route, final Email original) {
        switch (route) {
            case EMAIL_REPLY: return mail.emailBuilder().replyingTo(original);
            case EMAIL_REPLY_CUSTOM: return mail.emailBuilder().replyingTo(original, CUSTOM_QUOTE);
            case EMAIL_REPLY_ALL: return mail.emailBuilder().replyingToAll(original);
            case EMAIL_REPLY_ALL_CUSTOM: return mail.emailBuilder().replyingToAll(original, CUSTOM_QUOTE);
            default:
                final MimeMessage message = EmailConverter.emailToMimeMessage(original);
                switch (route) {
                    case MIME_REPLY: return mail.emailBuilder().replyingTo(message);
                    case MIME_REPLY_CUSTOM: return mail.emailBuilder().replyingTo(message, CUSTOM_QUOTE);
                    case MIME_REPLY_ALL: return mail.emailBuilder().replyingToAll(message);
                    case MIME_REPLY_ALL_CUSTOM: return mail.emailBuilder().replyingToAll(message, CUSTOM_QUOTE);
                    case MIME_BOOLEAN_ALL_CUSTOM: return mail.emailBuilder().replyingTo(message, true, CUSTOM_QUOTE);
                    default: throw new AssertionError(route);
                }
        }
    }

    private static String quoteTemplate(final ReplyRoute route) {
        return route.name().endsWith("CUSTOM") ? CUSTOM_QUOTE : DEFAULT_QUOTING_MARKUP;
    }

    private static void assertRoutingAndThread(final Email reply, final ReplyRoute route) {
        assertThat(reply.getSubject()).isEqualTo("Re: Order");
        assertThat(reply.getRecipients()).extracting(Recipient::getAddress).contains("reply@example.org");
        if (route.name().contains("ALL")) {
            assertThat(reply.getRecipients()).extracting(Recipient::getAddress).contains("carol@example.org");
        } else {
            assertThat(reply.getRecipients()).extracting(Recipient::getAddress).doesNotContain("carol@example.org");
        }
        assertThat(reply.getHeaders().get("In-Reply-To")).containsExactly("<source@example.org>");
        assertThat(reply.getHeaders().get("References")).containsExactly("<earlier@example.org> <source@example.org>");
    }

    private enum ReplyRoute {
        EMAIL_REPLY, EMAIL_REPLY_CUSTOM, EMAIL_REPLY_ALL, EMAIL_REPLY_ALL_CUSTOM,
        MIME_REPLY, MIME_REPLY_CUSTOM, MIME_REPLY_ALL, MIME_REPLY_ALL_CUSTOM, MIME_BOOLEAN_ALL_CUSTOM
    }
}
