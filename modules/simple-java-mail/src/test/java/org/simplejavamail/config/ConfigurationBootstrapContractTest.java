package org.simplejavamail.config;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetSystemProperty;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.MailerFromSessionBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class ConfigurationBootstrapContractTest {
    @Test
    void everyExplicitFactoryRootUsesTheSelectedSnapshotAndReturnsIndependentDrafts() throws Exception {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(Map.of(
                ConfigKeys.SMTP_HOST.key(), "factory.example.test", ConfigKeys.DEFAULT_SUBJECT.key(), "factory subject")).load();
        final SimpleJavaMail factory = (SimpleJavaMail) invoke(null, SimpleJavaMail.class.getMethod("withConfig", SimpleJavaMailConfig.class), config);
        assertThat(factory.getConfig()).isSameAs(config);
        final MailerRegularBuilder<?> regular = (MailerRegularBuilder<?>) invoke(factory, SimpleJavaMail.class.getMethod("mailerBuilder"));
        assertThat(regular.getHost()).isEqualTo("factory.example.test");
        assertThat(factory.mailerBuilder()).isNotSameAs(regular);
        final Session session = Session.getInstance(new Properties());
        final MailerFromSessionBuilder<?> supplied = (MailerFromSessionBuilder<?>) invoke(factory,
                SimpleJavaMail.class.getMethod("mailerBuilder", Session.class), session);
        assertThat(supplied.getSession()).isSameAs(session);
        final EmailStartingBuilder email = (EmailStartingBuilder) invoke(factory, SimpleJavaMail.class.getMethod("emailBuilder"));
        assertThat(email.startingBlank().buildEmail().getSubject()).isNull();
        assertThat(email.startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getSubject()).isEqualTo("factory subject");
        final ConfiguredEmailConverter converter = (ConfiguredEmailConverter) invoke(factory, SimpleJavaMail.class.getMethod("converter"));
        final byte[] eml = "From: sender@example.test\r\nTo: receiver@example.test\r\n\r\nbody\r\n".getBytes(StandardCharsets.US_ASCII);
        final EmailPopulatingBuilder imported = converter.fromEml(eml).toEmailBuilder();
        assertThat(imported.buildEmail().getSubject()).isEmpty();
        assertThat(imported.clearSubject().buildEmailCompletedWithDefaultsAndOverrides().getSubject()).isEqualTo("factory subject");
        final IRecipientBuilder recipientBuilder = (IRecipientBuilder) invoke(factory, SimpleJavaMail.class.getMethod("recipientBuilder"));
        final Recipient recipient = recipientBuilder.withAddress("receiver@example.test").build();
        assertThat(recipient.getAddress()).isEqualTo("receiver@example.test");
        final IRecipientsBuilder recipients = (IRecipientsBuilder) invoke(factory, SimpleJavaMail.class.getMethod("recipientsBuilder"));
        assertThat(recipients.withRecipients(List.of(recipient)).buildRecipients()).containsExactly(recipient);
        regular.withSMTPServerHost("changed.example.test");
        assertThat(config.get(ConfigKeys.SMTP_HOST)).isEqualTo("factory.example.test");
        assertThat(factory.mailerBuilder().getHost()).isEqualTo("factory.example.test");
        record("rule", "bootstrap.snapshot-ownership");
        for (String root : new String[] {"explicit", "mailer", "session", "email", "recipient", "recipients", "converter"}) {
            record("adapter", "bootstrap." + root);
        }
        record("case", "bootstrap.explicit-roots");
    }

    @Test
    @SetSystemProperty(key = "simplejavamail.defaults.subject", value = "first system subject")
    void conventionalBootstrapLoadsOnceAndKeepsTheCapturedSourceOrder() throws Exception {
        try (DefaultFactoryClassLoader loader = new DefaultFactoryClassLoader()) {
            final Class<?> factoryType = Class.forName(SimpleJavaMail.class.getName(), true, loader);
            assertThat(loader.resourceReads).hasValue(0);
            final Object factory = invoke(null, factoryType.getMethod("fromDefaults"));
            assertThat(loader.resourceReads).hasValue(1);
            assertThat(invoke(null, factoryType.getMethod("fromDefaults"))).isSameAs(factory);
            assertThat(completedSubject(factoryType, factory)).isEqualTo("first system subject");
            System.setProperty(ConfigKeys.DEFAULT_SUBJECT.key(), "later system subject");
            assertThat(completedSubject(factoryType, factory)).isEqualTo("first system subject");
            assertThat(loader.resourceReads).hasValue(1);
            record("rule", "bootstrap.lazy-defaults");
            record("adapter", "bootstrap.defaults");
            record("case", "bootstrap.conventional-snapshot");
        }
    }

    private static Object completedSubject(final Class<?> factoryType, final Object factory) throws Exception {
        final Object starter = factoryType.getMethod("emailBuilder").invoke(factory);
        final Object draft = starter.getClass().getMethod("startingBlank").invoke(starter);
        final Object email = draft.getClass().getMethod("buildEmailCompletedWithDefaultsAndOverrides").invoke(draft);
        return email.getClass().getMethod("getSubject").invoke(email);
    }

    /** A separate library class loader exercises lazy bootstrap without changing the process's ordinary singleton. */
    private static final class DefaultFactoryClassLoader extends URLClassLoader {
        private final AtomicInteger resourceReads = new AtomicInteger();
        private final URL defaultResource;

        private DefaultFactoryClassLoader() throws Exception {
            super(testClasspath(), ClassLoader.getSystemClassLoader().getParent());
            final byte[] properties = (ConfigKeys.DEFAULT_SUBJECT.key() + "=classpath subject\n").getBytes(StandardCharsets.UTF_8);
            defaultResource = new URL(null, "memory:simplejavamail.properties", new URLStreamHandler() {
                @Override protected URLConnection openConnection(final URL url) {
                    return new URLConnection(url) {
                        @Override public void connect() { }
                        @Override public InputStream getInputStream() { return new ByteArrayInputStream(properties); }
                    };
                }
            });
        }

        @Override public URL getResource(final String name) {
            if (name.equals(ConfigLoader.DEFAULT_CONFIG_FILENAME)) {
                resourceReads.incrementAndGet();
                return defaultResource;
            }
            return super.getResource(name);
        }

        private static URL[] testClasspath() throws Exception {
            final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            final List<URL> urls = new ArrayList<>();
            for (String entry : classpath.split(Pattern.quote(File.pathSeparator))) { urls.add(new File(entry).toURI().toURL()); }
            return urls.toArray(new URL[0]);
        }
    }
}
