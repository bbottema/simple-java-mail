package org.simplejavamail.mailer;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.MailRehearsal;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.api.email.SignatureStatus.VERIFIED;
import static org.simplejavamail.api.mailer.MailFailureKind.PREPARATION;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class MailerRehearsalLifetimeTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @EnumSource(Preparation.class)
    void preparationRemainsAvailableAfterClosingWithTheOriginalDefaults(final Preparation preparation) throws Exception {
        final Mailer mailer = MAIL.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25)
                .withEmailDefaults(defaults("original@example.org")).buildMailer();
        final Email draft = draft();
        mailer.close();

        final MailRehearsal preview = preparation.prepare(mailer, draft);
        if (preview != null) {
            assertThat(preview.getEffectiveEmail().getFromRecipient().getAddress()).isEqualTo("original@example.org");
            assertThat(preview.isFullRehearsal()).isEqualTo(preparation.full);
            final byte[] bytes = preview.getEmlBytes();
            bytes[0] ^= 1;
            assertThat(preview.getEmlBytes()).isNotEqualTo(bytes);
        }
        assertThat(draft.getFromRecipient()).isNull();
        assertThat(draft.getId()).isNull();
        assertThat(mailer.closeAsync()).isCompleted();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reusedSessionCannotReplaceTheOriginalMailersSizePolicy(final boolean originalHasLimit) throws Exception {
        final Session shared = session(new Properties());
        final Mailer original = MAIL.mailerBuilder(shared).withEmailDefaults(defaults("original@example.org"))
                .withValidationConfig(validation(originalHasLimit)).buildMailer();
        original.close();

        try (Mailer replacement = MAIL.mailerBuilder(shared).withEmailDefaults(defaults("replacement@example.org"))
                .withValidationConfig(validation(!originalHasLimit)).buildMailer()) {
            assertThat(original.getSession()).isSameAs(shared);
            assertThat(replacement.getSession()).isSameAs(shared);
            if (originalHasLimit) {
                assertThatThrownBy(() -> original.rehearse(draft())).isInstanceOf(EmailTooBigException.class);
                assertThatThrownBy(() -> original.validate(draft())).isInstanceOf(EmailTooBigException.class);
            } else {
                assertThat(original.rehearse(draft()).getEffectiveEmail().getFromRecipient().getAddress())
                        .isEqualTo("original@example.org");
                original.validate(draft());
            }
            assertThat(original.rehearse(draft(), false).getEffectiveEmail().getFromRecipient().getAddress())
                    .isEqualTo("original@example.org");
            original.validate(draft(), false);
        }
    }

    @ParameterizedTest
    @EnumSource(Preparation.class)
    void exactPreparationAfterCloseRetainsTheAuthoritativeBytes(final Preparation preparation) throws Exception {
        final byte[] bytes = "Subject: exact\r\nX-Folded: first\r\n second\r\n\r\nbody\r\n".getBytes(StandardCharsets.US_ASCII);
        final Email exact = MAIL.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("recipient@example.org").buildEmail();
        final Mailer mailer = MAIL.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25)
                .withValidationConfig(ValidationConfig.builder().withMaximumEmailSize(bytes.length).build()).buildMailer();
        mailer.close();
        final MailRehearsal preview = preparation.prepare(mailer, exact);
        if (preview != null) {
            assertThat(preview.getEmlBytes()).containsExactly(bytes);
            assertThat(preview.getEffectiveEmail()).isSameAs(exact);
            assertThat(preview.getEnvelopeRecipients()).containsExactly("recipient@example.org");
        }
    }

    @Test
    void openPreparationObservesLiveSessionMetadataAndCloseFreezesItsLastView() throws Exception {
        final Properties inherited = new Properties();
        inherited.setProperty("mail.from", "builder@initial.example");
        final Properties properties = new Properties(inherited);
        final Session shared = session(properties);
        final AtomicInteger authentication = new AtomicInteger();
        final Session callerSession = Session.getInstance(shared.getProperties(), new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                authentication.incrementAndGet();
                return new PasswordAuthentication("caller", "unused");
            }
        });
        final Mailer original = MAIL.mailerBuilder(callerSession).withEmailDefaults(defaults("original@example.org")).buildMailer();
        try {
            assertThat(original.rehearse(draft()).getEmailId()).endsWith("@initial.example>");
            inherited.setProperty("mail.from", "builder@live.example");
            assertThat(original.rehearse(draft()).getEmailId()).endsWith("@live.example>");
            original.close();
            inherited.setProperty("mail.from", "builder@reused.example");
            try (Mailer replacement = MAIL.mailerBuilder(callerSession).withEmailDefaults(defaults("replacement@example.org")).buildMailer()) {
                assertThat(replacement.rehearse(draft()).getEmailId()).endsWith("@reused.example>");
                assertThat(original.rehearse(draft()).getEmailId()).endsWith("@live.example>");
                assertThat(original.getSession()).isSameAs(callerSession);
                assertThat(authentication).hasValue(0);
            }
        } finally {
            original.close();
        }
    }

    @Test
    void completedPreparationStillAppliesMessageLocksAndNativeSecurity() throws Exception {
        final SimpleJavaMail governed = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.defaults.bcc.address", "archive@example.org")).load());
        final SmimeSigningConfig signing = SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build();
        final Email email = governed.emailBuilder().startingBlank().from("sender@example.org").to("recipient@example.org")
                .withPlainText("Protected after close").signWithSmime(signing).buildEmail();
        final Mailer mailer = governed.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25).buildMailer();
        mailer.close();

        final MailRehearsal preview = mailer.rehearse(email);
        assertThat(preview.getEnvelopeRecipients()).contains("recipient@example.org", "archive@example.org");
        final Email inspected = EmailConverter.emlToEmail(new ByteArrayInputStream(preview.getEmlBytes()));
        assertThat(inspected.getPlainText()).isEqualTo("Protected after close");
        assertThat(inspected.getInspection().getPlainText().getSignatureStatus()).isEqualTo(VERIFIED);
        assertThat(email.getRecipients()).extracting(recipient -> recipient.getAddress()).containsExactly("recipient@example.org");
    }

    @Test
    void privatePreparationDoesNotAddSessionDebugLogging() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.debug", "true");
        final Session caller = session(properties);
        final ByteArrayOutputStream debug = new ByteArrayOutputStream();
        caller.setDebugOut(new PrintStream(debug));
        final Mailer mailer = MAIL.mailerBuilder(caller).withEmailDefaults(defaults("sender@example.org")).buildMailer();
        final PrintStream originalOut = System.out;
        final ByteArrayOutputStream bootstrapLogging = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(bootstrapLogging));
            debug.reset();
            mailer.rehearse(draft());
            assertThat(debug.toByteArray()).isEmpty();
            mailer.close();
            mailer.rehearse(draft());
            assertThat(debug.toByteArray()).isEmpty();
            assertThat(bootstrapLogging.toString(StandardCharsets.UTF_8)).doesNotContain("DEBUG: Jakarta Mail", "DEBUG: getProvider", "DEBUG: setDebug");
        } finally {
            System.setOut(originalOut);
            mailer.close();
        }
    }

    @Test
    void aNonStringDirectEntryRetainsItsInheritedProviderVisibleString() throws Exception {
        final Properties inherited = new Properties();
        inherited.setProperty("mail.from", "builder@inherited.example");
        final Properties properties = new Properties(inherited);
        properties.put("mail.from", 42);
        final Mailer mailer = MAIL.mailerBuilder(session(properties)).withEmailDefaults(defaults("sender@example.org")).buildMailer();
        assertThat(mailer.rehearse(draft()).getEmailId()).endsWith("@inherited.example>");
        mailer.close();
        inherited.setProperty("mail.from", "builder@changed.example");
        assertThat(mailer.rehearse(draft()).getEmailId()).endsWith("@inherited.example>");
        assertThat(properties.get("mail.from")).isEqualTo(42);
    }

    @Test
    void closingOnlyCapturesMetadataWithoutReadingDefaultContentOrSigningKeys() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final jakarta.activation.DataSource source = new jakarta.activation.DataSource() {
            @Override public InputStream getInputStream() { reads.incrementAndGet(); return new ByteArrayInputStream(new byte[] {1, 2, 3}); }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "document.bin"; }
        };
        final Email defaults = MAIL.emailBuilder().copying(defaults("sender@example.org")).withAttachment("document.bin", source)
                .signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData("not-an-interpreted-key")
                        .dkimSigningDomain("example.org").dkimSelector("test").build()).buildEmail();
        final Mailer mailer = MAIL.mailerBuilder().withSMTPServer("unreachable.example.invalid", 25).withEmailDefaults(defaults).buildMailer();
        assertThat(reads).hasValue(0);
        mailer.close();
        assertThat(reads).hasValue(0);
        assertThat(mailer.rehearse(draft(), false).getEmlBytes()).isNotEmpty();
        assertThat(reads.get()).isPositive();
    }

    @Test
    void metadataCaptureFailureDoesNotStrandShutdownOrSwitchToTheNextOwner() throws Exception {
        final FailingProperties properties = new FailingProperties();
        final Session shared = session(properties);
        final Mailer original = MAIL.mailerBuilder(shared).withEmailDefaults(defaults("original@example.org")).buildMailer();
        properties.failure = new IllegalStateException("Metadata snapshot unavailable");
        original.closeAsync().get(2, SECONDS);
        properties.failure = null;
        try (Mailer replacement = MAIL.mailerBuilder(shared).withEmailDefaults(defaults("replacement@example.org")).buildMailer()) {
            assertThatThrownBy(() -> original.rehearse(draft())).isInstanceOf(MailException.class)
                    .satisfies(failure -> assertThat(((MailException) failure).getKind()).isEqualTo(PREPARATION))
                    .hasCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("Metadata snapshot unavailable");
            assertThat(replacement.rehearse(draft()).getEffectiveEmail().getFromRecipient().getAddress()).isEqualTo("replacement@example.org");
        }
    }

    @Test
    void fatalMetadataCaptureErrorStillInitiatesOwnedCleanup() throws Exception {
        final FailingProperties properties = new FailingProperties();
        final Session shared = session(properties);
        final Mailer original = MAIL.mailerBuilder(shared).withEmailDefaults(defaults("original@example.org")).buildMailer();
        final AssertionError fatal = new AssertionError("Metadata capture error");
        properties.failure = fatal;
        assertThat(catchThrowable(original::closeAsync)).isSameAs(fatal);
        properties.failure = null;
        original.closeAsync().get(2, SECONDS);
        try (Mailer replacement = MAIL.mailerBuilder(shared).buildMailer()) {
            assertThat(catchThrowable(() -> original.rehearse(draft()))).isSameAs(fatal);
        }
    }

    @Test
    void aShutdownInitiationFailureDoesNotReplaceTheFatalCaptureError() throws Exception {
        final FailingProperties properties = new FailingProperties();
        final Session shared = session(properties);
        final Mailer original = MAIL.mailerBuilder(shared).withEmailDefaults(defaults("original@example.org")).buildMailer();
        final AssertionError fatal = new AssertionError("Metadata capture error");
        final IllegalStateException initiation = new IllegalStateException("Cleanup dispatch unavailable");
        properties.failure = fatal;
        try (MockedStatic<CompletableFuture> futures = Mockito.mockStatic(CompletableFuture.class, Mockito.CALLS_REAL_METHODS)) {
            futures.when(() -> CompletableFuture.runAsync(Mockito.any(Runnable.class), Mockito.any(Executor.class))).thenThrow(initiation);
            assertThat(catchThrowable(original::closeAsync)).isSameAs(fatal);
            assertThat(fatal.getSuppressed()).containsExactly(initiation);
        } finally {
            properties.failure = null;
            original.closeAsync().get(2, SECONDS);
        }
        try (Mailer replacement = MAIL.mailerBuilder(shared).buildMailer()) {
            assertThat(catchThrowable(() -> original.rehearse(draft()))).isSameAs(fatal);
        }
    }

    private static ValidationConfig validation(final boolean constrained) {
        return constrained ? ValidationConfig.builder().withMaximumEmailSize(1).build() : ValidationConfig.builder().build();
    }

    static Session session(final Properties properties) {
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "unreachable.example.invalid");
        properties.setProperty("mail.smtp.port", "25");
        return Session.getInstance(properties);
    }

    static Email defaults(final String sender) {
        return MAIL.emailBuilder().startingBlank().from(sender).buildEmail();
    }

    static Email draft() {
        return MAIL.emailBuilder().startingBlank().to("recipient@example.org").withSubject("Rehearsal lifetime").withPlainText("Body").buildEmail();
    }

    private enum Preparation {
        FULL_REHEARSAL(true), BASE_REHEARSAL(false), FULL_VALIDATION(true), BASE_VALIDATION(false);
        private final boolean full;
        Preparation(final boolean full) { this.full = full; }
        MailRehearsal prepare(final Mailer mailer, final Email email) {
            switch (this) {
                case FULL_REHEARSAL: return mailer.rehearse(email);
                case BASE_REHEARSAL: return mailer.rehearse(email, false);
                case FULL_VALIDATION: mailer.validate(email); return null;
                default: mailer.validate(email, false); return null;
            }
        }
    }

    private static final class FailingProperties extends Properties {
        private Throwable failure;
        @Override public synchronized Object clone() {
            if (failure instanceof Error) { throw (Error) failure; }
            if (failure != null) { throw (RuntimeException) failure; }
            return super.clone();
        }
    }
}
