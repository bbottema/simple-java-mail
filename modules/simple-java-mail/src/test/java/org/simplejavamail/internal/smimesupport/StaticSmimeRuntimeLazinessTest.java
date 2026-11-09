package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import org.simplejavamail.mailer.MailerHelper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StaticSmimeRuntimeLazinessTest {
    @Test
    @Timeout(30)
    void plainStaticExportsAndNoOpProtectionDoNotReadConventionalConfiguration() throws Exception {
        final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dsimplejavamail.smime.cache.capacity=not-an-integer", "-cp", classpath, Probe.class.getName())
                .redirectErrorStream(true).start();
        try {
            assertThat(child.waitFor(20, TimeUnit.SECONDS)).isTrue();
            final String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(child.exitValue()).withFailMessage(output).isZero();
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(final String[] ignored) throws Exception {
            final SimpleJavaMail explicit = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
            final Email email = explicit.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                    .withPlainText("Ordinary content").buildEmail();
            final MimeMessage exported = EmailConverter.emailToMimeMessage(email);
            if (!exported.getContent().toString().contains("Ordinary content")) throw new AssertionError("Plain export changed");
            final MimeMessage raw = new MimeMessage(Session.getInstance(new Properties()));
            raw.setText("Unprotected content", "UTF-8");
            if (MailerHelper.signAndOrEncryptMessageWithSmime(raw.getSession(), raw, email) != raw) {
                throw new AssertionError("No-op protection changed the input");
            }
        }
    }
}
