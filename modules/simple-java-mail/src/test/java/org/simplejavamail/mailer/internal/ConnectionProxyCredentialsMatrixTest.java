package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProxyCredentialsMatrixTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // Values: 0 absent/inherit, 1 first, 2 second; local 3 removes and local 4 supplies empty text.
            // rule | protocol | supplied | policy | configured user/password | Java user/password | expected user/password | rejects
            "anonymous raw endpoint | SMTP | false | REJECT_CONFLICTS | 0 | 0 | 0 | 0 | 0 | 0 | false",
            "captured pair combines with Session route | SMTPS | true | REJECT_CONFLICTS | 1 | 1 | 0 | 0 | 1 | 1 | false",
            "Java pair combines with raw endpoint | SMTP | false | REJECT_CONFLICTS | 0 | 0 | 1 | 1 | 1 | 1 | false",
            "equivalent pair accepted | SMTPS | true | REJECT_CONFLICTS | 1 | 1 | 1 | 1 | 1 | 1 | false",
            "username alone is incomplete | SMTP | false | ALLOW_EXPLICIT_OVERRIDES | 1 | 0 | 0 | 0 | 0 | 0 | true",
            "password alone is incomplete | SMTPS | true | REJECT_CONFLICTS | 0 | 1 | 0 | 0 | 0 | 0 | true",
            "Java completes captured password | SMTP | false | REJECT_CONFLICTS | 0 | 1 | 1 | 0 | 1 | 1 | false",
            "Java completes captured username | SMTPS | true | REJECT_CONFLICTS | 1 | 0 | 0 | 1 | 1 | 1 | false",
            "strict pair replacement rejected | SMTP | false | REJECT_CONFLICTS | 1 | 1 | 2 | 2 | 0 | 0 | true",
            "deliberate pair replacement | SMTPS | true | ALLOW_EXPLICIT_OVERRIDES | 1 | 1 | 2 | 2 | 2 | 2 | false",
            "partial override retains password | SMTP | false | ALLOW_EXPLICIT_OVERRIDES | 1 | 1 | 2 | 0 | 2 | 1 | false",
            "strict pair removal rejected | SMTPS | true | REJECT_CONFLICTS | 1 | 1 | 3 | 3 | 0 | 0 | true",
            "deliberate pair removal becomes anonymous | SMTP | false | ALLOW_EXPLICIT_OVERRIDES | 1 | 1 | 3 | 3 | 0 | 0 | false",
            "one-sided removal remains incomplete | SMTPS | true | ALLOW_EXPLICIT_OVERRIDES | 1 | 1 | 3 | 0 | 0 | 0 | true",
            "local-only removal has no import conflict | SMTP | false | REJECT_CONFLICTS | 0 | 0 | 3 | 3 | 0 | 0 | false",
            "empty credentials are invalid | SMTPS | true | ALLOW_EXPLICIT_OVERRIDES | 0 | 0 | 4 | 1 | 0 | 0 | true"
    })
    void credentialRuleCategories(final String rule, final TransportStrategy strategy, final boolean supplied,
            final ConnectionConflictPolicy policy, final int configuredUser, final int configuredPassword,
            final int localUser, final int localPassword, final int expectedUser, final int expectedPassword, final boolean rejects) {
        final int suppliedUser = localUser == 3 ? 0 : localUser;
        final int suppliedPassword = localPassword == 3 ? 0 : localPassword;
        final Map<String, Object> properties = new LinkedHashMap<>();
        if (configuredUser != 0) { properties.put("simplejavamail.proxy.username", value("user", configuredUser)); }
        if (configuredPassword != 0) { properties.put("simplejavamail.proxy.password", value("test-password", configuredPassword)); }
        final var factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(properties).load());
        final Properties endpoint = new Properties();
        endpoint.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        endpoint.setProperty(strategy.propertyNameSocksHost(), "proxy.example.org");
        endpoint.setProperty(strategy.propertyNameSocksPort(), "1081");
        final Properties before = (Properties) endpoint.clone();
        final Session session = supplied ? Session.getInstance(endpoint) : null;
        final MailerGenericBuilder<?> builder = (supplied ? factory.mailerBuilder(session)
                : factory.mailerBuilder().withSMTPServerHost("relay.example.org").withTransportStrategy(strategy).withProperties(endpoint))
                .withConnectionConflictPolicy(policy);
        final SocksProxyConfig.Builder choices = SocksProxyConfig.builder();
        if (localUser != 0) { choices.withUsername(value("user", suppliedUser)); }
        if (localPassword != 0) { choices.withPassword(value("test-password", suppliedPassword)); }
        builder.withProxyConfig(choices.build());
        RuntimeException failure = null;
        MailerSessionSettings settings = null;
        try { settings = ((MailerGenericBuilderImpl<?>) builder).buildSessionSettings(session); }
        catch (RuntimeException rejected) { failure = rejected; }
        final String context = rule + " " + strategy + " supplied=" + supplied + " " + policy + " configured=" + configuredUser + "/" + configuredPassword
                + " local=" + localUser + "/" + localPassword;
        if (rejects) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
        else {
            assertThat(failure).as(context).isNull();
            assertThat(settings.getProxyConfig().getUsername()).isEqualTo(value("user", expectedUser));
            assertThat(settings.getProxyConfig().getPassword()).isEqualTo(value("test-password", expectedPassword));
            assertThat(settings.getProxyConfig().requiresAuthentication()).isEqualTo(expectedUser != 0);
            assertThat(settings.getProxyConfig().getRemoteProxyHost()).isEqualTo("proxy.example.org");
            assertThat(settings.getProxyConfig().getRemoteProxyPort()).isEqualTo(1081);
        }
        assertThat(endpoint).as(rule + " preserves the endpoint input").isEqualTo(before);
    }

    private static String value(final String prefix, final int value) { return value == 0 ? null : value == 4 ? "" : prefix + value; }
}
