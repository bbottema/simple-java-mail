package org.simplejavamail.api.email;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.email.internal.InternalEmail;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.Base64;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailConstructionContractTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void releasedPlainEmailRemainsReadableButExplainsTheSupportedConstructionRoute() throws Exception {
        final Email email = readReleasedEmail("email-9.3.6-plain.base64");
        assertReleasedMessage(email);
        assertThat(email.getClass()).isEqualTo(Email.class);

        assertThatThrownBy(() -> mail.emailBuilder().copying(email))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("buildEmail()");
        assertThatThrownBy(() -> mail.converter().fromEmail(email).toMimeMessage())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("buildEmail()");
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 2525).buildMailer()) {
            assertThatThrownBy(() -> mailer.rehearse(email, false))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("buildEmail()");
        }
    }

    @Test
    void releasedBuilderEmailStillSupportsCopyingConversionAndRehearsal() throws Exception {
        final Email email = readReleasedEmail("email-9.3.6-builder.base64");
        assertReleasedMessage(email);
        assertThat(email).isInstanceOf(InternalEmail.class);

        final Email copy = mail.emailBuilder().copying(email).buildEmail();
        assertReleasedMessage(copy);
        assertThat(mail.converter().fromEmail(copy).toMimeMessage().getSubject()).isEqualTo(email.getSubject());
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 2525).buildMailer()) {
            assertThat(mailer.rehearse(copy, false).getEnvelopeRecipients()).containsExactly("recipient@example.test");
        }
    }

    private void assertReleasedMessage(final Email email) {
        assertThat(email.getSubject()).isEqualTo("Released 9.3.6 snapshot");
        assertThat(email.getPlainText()).isEqualTo("Released body");
        assertThat(email.getFromRecipient().getAddress()).isEqualTo("sender@example.test");
        assertThat(email.getRecipients()).extracting(Recipient::getAddress).containsExactly("recipient@example.test");
    }

    private Email readReleasedEmail(final String resource) throws Exception {
        final byte[] serialized;
        try (InputStream input = Objects.requireNonNull(getClass().getResourceAsStream("/serialization/" + resource), resource)) {
            serialized = Base64.getDecoder().decode(new String(input.readAllBytes(), US_ASCII).trim());
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            return (Email) input.readObject();
        }
    }
}
