package org.simplejavamail.springsupport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringRecipientRejectionHandlingTest {

    @ParameterizedTest
    @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void environmentLoadsOrdinaryAndLockedChoicesThroughTheSharedLoader(final boolean choice, final boolean locked) throws Exception {
        final String key = "simplejavamail." + (locked ? "locked." : "") + "defaults.sendtoacceptedrecipients";
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("company-mail", Map.of(key, Boolean.toString(choice))));
            context.register(SimpleJavaMailSpringSupport.class);
            context.refresh();
            final SimpleJavaMail factory = context.getBean(SimpleJavaMail.class);
            final Email local = factory.emailBuilder().startingBlank().buildEmail();
            assertThat(local.getSendingToAcceptedRecipients()).isNull();
            try (Mailer mailer = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
                assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(local).getSendingToAcceptedRecipients()).isEqualTo(choice);
                final Email conflicting = factory.emailBuilder().startingBlank().withSendingToAcceptedRecipients(!choice).buildEmail();
                if (locked) {
                    assertThatThrownBy(() -> mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(conflicting)).hasMessageContaining(key);
                } else {
                    assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(conflicting).getSendingToAcceptedRecipients())
                            .isEqualTo(!choice);
                }
                assertThat(factory.getConfig().getDiagnostics().getProperties(ConfigDiagnosticGroup.EMAIL_DEFAULTS)).anySatisfy(entry -> {
                    assertThat(entry.getPropertyName()).isEqualTo(key);
                    assertThat(entry.getDisplayValue()).isEqualTo(Boolean.toString(choice));
                    assertThat(entry.getSourceName()).isEqualTo("company-mail");
                    assertThat(entry.isRedacted()).isFalse();
                });
            }
        }
    }
}
