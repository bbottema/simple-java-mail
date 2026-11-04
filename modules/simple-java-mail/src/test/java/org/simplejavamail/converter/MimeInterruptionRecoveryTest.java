package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.*;

class MimeInterruptionRecoveryTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    enum ContentPath {
        PLAIN_STREAM("text/plain; charset=UTF-8", true),
        HTML_STREAM("text/html; charset=UTF-8", true),
        CALENDAR_STREAM("text/calendar; charset=UTF-8; method=REQUEST", true),
        CALENDAR_HANDLER("text/calendar; charset=UTF-8; method=REQUEST", false);

        private final String type;
        private final boolean providerStream;
        ContentPath(final String type, final boolean providerStream) {
            this.type = type;
            this.providerStream = providerStream;
        }
    }

    @ParameterizedTest @EnumSource(ContentPath.class)
    void interruptedSelectedContentCancelsInsteadOfProducingAnInspectionProblem(final ContentPath path) throws Exception {
        final InterruptedIOException interruption = new InterruptedIOException("selected MIME read interrupted");
        final FailingSource source = new FailingSource(path.type, interruption);
        final MimeMessage input = message(path, source);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(input).toEmail())
                    .isInstanceOfSatisfying(CancellationException.class, canceled ->
                            assertThat(hasCause(canceled, interruption)).isTrue());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(source.opened).isEqualTo(1);
            assertThat(source.closed).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest @EnumSource(ContentPath.class)
    void anOrdinarySocketTimeoutRemainsARecoverableReadProblem(final ContentPath path) throws Exception {
        final FailingSource source = new FailingSource(path.type, new SocketTimeoutException("ordinary MIME timeout"));
        final MimeMessage input = message(path, source);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try (MockedStatic<ModuleLoader> ignored = Mockito.mockStatic(ModuleLoader.class)) {
            final Email email = MAIL.converter().fromMimeMessage(input).toEmail();
            assertThat(email.getProblems()).singleElement().satisfies(problem -> {
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
                assertThat(problem.getMessage()).contains("ordinary MIME timeout");
            });
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(source.opened).isEqualTo(1);
            assertThat(source.closed).isEqualTo(1);
        } finally {
            Thread.interrupted();
        }
    }

    private static MimeMessage message(final ContentPath path, final FailingSource source) throws Exception {
        final DataHandler handler = path.providerStream ? new DataHandler(source) {
            @Override public Object getContent() throws IOException { return source.getInputStream(); }
        } : new DataHandler(source);
        final MimeMessage result = new MimeMessage(Session.getInstance(new Properties()));
        result.setDataHandler(handler);
        result.setHeader("Content-Type", path.type);
        result.setFrom("sender@example.org");
        result.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
        return result;
    }

    private static boolean hasCause(final Throwable failure, final Throwable expected) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current == expected) return true;
        }
        return false;
    }

    private static final class FailingSource implements DataSource {
        private final String type;
        private final IOException failure;
        private int opened;
        private int closed;
        private FailingSource(final String type, final IOException failure) {
            this.type = type;
            this.failure = failure;
        }
        @Override public InputStream getInputStream() {
            opened++;
            return new InputStream() {
                private IOException signaledFailure() {
                    if (failure instanceof InterruptedIOException && !(failure instanceof SocketTimeoutException)) {
                        Thread.currentThread().interrupt();
                    }
                    return failure;
                }
                @Override public int read() throws IOException { throw signaledFailure(); }
                @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException { throw signaledFailure(); }
                @Override public void close() { closed++; }
            };
        }
        @Override public OutputStream getOutputStream() throws IOException { throw new IOException("read only"); }
        @Override public String getContentType() { return type; }
        @Override public String getName() { return "selected MIME content"; }
    }
}
