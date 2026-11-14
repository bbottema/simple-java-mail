package org.simplejavamail.mailer.internal;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.internal.util.MiscUtil;
import testutil.ConfigLoaderTestHelper;
import testutil.smtp.ScriptedSmtpServer;
import testutil.smtp.SmtpConversation;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.parallel.Resources.LOCALE;
import static org.simplejavamail.mailer.internal.SmtpContentNegotiationCharacterizationTest.assertCompatibilityFailure;
import static org.simplejavamail.mailer.internal.SmtpContentNegotiationCharacterizationTest.builder;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.acceptMessageAfterMailFrom;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;

@Tag("fresh-parent-jvm") // The deterministic CID fixture reseeds the process-global random generator.
@ResourceLock(LOCALE)
@ResourceLock("MiscUtil.RANDOM")
@Timeout(30)
class AutomaticImageCidLocaleTest {
    private static final String EXPECTED_CID = "gituschoku";
    private static final String MAIL_FROM = "MAIL FROM:<sender@example.test>";
    private static final String RECIPIENT = "receiver@example.test";
    private static final byte[] IMAGE = {1, 2, 3};
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @TempDir
    Path images;

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR", "az-AZ"})
    void automaticImageIdentityIsAsciiAndMatchesTheHtmlRegardlessOfLocale(final String languageTag) throws Exception {
        try (LocaleScope ignored = new LocaleScope(languageTag)) {
            final Email email = automaticallyEmbeddedEmail();
            assertThat(email.getEmbeddedImages()).hasSize(1);
            assertThat(email.getEmbeddedImages().get(0).getName()).isEqualTo(EXPECTED_CID).matches("[a-z]{10}");
            assertThat(email.getHTMLText()).isEqualTo("<img src='cid:" + EXPECTED_CID + "'>");
            assertThat(email.getEmbeddedImages().get(0).readAllBytes()).containsExactly(IMAGE);
            final MimeMessage mime = mail.converter().fromEmail(email).toMimeMessage();
            final MimeMultipart parts = (MimeMultipart) mime.getContent();
            assertThat(parts.getBodyPart(1).getHeader("Content-ID")).containsExactly("<" + EXPECTED_CID + ">");
        }
    }

    @Test
    void automaticTurkishLocaleImagesSubmitWithoutSmtpUtf8OrEightBitMime() throws Exception {
        try (LocaleScope ignored = new LocaleScope("tr-TR")) {
            final Email email = automaticallyEmbeddedEmail();
            final AtomicReference<String> received = new AtomicReference<>();
            try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
                peer.greet("250 localhost");
                final String command = firstCommand(peer);
                if ("QUIT".equals(command)) {
                    peer.reply("221 bye");
                    peer.expectClosed();
                    return; // Let a baseline local compatibility refusal retain its original assertion.
                }
                assertThat(command).isEqualTo(MAIL_FROM);
                received.set(acceptMessageAfterMailFrom(peer, "", RECIPIENT));
                finishConnection(peer);
            }); Mailer mailer = builder(server).buildMailer()) {
                assertThat(catchThrowable(() -> mailer.sync().sendMail(email)))
                        .as("ASCII mail with an automatically generated image ID needs no internationalized SMTP extension").isNull();
            }
            assertThat(received.get()).contains("Content-ID: <" + EXPECTED_CID + ">", "cid:" + EXPECTED_CID)
                    .doesNotContain("\u0131");
        }
    }

    @Test
    void genuineInternationalizedRecipientStillRequiresSmtpUtf8() throws Exception {
        try (LocaleScope ignored = new LocaleScope("tr-TR");
             ScriptedSmtpServer server = new ScriptedSmtpServer(1, UTF_8, peer -> {
                 peer.greet("250 localhost");
                 finishConnection(peer);
             }); Mailer mailer = builder(server).buildMailer()) {
            final Email email = mail.emailBuilder().startingBlank().from("sender@example.test").to("m\u00fcller@example.test")
                    .fixingMessageId("<locale-control@example.test>").withPlainText("ASCII body").buildEmail();
            assertCompatibilityFailure(catchThrowable(() -> mailer.sync().sendMail(email)), "does not advertise SMTPUTF8");
        }
    }

    @Test
    void explicitlySuppliedImageIdentitiesKeepTheirCase() throws Exception {
        try (LocaleScope ignored = new LocaleScope("tr-TR")) {
            final String id = "KeepI.Mixed@example.test";
            final Email email = mail.emailBuilder().startingBlank().withEmbeddedImage(id, IMAGE, "image/png")
                    .withHTMLText("<img src='cid:" + id + "'>").buildEmail();
            assertThat(email.getEmbeddedImages().get(0).getName()).isEqualTo(id);
            assertThat(email.getHTMLText()).isEqualTo("<img src='cid:" + id + "'>");
        }
    }

    private Email automaticallyEmbeddedEmail() throws Exception {
        Files.write(images.resolve("logo.png"), IMAGE);
        // This fork owns the generator: seed 14 produces gItUsChOku before case normalization.
        // A deterministic capital I exercises the locale boundary without changing production APIs or sampling random output.
        final Field field = MiscUtil.class.getDeclaredField("RANDOM");
        field.setAccessible(true);
        ((Random) field.get(null)).setSeed(14L);
        return mail.emailBuilder().startingBlank().from("sender@example.test").to(RECIPIENT)
                .fixingMessageId("<locale-image@example.test>")
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(true)
                        .withEmbeddedImageBaseDir(images.toString()).embeddedImageAutoResolutionMustBeSuccesful(true).build())
                .withHTMLText("<img src='logo.png'>").buildEmail();
    }

    private static String firstCommand(final SmtpConversation peer) throws Exception {
        String command = peer.readLine();
        int checks = 0;
        while ("NOOP".equals(command)) {
            assertThat(++checks).isLessThan(20);
            peer.reply("250 still connected");
            command = peer.readLine();
        }
        return command;
    }

    private static final class LocaleScope implements AutoCloseable {
        private final Locale previous = Locale.getDefault();
        private final Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        private final Locale format = Locale.getDefault(Locale.Category.FORMAT);

        private LocaleScope(final String languageTag) { Locale.setDefault(Locale.forLanguageTag(languageTag)); }

        @Override public void close() {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }
}
