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

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProxyMatrixTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', nullValues = "~", value = {
            // Source: 0 factory, 1 Java raw, 2 Session. Java choices: 0 inherit, 1 equivalent, 2 different, 3 remove.
            // rule | protocol | supplied | policy | source | raw host/port | Java host/port choices | Java first | expected host/port | rejects
            "owned fallback | SMTP | false | REJECT_CONFLICTS | 1 | ~ | ~ | 0 | 0 | false | ~ | 1080 | false",
            "untouched Session gap | SMTPS | true | REJECT_CONFLICTS | 2 | ~ | ~ | 0 | 0 | false | ~ | ~ | false",
            "raw route uses provider port | SMTP | false | REJECT_CONFLICTS | 1 | raw.example.org | ~ | 0 | 0 | false | raw.example.org | 1080 | false",
            "Session route preserves missing port | SMTPS | true | REJECT_CONFLICTS | 2 | raw.example.org | ~ | 0 | 0 | false | raw.example.org | 1080 | false",
            "Session embedded port stays embedded | SMTP | true | REJECT_CONFLICTS | 2 | raw.example.org:1081 | ~ | 0 | 0 | false | raw.example.org | 1081 | false",
            "captured embedded endpoint | SMTPS | false | REJECT_CONFLICTS | 0 | raw.example.org:1081 | ~ | 0 | 0 | false | raw.example.org | 1081 | false",
            "separate port beats embedded port | SMTP | false | REJECT_CONFLICTS | 1 | raw.example.org:1081 | 1082 | 0 | 0 | false | raw.example.org | 1082 | false",
            "hexadecimal separate port | SMTPS | true | REJECT_CONFLICTS | 1 | raw.example.org:1081 | 0x43a | 0 | 0 | false | raw.example.org | 1082 | false",
            "equivalent typed endpoint | SMTP | false | REJECT_CONFLICTS | 0 | raw.example.org | 1082 | 1 | 1 | false | raw.example.org | 1082 | false",
            "equivalent hexadecimal port | SMTPS | true | REJECT_CONFLICTS | 2 | raw.example.org | 0x43a | 0 | 1 | false | raw.example.org | 1082 | false",
            "strict host disagreement | SMTP | false | REJECT_CONFLICTS | 1 | raw.example.org | 1082 | 2 | 0 | false | ~ | ~ | true",
            "partial host override retains port | SMTPS | true | ALLOW_EXPLICIT_OVERRIDES | 2 | raw.example.org | 1082 | 2 | 0 | false | typed.example.org | 1082 | false",
            "partial port override retains host | SMTP | false | ALLOW_EXPLICIT_OVERRIDES | 0 | raw.example.org | 1082 | 0 | 2 | false | raw.example.org | 1084 | false",
            "typed first wins deliberately | SMTPS | false | ALLOW_EXPLICIT_OVERRIDES | 1 | raw.example.org | 1082 | 2 | 0 | true | typed.example.org | 1082 | false",
            "typed first conflicts strictly | SMTP | true | REJECT_CONFLICTS | 1 | raw.example.org | 1082 | 2 | 0 | true | ~ | ~ | true",
            "deliberate imported host removal | SMTPS | true | ALLOW_EXPLICIT_OVERRIDES | 2 | raw.example.org | 1082 | 3 | 0 | false | ~ | 1082 | false",
            "strict imported host removal | SMTP | false | REJECT_CONFLICTS | 0 | raw.example.org | 1082 | 3 | 0 | false | ~ | ~ | true",
            "active route needs a port | SMTPS | false | ALLOW_EXPLICIT_OVERRIDES | 1 | raw.example.org | 1082 | 0 | 3 | false | ~ | ~ | true",
            "new typed route uses fallback | SMTP | true | REJECT_CONFLICTS | 1 | ~ | ~ | 2 | 0 | false | typed.example.org | 1080 | false",
            "inactive port-only choice | SMTPS | false | REJECT_CONFLICTS | 1 | ~ | ~ | 0 | 2 | false | ~ | 1084 | false",
            "local-only removal has no import conflict | SMTP | false | REJECT_CONFLICTS | 1 | ~ | ~ | 3 | 0 | false | ~ | 1080 | false"
    })
    void endpointRuleCategories(final String rule, final TransportStrategy strategy, final boolean supplied,
            final ConnectionConflictPolicy policy, final int source, final String rawHost, final String rawPort,
            final int hostChoice, final int portChoice, final boolean typedFirst,
            final String expectedHost, final Integer expectedPort, final boolean rejects) {
        ConfigurationAdoptionCatalogue.assertValueBuilderCategory(ConfigurationAdoptionCatalogue.Owner.MAILER_CONNECTION,
                SocksProxyConfig.Builder.class);
        final String suppliedHost = hostChoice == 1 ? "raw.example.org" : hostChoice == 2 ? "typed.example.org" : null;
        final Integer suppliedPort = portChoice == 1 ? Integer.valueOf(1082) : portChoice == 2 ? Integer.valueOf(1084) : null;
        final Properties properties = new Properties();
        if (rawHost != null) { properties.setProperty(strategy.propertyNameSocksHost(), rawHost); }
        if (rawPort != null) { properties.setProperty(strategy.propertyNameSocksPort(), rawPort); }
        final Map<String, String> captured = new java.util.LinkedHashMap<>();
        if (source == 0) { properties.forEach((key, value) -> captured.put("simplejavamail.extraproperties." + key, value.toString())); }
        final var factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(captured).load());
        final Properties sessionProperties = new Properties();
        sessionProperties.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
        if (source == 2) { sessionProperties.putAll(properties); }
        final Properties before = (Properties) sessionProperties.clone();
        final Session session = supplied ? Session.getInstance(sessionProperties) : null;
        final MailerGenericBuilder<?> builder = supplied ? factory.mailerBuilder(session)
                : factory.mailerBuilder().withSMTPServerHost("relay.example.org").withTransportStrategy(strategy);
        final SocksProxyConfig.Builder choices = SocksProxyConfig.builder();
        if (hostChoice != 0) { choices.withHost(suppliedHost); }
        if (portChoice != 0) { choices.withPort(suppliedPort); }
        final SocksProxyConfig selectedChoices = choices.build();
        builder.withConnectionConflictPolicy(policy);
        if (typedFirst) { builder.withProxyConfig(selectedChoices); }
        if (source == 1) { builder.withProperties(properties); }
        if (!typedFirst) { builder.withProxyConfig(selectedChoices); }
        MailerSessionSettings actual = null;
        RuntimeException failure = null;
        try { actual = ((MailerGenericBuilderImpl<?>) builder).buildSessionSettings(session); }
        catch (RuntimeException rejected) { failure = rejected; }
        final String context = rule + " " + strategy + " supplied=" + supplied + " " + policy + " source=" + source
                + " rawHost=" + rawHost + " rawPort=" + rawPort + " host=" + hostChoice + " port=" + portChoice;
        if (rejects) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
        else {
            assertThat(failure).as(context).isNull();
            assertThat(actual.getProxyConfig().getRemoteProxyHost()).as(context).isEqualTo(expectedHost);
            assertThat(actual.getProxyConfig().getRemoteProxyPort()).as(context).isEqualTo(expectedPort);
            if (supplied && hostChoice == 0 && portChoice == 0) {
                assertThat(actual.copyEffectiveProperties().getProperty(strategy.propertyNameSocksPort())).as(context).isEqualTo(rawPort);
            }
        }
        assertThat(sessionProperties).as(rule + " preserves the Session baseline").isEqualTo(before);
    }
}
