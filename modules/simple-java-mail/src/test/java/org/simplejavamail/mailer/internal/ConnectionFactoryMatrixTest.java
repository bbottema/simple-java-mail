package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.TlsConfig;
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

    @Test
    void factoryClassAndBorrowedInstanceSelectionsRetainTheirOwnBoundaries() {
        final SSLSocketFactory[] instances = {null, mock(SSLSocketFactory.class), mock(SSLSocketFactory.class)};
        for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
            for (int factoryClass = 0; factoryClass < 3; factoryClass++) {
                for (int rawClass = 0; rawClass < 3; rawClass++) {
                    for (int rawInstance = 0; rawInstance < 3; rawInstance++) {
                        for (int localClass = 0; localClass < 4; localClass++) {
                            for (int localInstance = 0; localInstance < 4; localInstance++) {
                                verify(policy, factoryClass, rawClass, rawInstance, localClass, localInstance, instances);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void verify(final ConnectionConflictPolicy policy, final int configuredClass, final int rawClass,
            final int rawInstance, final int localClass, final int localInstance, final SSLSocketFactory[] instances) {
        final int selectedClass = localClass == 3 ? 0 : localClass != 0 ? localClass : rawClass != 0 ? rawClass : configuredClass == 0 ? 0 : 1;
        final int selectedInstance = localInstance == 3 ? 0 : localInstance != 0 ? localInstance : rawInstance;
        final boolean classConflict = configuredClass != 0 && rawClass == 2
                || configuredClass != 0 && localClass != 0 && localClass != 1
                || rawClass != 0 && localClass != 0 && (localClass == 3 || localClass != rawClass);
        final boolean instanceConflict = rawInstance != 0 && localInstance != 0 && (localInstance == 3 || localInstance != rawInstance);
        final boolean violatesLock = configuredClass == 2 && (rawClass == 2 || localClass == 2 || localClass == 3 || selectedInstance != 0);
        final boolean rejects = policy == ConnectionConflictPolicy.REJECT_CONFLICTS && (classConflict || instanceConflict) || violatesLock;
        final Map<String, Object> configuration = new LinkedHashMap<>();
        if (configuredClass != 0) {
            configuration.put(configuredClass == 2 ? "simplejavamail.locked.custom.sslfactory.class" : "simplejavamail.custom.sslfactory.class", CLASS_A);
        }
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(ConfigLoader.builder().withMap(configuration).load())
                .withSMTPServerHost("relay.example.org").withConnectionConflictPolicy(policy);
        final Properties raw = new Properties();
        if (rawClass != 0) { raw.setProperty("mail.smtp.ssl.socketFactory.class", className(rawClass)); }
        if (rawInstance != 0) { raw.put("mail.smtp.ssl.socketFactory", instances[rawInstance]); }
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
        final String context = policy + " configured=" + configuredClass + " raw=" + rawClass + "/" + rawInstance
                + " local=" + localClass + "/" + localInstance;
        if (rejects) { assertThat(failure).as(context).isInstanceOf(IllegalArgumentException.class); }
        else {
            assertThat(failure).as(context).isNull();
            assertThat(plan.serverConfig().getCustomSSLFactoryClass()).as(context).isEqualTo(className(selectedClass));
            assertThat(plan.serverConfig().getCustomSSLFactoryInstance()).as(context).isSameAs(instances[selectedInstance]);
            final boolean shadowed = localInstance != 0 && localInstance != 3 && rawClass == 0;
            assertThat(plan.settings().copyEffectiveProperties().getProperty("mail.smtp.ssl.socketFactory.class"))
                    .as(context).isEqualTo(shadowed ? null : className(selectedClass));
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
