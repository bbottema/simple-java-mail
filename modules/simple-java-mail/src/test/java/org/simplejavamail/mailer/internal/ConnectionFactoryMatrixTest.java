package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import javax.net.ssl.SSLSocketFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ConnectionFactoryMatrixTest {
    private static final String CLASS_A = "javax.net.ssl.SSLSocketFactory";
    private static final String CLASS_B = "application.OtherFactory";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // Classes/instances: 0 absent/inherit, 1 first, 2 second. Configured class 2 locks class A; local 3 removes.
            // rule | protocol | policy | configured class | raw class/instance | Java class/instance | expected class/instance/physical class | rejects
            "no factory choice | SMTP | REJECT_CONFLICTS | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | false",
            "captured class selection | SMTPS | REJECT_CONFLICTS | 1 | 0 | 0 | 0 | 0 | 1 | 0 | 1 | false",
            "raw class selection | SMTP | REJECT_CONFLICTS | 0 | 2 | 0 | 0 | 0 | 2 | 0 | 2 | false",
            "equivalent class declarations | SMTPS | REJECT_CONFLICTS | 1 | 1 | 0 | 1 | 0 | 1 | 0 | 1 | false",
            "strict raw class disagreement | SMTP | REJECT_CONFLICTS | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 | true",
            "deliberate Java class override | SMTPS | ALLOW_EXPLICIT_OVERRIDES | 1 | 0 | 0 | 2 | 0 | 2 | 0 | 2 | false",
            "strict Java class disagreement | SMTP | REJECT_CONFLICTS | 1 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | true",
            "strict imported class removal | SMTPS | REJECT_CONFLICTS | 1 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | true",
            "deliberate imported class removal | SMTP | ALLOW_EXPLICIT_OVERRIDES | 1 | 0 | 0 | 3 | 0 | 0 | 0 | 0 | false",
            "borrowed raw instance | SMTPS | REJECT_CONFLICTS | 0 | 0 | 1 | 0 | 0 | 0 | 1 | 0 | false",
            "same instance identity accepted | SMTP | REJECT_CONFLICTS | 0 | 0 | 1 | 0 | 1 | 0 | 1 | 0 | false",
            "distinct instance identity rejected | SMTPS | REJECT_CONFLICTS | 0 | 0 | 1 | 0 | 2 | 0 | 0 | 0 | true",
            "deliberate instance replacement | SMTP | ALLOW_EXPLICIT_OVERRIDES | 0 | 0 | 1 | 0 | 2 | 0 | 2 | 0 | false",
            "strict imported instance removal | SMTPS | REJECT_CONFLICTS | 0 | 0 | 1 | 0 | 3 | 0 | 0 | 0 | true",
            "deliberate imported instance removal | SMTP | ALLOW_EXPLICIT_OVERRIDES | 0 | 0 | 1 | 0 | 3 | 0 | 0 | 0 | false",
            "typed instance shadows captured class | SMTPS | REJECT_CONFLICTS | 1 | 0 | 0 | 0 | 1 | 1 | 1 | 0 | false",
            "locked class rejects typed instance | SMTP | ALLOW_EXPLICIT_OVERRIDES | 2 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | true",
            "locked class rejects raw instance | SMTPS | REJECT_CONFLICTS | 2 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | true",
            "locked equivalent class accepted | SMTP | REJECT_CONFLICTS | 2 | 1 | 0 | 1 | 0 | 1 | 0 | 1 | false",
            "override policy cannot defeat class lock | SMTPS | ALLOW_EXPLICIT_OVERRIDES | 2 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | true",
            "typed instance retains explicit raw class | SMTP | REJECT_CONFLICTS | 0 | 1 | 0 | 0 | 1 | 1 | 1 | 1 | false",
            "clearing local instance restores class | SMTPS | REJECT_CONFLICTS | 1 | 0 | 0 | 0 | 3 | 1 | 0 | 1 | false",
            "raw class and instance remain separate choices | SMTP | REJECT_CONFLICTS | 0 | 1 | 1 | 0 | 0 | 1 | 1 | 1 | false"
    })
    void factoryRuleCategories(final String rule, final TransportStrategy strategy, final ConnectionConflictPolicy policy,
            final int configuredClass, final int rawClass, final int rawInstance, final int localClass, final int localInstance,
            final int expectedClass, final int expectedInstance, final int expectedPhysicalClass, final boolean rejects) {
        final SSLSocketFactory[] instances = {null, mock(SSLSocketFactory.class), mock(SSLSocketFactory.class)};
        final Map<String, Object> configuration = new LinkedHashMap<>();
        if (configuredClass != 0) {
            configuration.put(configuredClass == 2 ? "simplejavamail.locked.custom.sslfactory.class" : "simplejavamail.custom.sslfactory.class", CLASS_A);
        }
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder().withMap(configuration).load())
                .withSMTPServerHost("relay.example.org").withTransportStrategy(strategy).withConnectionConflictPolicy(policy);
        final Properties raw = new Properties();
        if (rawClass != 0) { raw.setProperty(strategy.propertyNameSSLSocketFactoryClass(), className(rawClass)); }
        if (rawInstance != 0) { raw.put(strategy.propertyNameSSLSocketFactory(), instances[rawInstance]); }
        builder.withProperties(raw);
        final TlsConfig.Builder choices = TlsConfig.builder();
        if (localClass != 0) { choices.withCustomSSLFactoryClass(localClass == 3 ? null : className(localClass)); }
        if (localInstance != 0) { choices.withCustomSSLFactoryInstance(localInstance == 3 ? null : instances[localInstance]); }
        builder.withTlsConfig(choices.build());
        RuntimeException failure = null;
        ValidatedConnectionPlan plan = null;
        try {
            builder.validateLockedConfiguration();
            plan = builder.prepareConnectionPlan(null);
        } catch (RuntimeException rejected) { failure = rejected; }
        final String context = rule + " " + policy + " configured=" + configuredClass + " raw=" + rawClass + "/" + rawInstance
                + " local=" + localClass + "/" + localInstance;
        if (rejects) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
        else {
            assertThat(failure).as(context).isNull();
            assertThat(plan.serverConfig().getCustomSSLFactoryClass()).as(context).isEqualTo(className(expectedClass));
            assertThat(plan.serverConfig().getCustomSSLFactoryInstance()).as(context).isSameAs(instances[expectedInstance]);
            assertThat(plan.settings().copyEffectiveProperties().getProperty(strategy.propertyNameSSLSocketFactoryClass()))
                    .as(context).isEqualTo(className(expectedPhysicalClass));
        }
    }

    private static String className(final int value) { return value == 0 ? null : value == 1 ? CLASS_A : CLASS_B; }

    @Test
    void selectedPoolMemberCannotHideAnInstanceBehindALockedFactoryClass() throws Exception {
        final Properties raw = new Properties();
        raw.setProperty("mail.smtp.ssl.socketFactory.class", CLASS_A);
        raw.put("mail.smtp.ssl.socketFactory", mock(SSLSocketFactory.class));
        try (Mailer locked = new MailerRegularBuilderImpl(ConfigLoader.builder()
                .withMap(Map.of("simplejavamail.locked.custom.sslfactory.class", CLASS_A)).load())
                .withSMTPServerHost("relay.example.org").buildMailer();
             Mailer candidate = new MailerRegularBuilderImpl(ConfigLoader.builder().load())
                     .withSMTPServerHost("relay.example.org").withProperties(raw).buildMailer()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    SessionBasedEmailToMimeMessageConverter.verifySelectedSession(locked.getSession(), candidate.getSession()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("locked.custom.sslfactory.class");
        }
    }
}
