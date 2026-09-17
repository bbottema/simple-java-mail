package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.spi.ContentRequirement;
import org.simplejavamail.api.mailer.spi.DeliveryEnvelope;
import org.simplejavamail.api.mailer.spi.DeliveryRecipient;
import org.simplejavamail.api.mailer.spi.MailTransportAdapter;
import org.simplejavamail.api.mailer.spi.MailTransportCompatibilityException;
import org.simplejavamail.api.mailer.spi.PreparedMail;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.*;
import static org.simplejavamail.internal.mailprovider.angus.AngusRecipientCommands.originalRecipientParameter;

class AngusRecipientCommandsTest {

    private final Session session = Session.getInstance(new Properties());

    @Test
    void duplicateOccurrencesKeepSeparatePreferencesAndDoNotMergeWithSharedNotify() throws Exception {
        final String mailbox = "same+tag@example.test";
        final AngusRecipientCommands commands = prepareRecipientCommands(new String[]{mailbox, mailbox, mailbox},
                List.of(new DeliveryRecipient(mailbox, List.of(NEVER)), new DeliveryRecipient(mailbox, List.of(DELAY, FAILURE)),
                        new DeliveryRecipient(mailbox, emptyList())));
        final String original = "RCPT TO:<" + mailbox + "> NOTIFY=SUCCESS";
        assertThat(commands.applyToRecipientCommand(original, 0))
                .isEqualTo("RCPT TO:<same+tag@example.test> NOTIFY=NEVER ORCPT=rfc822;same+2Btag@example.test");
        assertThat(commands.applyToRecipientCommand(original, 1)).contains(" NOTIFY=FAILURE,DELAY ORCPT=").doesNotContain("SUCCESS");
        assertThat(commands.applyToRecipientCommand(original, 2)).contains(" NOTIFY=SUCCESS ORCPT=");
        assertThat(commands.applyToRecipientCommand(original, 0)).contains(" NOTIFY=NEVER ORCPT="); // No mutable traversal cursor.
    }

    @Test
    void notifyOverridePreservesOtherParametersAndWorksWithoutSharedDefault() throws Exception {
        final AngusRecipientCommands commands = prepareRecipientCommands(new String[]{"a@example.test"},
                List.of(new DeliveryRecipient("a@example.test", List.of(NEVER))));
        assertThat(commands.applyToRecipientCommand("RCPT TO:<a@example.test> XBEFORE=kept nOtIfY=SUCCESS XAFTER=kept", 0))
                .isEqualTo("RCPT TO:<a@example.test> XBEFORE=kept XAFTER=kept NOTIFY=NEVER ORCPT=rfc822;a@example.test");
        assertThat(commands.applyToRecipientCommand("RCPT TO:<a@example.test>", 0))
                .isEqualTo("RCPT TO:<a@example.test> NOTIFY=NEVER ORCPT=rfc822;a@example.test");
    }

    @Test
    void reorderedPoliciesAreRejectedInsteadOfRemapped() {
        assertThatThrownBy(() -> prepareRecipientCommands(new String[]{"a@example.test", "b@example.test"},
                List.of(new DeliveryRecipient("b@example.test", List.of(NEVER)), new DeliveryRecipient("a@example.test", List.of(SUCCESS)))))
                .isInstanceOf(MailTransportCompatibilityException.class)
                .hasMessageContaining("matching addresses in the same order, including duplicates");
    }

    @Test
    void rfcGroupsExpandInProviderOrderWithoutDeduplicatingMembers() throws Exception {
        final String group = "Team:a@example.test,b@example.test;";
        final AngusRecipientCommands commands = prepareRecipientCommands(new String[]{group, "a@example.test"},
                List.of(new DeliveryRecipient(group, List.of(NEVER)), new DeliveryRecipient("a@example.test", List.of(SUCCESS))));
        assertThat(commands.applyToRecipientCommand("RCPT TO:<a@example.test>", 0)).contains("NOTIFY=NEVER ORCPT=rfc822;a@example.test");
        assertThat(commands.applyToRecipientCommand("RCPT TO:<b@example.test>", 1)).contains("NOTIFY=NEVER ORCPT=rfc822;b@example.test");
        assertThat(commands.applyToRecipientCommand("RCPT TO:<a@example.test>", 2)).contains("NOTIFY=SUCCESS ORCPT=rfc822;a@example.test");
    }

    @Test
    void emptyRfcGroupsDoNotConsumeCommandPreference() throws Exception {
        final String emptyGroup = "Empty:;";
        final AngusRecipientCommands commands = prepareRecipientCommands(new String[]{emptyGroup, "a@example.test"},
                List.of(new DeliveryRecipient(emptyGroup, List.of(NEVER)), new DeliveryRecipient("a@example.test", List.of(SUCCESS))));
        assertThat(commands.applyToRecipientCommand("RCPT TO:<a@example.test>", 0)).contains("NOTIFY=SUCCESS ORCPT=rfc822;a@example.test");
        assertThatThrownBy(() -> commands.applyToRecipientCommand("RCPT TO:<a@example.test>", 1))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("more SMTP recipient commands than expected");
    }

