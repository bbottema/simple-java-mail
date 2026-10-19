package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.SessionDebugConfig;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebugConfigurationCompanionTest {
    @Test
    void outputClearsRestoreTheCapturedSelectionWithoutClosingBorrowedStreams() throws Exception {
        final SimpleJavaMail mail = factory(Map.of("javaxmail.debug", "true", "javaxmail.debug.out", "STDERR"));
        final TrackingPrinter custom = new TrackingPrinter();
        final MailerRegularBuilder<?> builder = mail.mailerBuilder().withDebugConfig(SessionDebugConfig.builder().withPrinter(custom).build());
        assertThat(builder.getDebugConfig().getOutput()).isNull();
        try (Mailer original = builder.buildMailer()) {
            assertThat(builder.useConfiguredDebugConfig()).isSameAs(builder);
            assertThat(builder.getDebugConfig().getOutput()).isNull();
            assertThat(builder.getDebugConfig().getPrinter()).isNull();
            assertThat(builder.getDebugConfig().getEnabled()).isNull();
            try (Mailer inherited = builder.buildMailer()) {
                assertThat(inherited.getSession().getDebugOut()).isSameAs(System.err);
                assertThat(inherited.getOperationalConfig().getDebugConfig().getEnabled()).isTrue();
            }
            builder.withDebugConfig(SessionDebugConfig.builder().withOutput(SessionDebugOutput.STDOUT).useConfiguredOutput().build());
            assertThat(builder.getDebugConfig().getOutput()).isNull();
            assertThat(original.getSession().getDebugOut()).isSameAs(custom);
            builder.withDebugConfig(SessionDebugConfig.builder().resetEnabled().build());
            assertThat(builder.getDebugConfig().getEnabled()).isEqualTo(MailerGenericBuilder.DEFAULT_JAVAXMAIL_DEBUG);
        }
        assertThat(custom.closed).isZero();
        assertThat(mail.mailerBuilder().getDebugConfig().getEnabled()).isNull();
    }

    @Test
    void absentOutputDoesNotReplaceTheCallerSessionsPrinter() throws Exception {
        final TrackingPrinter baseline = new TrackingPrinter();
        final TrackingPrinter custom = new TrackingPrinter();
        final Session session = Session.getInstance(new Properties());
        session.setDebugOut(baseline);
        final MailerGenericBuilder<?> builder = factory(Map.of()).mailerBuilder(session).withDebugConfig(SessionDebugConfig.builder().withPrinter(custom).useConfiguredOutput().build());
        assertThat(builder.getDebugConfig().getOutput()).isNull();
        assertThat(builder.getDebugConfig().getPrinter()).isNull();
        try (Mailer mailer = builder.buildMailer()) {
            assertThat(mailer.getSession().getDebugOut()).isSameAs(baseline);
        }
        assertThat(baseline.closed).isZero();
        assertThat(custom.closed).isZero();
    }

    @Test
    void clearsRestoreCompatibleLocksButAConflictingResetStillFails() throws Exception {
        final SimpleJavaMail mail = factory(Map.of("locked.javaxmail.debug.out", "STDERR", "locked.javaxmail.debug", "true"));
        try (Mailer ignored = mail.mailerBuilder().withDebugConfig(SessionDebugConfig.builder().withPrinter(new TrackingPrinter()).useConfiguredOutput().build()).buildMailer()) {
            assertThat(ignored.getSession().getDebugOut()).isSameAs(System.err);
        }
        assertThatThrownBy(() -> mail.mailerBuilder().withDebugConfig(SessionDebugConfig.builder().resetEnabled().build()).buildMailer())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("simplejavamail.locked.javaxmail.debug");
    }

    private static SimpleJavaMail factory(final Map<String, String> overrides) {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("simplejavamail.smtp.host", "localhost");
        overrides.forEach((tail, value) -> values.put("simplejavamail." + tail, value));
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(values).load());
    }

    private static final class TrackingPrinter extends PrintStream {
        private int closed;
        private TrackingPrinter() { super(new ByteArrayOutputStream()); }
        @Override public void close() { closed++; super.close(); }
    }
}
