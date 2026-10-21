package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import com.sanctionco.jmail.JMail;
import jakarta.activation.DataSource;
import jakarta.mail.internet.MimeMessage;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.EmailTooBigException;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.DKIMModule;
import org.simplejavamail.mailer.MailCompletenessException;
import org.simplejavamail.mailer.MailInvalidAddressException;
import org.simplejavamail.mailer.MailSuspiciousCRLFValueException;
import org.simplejavamail.mailer.MailerHelper;
import testutil.ConfigLoaderTestHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.simplejavamail.mailer.internal.LockedEmailConfigurationTest.locked;

class ClientValidationWarningsTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void suppliedChoicesKeepAbsenceAndResetOnlyTheWarningChoice() {
        final EmailValidator validator = JMail.strictValidator();
        final ValidationConfig.Builder builder = ValidationConfig.builder().withEmailValidator(validator).withMaximumEmailSize(1234);
        assertThat(builder.getClientValidationWarningsOnly()).isNull();
        assertThat(builder.build().getClientValidationWarningsOnly()).isNull();
        final ValidationConfig warnings = builder.withClientValidationWarningsOnly(true).build();
        final ValidationConfig blocking = warnings.toBuilder().resetClientValidationWarningsOnly().build();
        final ValidationConfig inherited = blocking.toBuilder().useConfiguredClientValidationWarningsOnly().build();
        assertThat(warnings.getClientValidationWarningsOnly()).isTrue();
        assertThat(blocking.getClientValidationWarningsOnly()).isFalse();
        assertThat(inherited.getClientValidationWarningsOnly()).isNull();
        for (ValidationConfig choices : List.of(warnings, blocking, inherited)) {
            assertThat(choices.getEmailValidator()).isSameAs(validator);
            assertThat(choices.getMaximumEmailSize()).isEqualTo(1234);
        }
    }

    @Test
    void propertiesPrecedeFallbackButExplicitFalseAndResetRemainBlocking() throws Exception {
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.disable.all.clientvalidation", true)).load());
        try (Mailer fallback = mail.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().build()).buildMailer();
             Mailer inherited = configured.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().withMaximumEmailSize(1234).build()).buildMailer();
             Mailer explicit = configured.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly(false).build()).buildMailer();
             Mailer reset = configured.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().resetClientValidationWarningsOnly().build()).buildMailer();
             Mailer restored = configured.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly(false)
                     .useConfiguredClientValidationWarningsOnly().build()).buildMailer();
             Mailer replaced = configured.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly(false).build())
                     .withValidationConfig(ValidationConfig.builder().withMaximumEmailSize(1234).build()).buildMailer()) {
            assertThat(fallback.getOperationalConfig().isClientValidationWarningsOnly()).isFalse();
            assertThat(inherited.getOperationalConfig().isClientValidationWarningsOnly()).isTrue();
            assertThat(explicit.getOperationalConfig().isClientValidationWarningsOnly()).isFalse();
            assertThat(reset.getOperationalConfig().isClientValidationWarningsOnly()).isFalse();
            assertThat(restored.getOperationalConfig().isClientValidationWarningsOnly()).isTrue();
            assertThat(replaced.getOperationalConfig().isClientValidationWarningsOnly()).isTrue();
        }
    }

    @Test
    void lockedPropertiesConstrainExplicitChoicesAndReset() throws Exception {
        final SimpleJavaMail blocking = locked(Map.of("disable.all.clientvalidation", "false"));
        assertThatThrownBy(() -> blocking.mailerBuilder().withSMTPServer("unused.example.invalid", 25)
                .withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly(true).build()).buildMailer())
                .hasMessageContaining("locked.disable.all.clientvalidation");
        final SimpleJavaMail warning = locked(Map.of("disable.all.clientvalidation", "true"));
        assertThatThrownBy(() -> warning.mailerBuilder().withSMTPServer("unused.example.invalid", 25)
                .withValidationConfig(ValidationConfig.builder().resetClientValidationWarningsOnly().build()).buildMailer())
                .hasMessageContaining("locked.disable.all.clientvalidation");
        try (Mailer matching = blocking.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().resetClientValidationWarningsOnly().build()).buildMailer();
             Mailer inherited = warning.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly(false)
                     .useConfiguredClientValidationWarningsOnly().build()).buildMailer()) {
            assertThat(matching.getOperationalConfig().isClientValidationWarningsOnly()).isFalse();
            assertThat(inherited.getOperationalConfig().isClientValidationWarningsOnly()).isTrue();
        }
    }

    @Test
    void warningModeStillRunsCompletenessAddressAndCrlfChecks() throws Exception {
        final Email missingRecipients = mail.emailBuilder().startingBlank().from("sender@example.test").withPlainText("body").buildEmail();
        final Email invalidAddress = mail.emailBuilder().startingBlank().from(new Recipient(null, "not-an-email-address", null, null))
                .to("receiver@example.test").withPlainText("body").buildEmail();
        final Email suspiciousSubject = mail.emailBuilder().copying(validEmail()).withSubject("subject\r\nX-Injected: yes").buildEmail();
        try (Mailer warning = mail.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly(true).build()).buildMailer();
             Mailer blocking = mail.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly(false).build()).buildMailer();
             WarningCapture warnings = new WarningCapture()) {
            warning.validate(missingRecipients, false);
            warning.validate(invalidAddress, false);
            warning.validate(suspiciousSubject, false);
            assertThat(warnings.messages).anyMatch(message -> message.contains("missing field"));
            assertThat(warnings.messages).anyMatch(message -> message.contains("invalid address"));
            assertThat(warnings.messages).anyMatch(message -> message.contains("suspected CRLF injection"));
            assertThatThrownBy(() -> blocking.validate(missingRecipients, false)).isInstanceOf(MailCompletenessException.class);
            assertThatThrownBy(() -> blocking.validate(invalidAddress, false)).isInstanceOf(MailInvalidAddressException.class);
            assertThatThrownBy(() -> blocking.validate(suspiciousSubject, false)).isInstanceOf(MailSuspiciousCRLFValueException.class);
        }
    }

    @Test
    void warningModeDoesNotSuppressSizeSecurityOrContentPreparationFailures() throws Exception {
        final Email email = validEmail();
        try (Mailer limited = mail.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder()
                .withClientValidationWarningsOnly(true).withMaximumEmailSize(1).build()).buildMailer()) {
            assertThatThrownBy(() -> limited.validate(email)).isInstanceOf(EmailTooBigException.class);
        }
        try (Mailer warning = mail.mailerBuilder().withSMTPServer("unused.example.invalid", 25).withValidationConfig(ValidationConfig.builder().withClientValidationWarningsOnly(true).build()).buildMailer()) {
            final Email protectedEmail = mail.emailBuilder().copying(email).signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData("test-key")
                    .dkimSigningDomain("example.test").dkimSelector("test").build()).buildEmail();
            final DKIMModule dkim = mock(DKIMModule.class);
            when(dkim.signMessageWithDKIM(any(Email.class), any(MimeMessage.class), any(DkimConfig.class), any(Recipient.class)))
                    .thenThrow(new IllegalStateException("deliberate signing failure"));
            try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
                modules.when(ModuleLoader::loadDKIMModule).thenReturn(dkim);
                assertThatThrownBy(() -> warning.validate(protectedEmail)).hasStackTraceContaining("deliberate signing failure");
            }
            final Email unreadable = mail.emailBuilder().copying(email).withAttachment("report.txt", new DataSource() {
                @Override public InputStream getInputStream() throws IOException { throw new IOException("deliberate content failure"); }
                @Override public OutputStream getOutputStream() throws IOException { throw new IOException("read-only"); }
                @Override public String getContentType() { return "text/plain"; }
                @Override public String getName() { return "report.txt"; }
            }).buildEmail();
            assertThatThrownBy(() -> warning.validate(unreadable)).hasStackTraceContaining("deliberate content failure");
        }
    }

    private Email validEmail() {
        return mail.emailBuilder().startingBlank().from("sender@example.test").to("receiver@example.test").withPlainText("body").buildEmail();
    }

    private static final class WarningCapture extends AbstractAppender implements AutoCloseable {
        private final Logger logger = (Logger) LogManager.getLogger(MailerHelper.class);
        private final long threadId = Thread.currentThread().getId();
        private final List<String> messages = new ArrayList<>();

        private WarningCapture() {
            super("validation-warnings-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY);
            start();
            logger.addAppender(this);
        }
        @Override public void append(final LogEvent event) {
            if (event.getLevel() == Level.WARN && event.getThreadId() == threadId) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }
        @Override public void close() { logger.removeAppender(this); stop(); }
    }
}