    @Test
    void asciiXtextEscapesSpecialCharacters() {
        assertThat(originalRecipientParameter("\"a b+=\"@example.test", false)).isEqualTo("ORCPT=rfc822;\"a+20b+2B+3D\"@example.test");
        assertThat(originalRecipientParameter("normal@example.test", false)).isEqualTo("ORCPT=rfc822;normal@example.test");
    }

    @Test
    void asciiOrcptUsesAngusXtextForPrintableCharacters() {
        for (char character = ' '; character <= '~'; character++) {
            final String address = "recipient" + character + "@example.test";
            assertThat(originalRecipientParameter(address, false))
                    .isEqualTo("ORCPT=rfc822;" + ManagedAngusTransport.encodeXtext(address));
        }
    }

    @Test
    void unsafeCharactersAreNeverEncodedIntoAutomaticMetadata() {
        for (char character = 0; character < ' '; character++) {
            assertThat(originalRecipientParameter("recipient" + character + "@example.test", false)).isNull();
            assertThat(originalRecipientParameter("müller" + character + "@example.test", true)).isNull();
        }
        for (final char character : new char[]{127, Character.MIN_HIGH_SURROGATE, Character.MAX_HIGH_SURROGATE,
                Character.MIN_LOW_SURROGATE, Character.MAX_LOW_SURROGATE}) {
            assertThat(originalRecipientParameter("recipient" + character + "@example.test", true)).isNull();
        }
    }

    @Test
    void utf8UsesUnitextOnlyWhenTheProviderCanSendUtf8() {
        assertThat(originalRecipientParameter("müller+tag@example.test", false)).isNull();
        assertThat(originalRecipientParameter("müller+tag@example.test", true)).isEqualTo("ORCPT=utf-8;müller\\x{2B}tag@example.test");
        assertThat(originalRecipientParameter("\"é \\=\"@example.test", true))
                .isEqualTo("ORCPT=utf-8;\"é\\x{20}\\x{5C}\\x{3D}\"@example.test");
        assertThat(originalRecipientParameter("\uD800@example.test", true)).isNull();
    }

    @Test
    void unitextPreservesSupplementaryCharactersAndLiteralEscapeText() {
        assertThat(originalRecipientParameter("\uD83D\uDE00+tag@example.test", true))
                .isEqualTo("ORCPT=utf-8;\uD83D\uDE00\\x{2B}tag@example.test");
        assertThat(originalRecipientParameter("\"é\\x{2B}+ =\"@example.test", true))
                .isEqualTo("ORCPT=utf-8;\"é\\x{5C}x{2B}\\x{2B}\\x{20}\\x{3D}\"@example.test");
    }

    @Test
    void parameterBoundaryCountsEncodedOctetsAndNeverTruncates() {
        final String maximumAscii = "a".repeat(487);
        assertThat(originalRecipientParameter(maximumAscii, false)).hasSize(500);
        assertThat(originalRecipientParameter(maximumAscii + "a", false)).isNull();
        assertThat(originalRecipientParameter("+".repeat(162) + "a", false)).hasSize(500);
        assertThat(originalRecipientParameter("+".repeat(163), false)).isNull();
        assertThat(originalRecipientParameter("é".repeat(244), true)).isNotNull();
        assertThat(originalRecipientParameter("é".repeat(245), true)).isNull();
        assertThat(originalRecipientParameter("\uD83D\uDE00".repeat(122), true).getBytes(UTF_8)).hasSize(500);
        assertThat(originalRecipientParameter("\uD83D\uDE00".repeat(123), true)).isNull();
    }

