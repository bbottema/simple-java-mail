package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.api.mailer.config.TransportStrategy.OAUTH2_TOKEN_PROVIDER_PROPERTY;

class CustomMailerInactiveOAuthTest {
    private static final String BATCH_IMPLEMENTATION = "org.simplejavamail.internal.batchsupport.BatchSupport";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void customDeliveryKeepsSmtpInactiveAcrossActualModuleClasspaths(final boolean batchPresent) throws Exception {
        final String original = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final Path batchArtifact = Path.of(Class.forName(BATCH_IMPLEMENTATION, false, getClass().getClassLoader())
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        final String classpath = batchPresent ? original : Arrays.stream(original.split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !Path.of(entry).toAbsolutePath().normalize().equals(batchArtifact))
                .collect(Collectors.joining(File.pathSeparator));
        if (!batchPresent) assertThat(classpath).as("the batch implementation artifact is removed").isNotEqualTo(original);

        final Path reports = Path.of("target", "custom-mailer-inactive-oauth");
        Files.createDirectories(reports);
        final Path output = reports.resolve("batch-" + batchPresent + ".log");
        final Path arguments = reports.resolve("batch-" + batchPresent + ".args");
        Files.writeString(arguments, "-cp\n\"" + classpath.replace('\\', '/') + "\"\n"
                + Probe.class.getName() + "\n" + batchPresent + "\n");
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(45, SECONDS)).as("child finished; output: %s", output).isTrue();
            assertThat(child.exitValue()).as(Files.readString(output)).isZero();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertThat(child.waitFor(5, SECONDS)).as("child stopped").isTrue();
            }
        }
    }

    public static final class Probe {
        public static void main(final String[] args) throws Exception {
            assertThat(ModuleLoader.batchModuleAvailable()).isEqualTo(Boolean.parseBoolean(args[0]));
            final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
            for (ConnectionConflictPolicy policy : ConnectionConflictPolicy.values()) {
                for (boolean suppliedSession : new boolean[] {false, true}) {
                    customDeliveryRetainsUnusedCredentials(mail, policy, suppliedSession);
                }
                activeSmtpStillRejectsConflictingCredentials(mail, policy);
            }
        }

        private static void customDeliveryRetainsUnusedCredentials(final SimpleJavaMail mail, final ConnectionConflictPolicy policy,
                final boolean suppliedSession) throws Exception {
            final AtomicInteger tokenRequests = new AtomicInteger();
            final OAuth2AccessTokenProvider tokens = () -> "token-" + tokenRequests.incrementAndGet();
            final Properties smtp = credentials(tokens);
            final AtomicInteger sends = new AtomicInteger();
            final AtomicInteger connectionTests = new AtomicInteger();
            final CustomMailer custom = new CustomMailer() {
                @Override public void testConnection(final OperationalConfig operationalConfig, final Session session) {
                    assertCredentials(session, tokens);
                    connectionTests.incrementAndGet();
                }

                @Override public void sendMessage(final OperationalConfig operationalConfig, final Session session,
                        final Email email, final MimeMessage message) {
                    assertCredentials(session, tokens);
                    sends.incrementAndGet();
                }
            };
            final Session source = Session.getInstance(smtp);
            final MailerGenericBuilder<?> builder = suppliedSession ? mail.mailerBuilder(source) : mail.mailerBuilder().withProperties(smtp);
            try (Mailer mailer = builder.withConnectionConflictPolicy(policy).withCustomMailer(custom).buildMailer()) {
                if (suppliedSession) assertThat(mailer.getSession()).isSameAs(source);
                assertCredentials(mailer.getSession(), tokens);
                mailer.sync().sendMail(mail.emailBuilder().startingBlank()
                        .from("sender@example.test").to("recipient@example.test").withPlainText("Custom delivery").buildEmail());
                mailer.sync().testConnection();
            }
            assertThat(sends).hasValue(1);
            assertThat(connectionTests).hasValue(1);
            assertThat(tokenRequests).hasValue(0);
            assertThat(smtp.get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(tokens);
            assertThat(smtp.getProperty(OAUTH2_TOKEN_PROPERTY)).isEqualTo("retained-fixed-token");
        }

        private static void activeSmtpStillRejectsConflictingCredentials(final SimpleJavaMail mail,
                final ConnectionConflictPolicy policy) {
            final AtomicInteger tokenRequests = new AtomicInteger();
            final Properties smtp = credentials(() -> "token-" + tokenRequests.incrementAndGet());
            assertThatThrownBy(() -> {
                try (Mailer ignored = mail.mailerBuilder().withConnectionConflictPolicy(policy)
                        .withSMTPServer("smtp.example.test", 587, "user@example.test", "retained-fixed-token")
                        .withTransportStrategy(TransportStrategy.SMTP_OAUTH2).withProperties(smtp).buildMailer()) {
                    // An incorrectly accepted build still retires its resources.
                }
            }).isInstanceOf(MailException.class).hasMessage(MailerException.CONFLICTING_OAUTH2_CREDENTIALS);
            assertThat(tokenRequests).hasValue(0);
        }

        private static Properties credentials(final OAuth2AccessTokenProvider provider) {
            final Properties smtp = new Properties();
            smtp.setProperty(OAUTH2_TOKEN_PROPERTY, "retained-fixed-token");
            smtp.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, provider);
            return smtp;
        }

        private static void assertCredentials(final Session session, final OAuth2AccessTokenProvider provider) {
            assertThat(session.getProperties().get(OAUTH2_TOKEN_PROVIDER_PROPERTY)).isSameAs(provider);
            assertThat(session.getProperty(OAUTH2_TOKEN_PROPERTY)).isEqualTo("retained-fixed-token");
        }
    }
}
