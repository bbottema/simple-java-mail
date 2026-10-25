package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.internal.configresolution.ConfigurationContractModel;

import javax.net.ssl.SSLSocketFactory;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConnectionRawSourceMatrixTest {
    @Test
    void everyTypedMemberProjectionHasAnExplicitCoverageCategory() {
        final Set<Property> adopted = new LinkedHashSet<>();
        for (Property property : Property.values()) {
            if (!ConnectionConfigurationResolver.sessionKeys(property, TransportStrategy.SMTP).isEmpty()
                    || !ConnectionConfigurationResolver.sessionKeys(property, TransportStrategy.SMTPS).isEmpty()) {
                adopted.add(property);
            }
        }
        // Production supplies only the inventory under examination, never the expected category or value.
        // Compound trust and transport selectors have separate TLS/OAuth tables rather than scalar projections.
        assertThat(adopted).containsExactlyInAnyOrderElementsOf(ConnectionCoverageCatalogue.TYPED_MEMBERS.keySet());
    }

    @Test
    void everySupportedAliasHasAnExplicitCoverageCategory() {
        for (TransportStrategy strategy : new TransportStrategy[] {TransportStrategy.SMTP, TransportStrategy.SMTPS}) {
            final String prefix = strategy == TransportStrategy.SMTP ? "mail.smtp." : "mail.smtps.";
            final Set<String> classified = new LinkedHashSet<>();
            ConnectionCoverageCatalogue.MEMBERS.keySet().forEach(member -> classified.add(prefix + member));
            classified.add("mail.transport.protocol"); // Protocol/mandatory TLS and OAuth matrices own this cross-property selector.
            classified.add("simplejavamail.oauth2.token"); // OAuth credential composition tests own static/runtime exclusivity.
            classified.add("simplejavamail.oauth2.token.provider"); // A provider is a borrowed identity, never a property-file object.
            assertThat(ConnectionPropertyValues.supportedNames(strategy)).containsExactlyInAnyOrderElementsOf(classified);
        }
    }

    @Test
    void allRawSourceCombinationsPreserveTheSelectedProviderRepresentation() {
        final SSLSocketFactory factoryA = mock(SSLSocketFactory.class);
        final SSLSocketFactory factoryB = mock(SSLSocketFactory.class);
        for (Map.Entry<String, ConnectionCoverageCatalogue.Kind> member : ConnectionCoverageCatalogue.MEMBERS.entrySet()) {
            for (TransportStrategy strategy : new TransportStrategy[] {TransportStrategy.SMTP, TransportStrategy.SMTPS}) {
                for (boolean supplied : new boolean[] {false, true}) {
                    for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
                        for (int code = 0; code < 27; code++) {
                            final int[] sources = ConfigurationContractModel.digits(code, 3, 3);
                            if (!supplied && sources[1] != 0 || member.getValue() == ConnectionCoverageCatalogue.Kind.FACTORY && sources[0] != 0) { continue; }
                            final String key = (strategy == TransportStrategy.SMTP ? "mail.smtp." : "mail.smtps.") + member.getKey();
                            final Object first = member.getValue() == ConnectionCoverageCatalogue.Kind.FACTORY ? factoryA
                                    : member.getValue() == ConnectionCoverageCatalogue.Kind.NUMBER ? "123"
                                    : member.getValue() == ConnectionCoverageCatalogue.Kind.BOOLEAN ? "true" : "first.example.org";
                            final Object second = member.getValue() == ConnectionCoverageCatalogue.Kind.FACTORY ? factoryB
                                    : member.getValue() == ConnectionCoverageCatalogue.Kind.NUMBER ? "456"
                                    : member.getValue() == ConnectionCoverageCatalogue.Kind.BOOLEAN ? "false" : "second.example.org";
                            final Map<String, Object> config = new LinkedHashMap<>();
                            if (sources[0] != 0) { config.put("simplejavamail.extraproperties." + key, sources[0] == 1 ? first : second); }
                            final Properties original = new Properties();
                            original.setProperty("mail.transport.protocol", strategy == TransportStrategy.SMTP ? "smtp" : "smtps");
                            if (sources[1] != 0) { original.put(key, sources[1] == 1 ? first : second); }
                            final var mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(config).load());
                            final Session session = supplied ? Session.getInstance(original) : null;
                            final MailerGenericBuilder<?> builder = (supplied ? mail.mailerBuilder(session) : mail.mailerBuilder().withTransportStrategy(strategy))
                                    .withTransportModeLoggingOnly(true).withConnectionConflictPolicy(policy);
                            if (sources[2] != 0) {
                                final Properties raw = new Properties();
                                raw.put(key, sources[2] == 1 ? first : second);
                                builder.withProperties(raw);
                            }
                            final boolean rejects = policy == ConnectionConflictPolicy.REJECT_CONFLICTS && ConfigurationContractModel.conflicts(sources, -1);
                            final int selected = ConfigurationContractModel.selectedIndex(sources, new int[] {2, 0, 1});
                            final Object expected = selected < 0 ? fallback(member.getKey(), strategy, supplied)
                                    : sources[selected] == 1 ? first : second;
                            RuntimeException failure = null;
                            Object actual = null;
                            try {
                                actual = ((MailerGenericBuilderImpl<?>) builder).buildSessionSettings(session).copyEffectiveProperties().get(key);
                            } catch (RuntimeException rejected) { failure = rejected; }
                            final String context = key + " supplied=" + supplied + " " + policy + " case=" + code;
                            if (rejects) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
                            else {
                                assertThat(failure).as(context).isNull();
                                if (member.getValue() == ConnectionCoverageCatalogue.Kind.FACTORY) { assertThat(actual).as(context).isSameAs(expected); }
                                else { assertThat(actual).as(context).isEqualTo(expected); }
                            }
                        }
                    }
                }
            }
        }
    }

    private static String fallback(final String member, final TransportStrategy strategy, final boolean supplied) {
        if (supplied) { return null; }
        switch (member) {
            case "connectiontimeout": case "timeout": case "writetimeout": return "60000";
            case "port": return strategy == TransportStrategy.SMTP ? "25" : "465";
            case "ssl.checkserveridentity": return "true";
            case "starttls.enable": return strategy == TransportStrategy.SMTP ? "true" : null;
            case "starttls.required": return strategy == TransportStrategy.SMTP ? "false" : null;
            default: return null;
        }
    }
}
