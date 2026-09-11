package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.config.ConfigLoader.Property.DEFAULT_MAIL_SEND_TIMEOUT;

class MailSendTimeoutConfigTest {

    @Test
    void resolvesTypedTimeoutAndProvenanceWhileBuilderOverridesRemainSeparate() throws Exception {
        final SimpleJavaMailConfig config = ConfigLoader.builder()
                .withMap("file", Map.of(DEFAULT_MAIL_SEND_TIMEOUT.key(), "PT1M"))
                .withMap("application", Map.of(DEFAULT_MAIL_SEND_TIMEOUT.key(), Duration.ofMillis(12500)))
                .load();
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(config);
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withMailSendTimeout(Duration.ofSeconds(2)).buildMailer()) {
            assertThat(config.<Duration>getProperty(DEFAULT_MAIL_SEND_TIMEOUT)).isEqualTo(Duration.ofMillis(12500));
            assertThat(config.getDiagnostics().getProperties(ConfigDiagnosticGroup.EXECUTION_AND_POOLING))
                    .singleElement().satisfies(property -> {
                        assertThat(property.getDisplayValue()).isEqualTo("PT12.5S");
                        assertThat(property.getSourceName()).isEqualTo("application");
                        assertThat(property.isRedacted()).isFalse();
                    });
            assertThat(mailer.getOperationalConfig().getMailSendTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(mail.mailerBuilder().resetMailSendTimeout().getMailSendTimeout()).isNull();
        }
    }

    @Test
    void rejectsUnparseableNonPositiveAndOverflowingBudgets() {
        assertThatThrownBy(() -> ConfigLoader.builder().withMap(Map.of(DEFAULT_MAIL_SEND_TIMEOUT.key(), "30 seconds")).load())
                .isInstanceOf(IllegalArgumentException.class);
        for (String invalid : new String[]{"PT0S", "PT-1S", "P999999D"}) {
            final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(DEFAULT_MAIL_SEND_TIMEOUT.key(), invalid)).load();
            assertThatThrownBy(() -> SimpleJavaMail.withConfig(config).mailerBuilder()).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
