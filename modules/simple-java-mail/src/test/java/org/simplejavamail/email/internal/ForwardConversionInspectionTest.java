package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.config.ConfigLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPairGenerator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ForwardConversionInspectionTest {

    private static final String EML = "From: sender@supersecret-testing-domain.com\r\nTo: reader@example.org\r\n"
            + "Date: not-a-date\r\nSubject: Source\r\nMessage-ID: <received@example.org>\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n\r\nbody\r\n";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void aNormalForwardDoesNotInheritItsInputsInspectionHistory() {
        final Email received = mail.converter().fromEml(EML).toEmail();
        assertThat(received.getProblems()).isNotEmpty();

        final Email forward = mail.emailBuilder().forwarding(received).buildEmail();

        assertThat(forward.getEmailToForward()).isNotNull();
        assertThat(forward.getProblems()).isEmpty();
        assertThat(forward.getSourceData().getOrigin()).isNull();
        assertThat(forward.getSourceData().getParts()).isEmpty();
        assertThat(received.getProblems()).isNotEmpty();
    }

    @Test
    void aForwardKeepsNewConversionEvidenceWithoutReplayingTheOldLedger() throws Exception {
        final Email received = mail.converter().fromEml(EML).toEmail();
        final EmailProblem old = received.getProblems().get(0);
        final KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        final DkimConfig signing = DkimConfig.builder().dkimSigningDomain("supersecret-testing-domain.com").dkimSelector("test")
                .dkimPrivateKeyData(keys.generateKeyPair().getPrivate().getEncoded()).build();
        final Email input = mail.emailBuilder().copying(received).fixingMessageId("<forward-input@example.org>")
                .withAttachment("failed.pdf", failingSource()).signWithDomainKey(signing).buildEmail();

        final Email forward = mail.emailBuilder().forwarding(input).buildEmail();

        assertThat(forward.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.CONVERSION);
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED);
            assertThat(problem.getOrigin().getMessageId()).isEqualTo("<forward-input@example.org>");
            assertThat(problem.getOrigin().getReference()).isNotEqualTo(old.getOrigin().getReference());
            final EmailSourcePart evidence = (EmailSourcePart) problem.getSources().get(0);
            assertThat(evidence.getContent().readAllBytes()).containsExactly(1, 2, 3);
            assertThat(forward.getSourceData().getParts()).contains(evidence);
        });
        assertThat(forward.getSourceData().getOrigin().getReference()).isNotEqualTo(received.getSourceData().getOrigin().getReference());
        assertThat(forward.getSourceData().getHeaders()).isEmpty();
        assertThat(forward.getEmailToForward().getHeader("DKIM-Signature")).hasSize(1);
        assertThat(input.getProblems()).hasSameSizeAs(received.getProblems());
        assertThat(input.getProblems().get(0).getOrigin().getMessageId()).isEqualTo("<received@example.org>");
        assertThat(input.getAttachments()).hasSize(1);
    }

    @Test
    void aForwardDoesNotHideARequestedProtectionFailure() {
        final DkimConfig invalid = DkimConfig.builder().dkimSigningDomain("supersecret-testing-domain.com").dkimSelector("test")
                .dkimPrivateKeyData("-----BEGIN PRIVATE KEY-----\nAQID\n-----END PRIVATE KEY-----".getBytes(UTF_8)).build();
        final Email input = mail.converter().fromEml(EML).toEmail();
        final Email signed = mail.emailBuilder().copying(input).signWithDomainKey(invalid).buildEmail();

        assertThatThrownBy(() -> mail.emailBuilder().forwarding(signed)).isInstanceOf(org.simplejavamail.MailException.class)
                .hasMessageContaining("DKIM");
    }

    private static DataSource failingSource() {
        return new DataSource() {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    private boolean prefixRead;
                    @Override public int read(final byte[] bytes, final int offset, final int length) throws IOException {
                        if (prefixRead) throw new IOException("attachment unavailable");
                        prefixRead = true;
                        System.arraycopy(new byte[] {1, 2, 3}, 0, bytes, offset, 3);
                        return 3;
                    }
                    @Override public int read() throws IOException { throw new IOException("attachment unavailable"); }
                };
            }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/pdf"; }
            @Override public String getName() { return "failed.pdf"; }
        };
    }
}
