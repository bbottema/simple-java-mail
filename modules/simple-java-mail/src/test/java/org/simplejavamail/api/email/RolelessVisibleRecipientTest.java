package org.simplejavamail.api.email;

import jakarta.mail.Address;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Collections.singleton;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class RolelessVisibleRecipientTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void compositionDefaultsOnlyVisibleRolesAndRetainsMetadataAndOccurrences() {
        final X509Certificate certificate = mock(X509Certificate.class);
        final Recipient alice = new Recipient("Alice", "alice@example.org", null, certificate, singleton(FAILURE));
        final Recipient cc = mail.recipientBuilder().cc("Audit", "audit@example.org");
        final Recipient bcc = mail.recipientBuilder().bcc("Archive", "archive@example.org");
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().from(alice).withReplyTo(alice)
                .withRecipients(alice, cc, alice, bcc).withOverrideReceivers(alice);
        final List<Recipient> expected = List.of(withRole(alice, TO), cc, withRole(alice, TO), bcc);

        assertThat(builder.getRecipients()).containsExactlyElementsOf(expected);
        final Email email = builder.buildEmail();
        assertThat(email.getRecipients()).containsExactlyElementsOf(expected);
        assertThat(email.getToRecipients()).containsExactly(withRole(alice, TO), withRole(alice, TO));
        assertThat(email.getCcRecipients()).containsExactly(cc);
        assertThat(email.getBccRecipients()).containsExactly(bcc);
        assertThat(email.getRecipients().get(1)).isSameAs(cc);
        assertThat(email.getRecipients().get(3)).isSameAs(bcc);
        assertThat(email.getFromRecipient().getType()).isNull();
        assertThat(email.getReplyToRecipients()).extracting(Recipient::getType).containsExactly((RecipientType) null);
        assertThat(email.getOverrideReceivers()).containsExactly(alice);
        assertThat(email.getOverrideReceivers().get(0)).isSameAs(alice);
        assertThat(alice.getType()).isNull();
        assertThat(alice.getSmimeCertificate()).isSameAs(certificate);
        assertThat(alice.getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);
        verifyNoInteractions(certificate);
    }

    @Test
    void copyingAndRepeatedCompletionKeepEveryVisibleOccurrence() throws Exception {
        final Recipient alice = mail.recipientBuilder().withName("Alice").withAddress("alice@example.org")
                .withDeliveryStatusNotificationNotifyOptions(FAILURE).build();
        final Recipient expectedAlice = withRole(alice, TO);
        final Email draft = message().withRecipients(alice, alice).to("bob@example.org").cc("audit@example.org").buildEmail();
        final Email copy = mail.emailBuilder().copying(draft).buildEmail();

        assertThat(copy.getToRecipients()).containsExactly(expectedAlice, expectedAlice, mail.recipientBuilder().to("bob@example.org"));
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            final Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(copy);
            final Email repeated = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(completed);
            assertThat(completed.getRecipients()).containsExactlyElementsOf(copy.getRecipients());
            assertThat(repeated.getRecipients()).containsExactlyElementsOf(copy.getRecipients());
        }
        assertThat(alice.getType()).isNull();
    }

    @Test
    void ordinaryMimeRetainsRolelessRecipientsAlongsideTypedRecipients() throws Exception {
        final Recipient alice = mail.recipientBuilder().withName("Alice").withAddress("alice@example.org").build();
        final Email draft = message().withRecipients(alice, alice).to("bob@example.org")
                .cc("audit@example.org").bcc("archive@example.org").buildEmail();

        final MimeMessage mime = mail.converter().fromEmail(draft).toMimeMessage();

        assertThat(mime.getRecipients(TO)).extracting(address -> ((InternetAddress) address).getAddress())
                .containsExactly("alice@example.org", "alice@example.org", "bob@example.org");
        assertThat(mime.getRecipients(CC)).extracting(Address::toString).containsExactly("audit@example.org");
        assertThat(mime.getRecipients(BCC)).extracting(Address::toString).containsExactly("archive@example.org");
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(mailer.rehearse(draft).getEffectiveEmail().getToRecipients()).extracting(Recipient::getAddress)
                    .containsExactly("alice@example.org", "alice@example.org", "bob@example.org");
        }
        assertThat(alice.getType()).isNull();
    }

    @Test
    void supportedSnapshotRestorationDefaultsLegacyVisibleRolesWithoutChangingSharedRecipients() throws Exception {
        final Recipient alice = mail.recipientBuilder().withName("Alice").withAddress("alice@example.org")
                .withDeliveryStatusNotificationNotifyOptions(FAILURE).build();
        final Recipient cc = mail.recipientBuilder().cc("audit@example.org");
        final Recipient bcc = mail.recipientBuilder().bcc("archive@example.org");
        final Email email = message().withReplyTo(alice).buildEmail();
        // Recreate the role-less visible entries allowed by supported earlier Email snapshots.
        final Field recipients = Email.class.getDeclaredField("recipients");
        recipients.setAccessible(true);
        recipients.set(email, new ArrayList<>(List.of(alice, cc, alice, bcc)));

        final SharedRecipientSnapshot restored = roundTrip(new SharedRecipientSnapshot(email, alice));

        assertThat(restored.email.getRecipients()).containsExactly(withRole(alice, TO), cc, withRole(alice, TO), bcc);
        assertThat(restored.email.getToRecipients()).containsExactly(withRole(alice, TO), withRole(alice, TO));
        assertThat(restored.contact.getType()).isNull();
        assertThat(restored.email.getFromRecipient().getType()).isNull();
        assertThat(restored.email.getReplyToRecipients()).extracting(Recipient::getType).containsExactly((RecipientType) null);
        assertThat(mail.emailBuilder().copying(restored.email).buildEmail().getRecipients())
                .containsExactlyElementsOf(restored.email.getRecipients());
    }

    private EmailPopulatingBuilder message() {
        return mail.emailBuilder().startingBlank().from("sender@example.org").withPlainText("body");
    }

    private static Recipient withRole(final Recipient recipient, final RecipientType type) {
        return new Recipient(recipient.getName(), recipient.getAddress(), type, recipient.getSmimeCertificate(),
                recipient.getDeliveryStatusNotificationNotifyOptions());
    }

    private static SharedRecipientSnapshot roundTrip(final SharedRecipientSnapshot value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (SharedRecipientSnapshot) input.readObject();
        }
    }

    private static final class SharedRecipientSnapshot implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Email email;
        private final Recipient contact;

        private SharedRecipientSnapshot(final Email email, final Recipient contact) {
            this.email = email;
            this.contact = contact;
        }
    }
}
