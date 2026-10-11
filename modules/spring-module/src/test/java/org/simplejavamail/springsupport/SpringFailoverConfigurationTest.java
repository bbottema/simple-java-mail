package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigDiagnosticGroup;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigPropertyDiagnostic;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class SpringFailoverConfigurationTest {
    @Test
    void namedDefinitionsAndLocksRetainSourcesAndResolveThroughSharedLoader() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("platform", Map.of(
                    "simplejavamail.smtp.host", "primary.example.org",
                    "simplejavamail.locked.failover.mailer", "other",
                    "simplejavamail.failover.primary.recheckinterval", "PT5S",
                    "simplejavamail.mailers.other.smtp.host", "other.example.org",
                    "simplejavamail.mailers.other.smtp.username", "relay-user",
                    "simplejavamail.mailers.other.smtp.password", "secret",
                    "simplejavamail.locked.mailers.other.transportstrategy", "SMTP_TLS")));
            context.register(SimpleJavaMailSpringSupport.class);
            context.refresh();
            final SimpleJavaMail factory = context.getBean(SimpleJavaMail.class);
            final Map<String, SimpleJavaMailConfig> namedMailerConfigurations = factory.getConfig().getProperty(ConfigLoader.Property.NAMED_MAILERS);
            assertThat(namedMailerConfigurations.get("other").getStringProperty(ConfigLoader.Property.SMTP_HOST)).isEqualTo("other.example.org");
            assertThat(namedMailerConfigurations.get("other").getLocks().contains(ConfigLoader.Property.TRANSPORT_STRATEGY)).isTrue();
            assertThat(factory.getConfig().getDiagnostics().getProperties(ConfigDiagnosticGroup.SMTP_CONNECTION))
                    .filteredOn(SpringFailoverConfigurationTest::isFailoverConfigurationDiagnostic)
                    .isNotEmpty()
                    .allSatisfy(entry -> assertThat(entry.getSourceName()).isEqualTo("platform"));
            assertThat(factory.getConfig().getDiagnostics().toString()).contains("<redacted>").doesNotContain("secret");
            assertThatThrownBy(() -> factory.mailerBuilder().clearFailoverMailer().buildMailer()).hasMessageContaining("locked.failover.mailer");
            try (Mailer mailer = factory.mailerBuilder().withConnectionPoolCoreSize(0).buildMailer()) {
                assertThat(mailer.getOperationalConfig().getFailoverPolicy().getPrimaryRecheckInterval()).isEqualTo(Duration.ofSeconds(5));
            }
        }
    }

    private static boolean isFailoverConfigurationDiagnostic(final ConfigPropertyDiagnostic diagnostic) {
        final String propertyName = diagnostic.getPropertyName();
        return propertyName.contains(".mailers.")
                || propertyName.contains(".failover.")
                || propertyName.equals("simplejavamail.smtp.host");
    }
}