    @Test
    void deliveryEnvelopeDefensivelyCopiesRecipientPolicies() {
        final List<DeliveryRecipient> recipientPolicies = new ArrayList<>(List.of(new DeliveryRecipient("a@example.test", List.of(NEVER))));
        final DeliveryEnvelope envelope = new DeliveryEnvelope(null, null, recipientPolicies);

        recipientPolicies.clear();

        assertThat(envelope.getRecipientOptions()).hasSize(1);
        assertThatThrownBy(() -> envelope.getRecipientOptions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(envelope.hasProviderSpecificOptions()).isTrue();
    }

    @Test
    void recipientPoliciesRequireAdapterOptIn() {
        final DeliveryEnvelope envelope = new DeliveryEnvelope(null, null,
                List.of(new DeliveryRecipient("a@example.test", List.of(NEVER))));

        assertThat(mock(MailTransportAdapter.class, CALLS_REAL_METHODS).supportsDeliveryEnvelope(envelope)).isFalse();
    }

    @Test
    void callerOwnedAngusTransportRejectsRecipientPreferencesBeforeDispatch() throws Exception {
        final PreparedMail mail = preparedMail(new String[]{"a@example.test"},
                List.of(new DeliveryRecipient("a@example.test", List.of(NEVER))));

        assertThatThrownBy(() -> AngusRecipientCommands.prepare(new SMTPTransport(session, null), mail, true))
                .isInstanceOf(MailTransportCompatibilityException.class)
                .hasMessageContaining("managed Angus")
                .hasMessageContaining("can bypass")
                .hasMessageContaining("withSMTPServer(...)")
                .hasMessageContaining("best-effort Email-level settings");
    }

    @Test
    void serverWithoutDsnRejectsRecipientPreferencesBeforeDispatch() throws Exception {
        final PreparedMail mail = preparedMail(new String[]{"a@example.test"},
                List.of(new DeliveryRecipient("a@example.test", List.of(NEVER))));

        assertThatThrownBy(() -> AngusRecipientCommands.prepare(new ManagedAngusTransport(session, null), mail, false))
                .isInstanceOf(MailTransportCompatibilityException.class)
                .hasMessageContaining("does not advertise usable DSN support")
                .hasMessageContaining("without those notification requests")
                .hasMessageContaining("No message was submitted");
    }

    @Test
    void misalignedRecipientAddressesAreRejectedBeforeDispatch() {
        assertThatThrownBy(() -> prepareRecipientCommands(new String[]{"a@example.test"},
                List.of(new DeliveryRecipient("b@example.test", List.of(NEVER)))))
                .isInstanceOf(MailTransportCompatibilityException.class)
                .hasMessageContaining("If your integration constructs PreparedMail")
                .hasMessageContaining("matching addresses in the same order, including duplicates")
                .hasMessageContaining("Otherwise, please report this as a Simple Java Mail bug");
    }

    @Test
    void misalignedRecipientCountsAreRejectedBeforeDispatch() {
        final List<DeliveryRecipient> recipientPolicies = List.of(new DeliveryRecipient("a@example.test", List.of(NEVER)));

        assertThatThrownBy(() -> prepareRecipientCommands(new String[]{"a@example.test", "b@example.test"}, recipientPolicies))
                .isInstanceOf(MailTransportCompatibilityException.class)
                .hasMessageContaining("one DeliveryRecipient per entry in PreparedMail.getRecipients()");
    }

    @Test
    void callerOwnedAngusTransportNeedsNoManagedHookWithoutRecipientPreferences() throws Exception {
        final PreparedMail mail = preparedMail(new String[]{"a@example.test"}, emptyList());

        assertThat(AngusRecipientCommands.prepare(new SMTPTransport(session, null), mail, true)).isNull();
    }

    @Test
    void unexpectedProviderCommandShapeOrCountStopsRatherThanMisapplyingPolicy() throws Exception {
        final AngusRecipientCommands commands = prepareRecipientCommands(new String[]{"a@example.test"}, emptyList());
        assertThatThrownBy(() -> commands.applyToRecipientCommand("RCPT TO:<a@example.test>", 1)).isInstanceOf(MessagingException.class)
                .hasMessageContaining("more SMTP recipient commands than expected")
                .hasMessageContaining("stopped this send")
                .hasMessageContaining("report this provider-integration problem")
                .hasMessageContaining("Simple Java Mail and Angus versions");
        assertThatThrownBy(() -> commands.applyToRecipientCommand("RCPT TO:a@example.test", 0)).isInstanceOf(MessagingException.class)
                .hasMessageContaining("a format Simple Java Mail cannot safely update")
                .hasMessageContaining("This send was stopped")
                .hasMessageContaining("report this provider-integration problem")
                .hasMessageContaining("Simple Java Mail and Angus versions");
    }

    private AngusRecipientCommands prepareRecipientCommands(final String[] recipients,
            final List<DeliveryRecipient> recipientOptions) throws Exception {
        return AngusRecipientCommands.prepare(new ManagedAngusTransport(session, null),
                preparedMail(recipients, recipientOptions), true);
    }

    private PreparedMail preparedMail(final String[] mailboxes, final List<DeliveryRecipient> recipientOptions) throws Exception {
        final Address[] addresses = new Address[mailboxes.length];
        for (int index = 0; index < mailboxes.length; index++) {
            addresses[index] = new InternetAddress(mailboxes[index]);
        }
        return new PreparedMail(new MimeMessage(session), addresses,
                new DeliveryEnvelope(null, null, recipientOptions), ContentRequirement.NORMAL);
    }
}
