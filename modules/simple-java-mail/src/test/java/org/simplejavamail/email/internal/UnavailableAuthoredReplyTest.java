package org.simplejavamail.email.internal;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ProtectionFamily;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.OpenPgpModule;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.internal.protection.ProtectionLayerResult;
import org.simplejavamail.mailer.MailerHelper;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class UnavailableAuthoredReplyTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @EnumSource(ProtectionFamily.class)
    void aRecognizedButUnreadableAuthoredViewDoesNotBorrowCarrierDestinationsOrAncestry(final ProtectionFamily family) throws Exception {
        final MimeMessage carrier = carrierWithReadableFallback(family);
        // Inject only the reader failure/recognized shape. Both source parts are ordinary existing fixtures, with no malformed payload.
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
            injectUnavailableAuthoredRead(family, modules);
            final Email received = MAIL.converter().fromMimeMessage(carrier).toEmail();
            assertThat(InternalEmail.requireInternalEmail(received).isAuthoredHeaderViewUnavailable()).isTrue();
            assertThat(received.getPlainText()).contains("Readable native alternative");
            assertThat(received.getSubject()).isEqualTo("Available carrier subject");
            assertThat(received.getFromRecipient()).isNull();
            assertThat(received.getRecipients()).isEmpty();
            assertThat(received.getId()).isNull();
            assertThat(received.getSentDate()).isNull();
            assertThat(received.getHeaders().get("From")).containsExactly("carrier@example.org");
            assertThat(received.getHeaders().get("Message-ID")).containsExactly("<carrier-id@example.org>");
            assertThat(received.getHeaders().get("References")).containsExactly("<carrier-earlier@example.org>");

            final Email copy = MAIL.emailBuilder().copying(received).buildEmail();
            final Email completed = new EmailGovernanceImpl(null, null, null, null).produceEmailApplyingDefaultsAndOverrides(received);
            for (Email source : List.of(received, copy, completed, snapshot(received))) {
                assertUnresolved(MAIL.emailBuilder().replyingTo(source).buildEmail());
                assertUnresolved(MAIL.emailBuilder().replyingToAll(source).buildEmail());
            }
            assertUnresolved(MAIL.emailBuilder().replyingTo(carrier).buildEmail());
            assertUnresolved(MAIL.emailBuilder().replyingToAll(carrier).buildEmail());

            final Email explicit = MAIL.emailBuilder().replyingToAll(received).from("writer@example.org").to("chosen@example.org").buildEmail();
            MailerHelper.validate(explicit);
            final MimeMessage rendered = MAIL.converter().fromEmail(explicit).toMimeMessage();
            assertThat(rendered.getHeader("To")).containsExactly("chosen@example.org");
            assertThat(rendered.getHeader("References")).isNull();
            final MimeMessage interpreted = MAIL.emailBuilder().forwarding(received).buildEmail().getEmailToForward();
            assertThat(interpreted.getHeader("References")).isNull();
            assertThat(interpreted.getHeader("In-Reply-To")).isNull();
            final Email deliberateThread = MAIL.emailBuilder().copying(received)
                    .withHeader("References", "<caller-chosen@example.org>", true).buildEmail();
            assertThat(MAIL.converter().fromEmail(deliberateThread).toMimeMessage().getHeader("References"))
                    .containsExactly("<caller-chosen@example.org>");
        }
    }

    @ParameterizedTest
    @EnumSource(ProtectionFamily.class)
    void opaqueInputWithoutPositiveAuthoredViewEvidenceDoesNotSuppressOrdinaryReplyFallback(final ProtectionFamily family) throws Exception {
        final MimeMessage carrier = new MimeMessage(session(), new ByteArrayInputStream(fixture(family, false)));
        carrierHeaders(carrier);
        final Email opaque = MAIL.converter().fromMimeMessage(carrier).toEmail();
        assertThat(InternalEmail.requireInternalEmail(opaque).isAuthoredHeaderViewUnavailable()).isFalse();
        final Email reply = MAIL.emailBuilder().replyingTo(opaque).buildEmail();
        assertThat(reply.getRecipients()).extracting(Recipient::getAddress).containsExactly("carrier-reply@example.org");
        assertThat(reply.getHeaders().get("In-Reply-To")).containsExactly("<carrier-id@example.org>");
    }

    private static void assertUnresolved(final Email reply) {
        assertThat(reply.getRecipients()).isEmpty();
        assertThat(reply.getHeaders()).doesNotContainKeys("In-Reply-To", "References");
        assertThat(reply.getPlainText()).contains("Readable native alternative");
        assertThat(reply.getSubject()).isEqualTo("Re: Available carrier subject");
    }

    private static void injectUnavailableAuthoredRead(final ProtectionFamily family, final MockedStatic<ModuleLoader> modules) {
        if (family == ProtectionFamily.SMIME) {
            final SMIMEModule provider = mock(SMIMEModule.class);
            when(provider.openIncomingLayer(any(Session.class), any(MimeMessage.class), isNull()))
                    .thenAnswer(call -> unavailable(call.getArgument(1)));
            modules.when(ModuleLoader::smimeModuleAvailable).thenReturn(true);
            modules.when(ModuleLoader::loadSmimeModule).thenReturn(provider);
        } else {
            final OpenPgpModule provider = mock(OpenPgpModule.class);
            when(provider.openIncomingLayer(any(Session.class), any(MimeMessage.class), isNull()))
                    .thenAnswer(call -> unavailable(call.getArgument(1)));
            modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(true);
            modules.when(ModuleLoader::loadOpenPgpModule).thenReturn(provider);
        }
    }

    private static ProtectionLayerResult unavailable(final MimeMessage outer) {
        final IOException failure = new IOException("Fixture authored message reader could not acquire the selected content");
        return ProtectionLayerResult.builder().recognized(true).authoredHeaders(true).opened(false).effectiveMimeMessage(outer)
                .inspectionComplete(false).inputReadFailure(failure).processingFailure(failure).build();
    }

    private static MimeMessage carrierWithReadableFallback(final ProtectionFamily family) throws Exception {
        final MimeMultipart alternatives = new MimeMultipart("alternative");
        alternatives.addBodyPart(new MimeBodyPart(new ByteArrayInputStream(fixture(family, true))));
        final MimeBodyPart readable = new MimeBodyPart();
        readable.setText("Readable native alternative", "UTF-8");
        alternatives.addBodyPart(readable);
        final MimeMessage carrier = new MimeMessage(session());
        carrier.setContent(alternatives);
        carrierHeaders(carrier);
        return carrier;
    }

    private static void carrierHeaders(final MimeMessage carrier) throws Exception {
        carrier.setFrom(new InternetAddress("carrier@example.org"));
        carrier.setReplyTo(new Address[] {new InternetAddress("carrier-reply@example.org")});
        carrier.setRecipients(Message.RecipientType.TO, "carrier-reader@example.org");
        carrier.setRecipients(Message.RecipientType.CC, "carrier-copy@example.org");
        carrier.setSubject("Available carrier subject");
        carrier.setHeader("References", "<carrier-earlier@example.org>");
        carrier.setHeader("In-Reply-To", "<carrier-ancestor@example.org>");
        carrier.saveChanges();
        carrier.setHeader("Message-ID", "<carrier-id@example.org>");
    }

    private static byte[] fixture(final ProtectionFamily family, final boolean signed) throws Exception {
        final String name = family == ProtectionFamily.SMIME
                ? "/pkcs12/test messages/S_MIME test message " + (signed ? "signed" : "encrypted") + ".eml"
                : "/openpgpjs/" + (signed ? "signed-mixed" : "encrypted-mixed") + ".eml";
        try (InputStream input = UnavailableAuthoredReplyTest.class.getResourceAsStream(name)) { return input.readAllBytes(); }
    }

    private static Session session() {
        final Properties properties = new Properties();
        properties.setProperty("mail.host", "localhost");
        return Session.getInstance(properties);
    }

    private static Email snapshot(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) input.readObject(); }
    }
}
