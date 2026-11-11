package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ValueOrigin;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import testutil.ConfigLoaderTestHelper;

import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class GovernedLockInspectionTest {
    private static final byte[] INPUT = ("Return-Path: <old-return@example.org>\r\nFrom: author@example.org\r\nTo: reader@example.org\r\n"
            + "Subject: Known\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nreceived body\r\n").getBytes(UTF_8);
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void aTraversedOwnersUnrelatedLockPreservesUnchangedRecipientAndBodyAssociations() {
        final Email input = MAIL.converter().fromEml(INPUT).toEmail();
        final Email output = governance(Map.of("simplejavamail.locked.defaults.subject", "Known")).applyLocksToPreparedEmail(input);
        assertThat(output.getInspection().getPlainText()).isSameAs(input.getInspection().getPlainText());
        assertThat(output.getInspection().getToRecipients()).isSameAs(input.getInspection().getToRecipients());
        assertThat(output.getInspection().getReplyToRecipients()).isSameAs(input.getInspection().getReplyToRecipients());
        assertThat(output.getInspection().getSubject().getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
        assertThat(output.getInspection().getOriginalMessage()).isSameAs(input.getInspection().getOriginalMessage());
    }

    @Test
    void exactHeaderFactsStayAuthoritativeWhileTheLockedEnvelopeSenderIsCallerOwned() {
        final Email exact = MAIL.emailBuilder().startingFromExactEml(INPUT).withEnvelopeRecipients("relay@example.org").buildEmail();
        final Email prepared = governance(Map.of("simplejavamail.locked.defaults.subject", "Known",
                "simplejavamail.locked.defaults.bounceto.address", "policy-return@example.org")).prepareExactEmail(exact);
        assertThat(prepared.getInspection().getPlainText()).isSameAs(exact.getInspection().getPlainText());
        assertThat(prepared.getInspection().getToRecipients()).isSameAs(exact.getInspection().getToRecipients());
        assertThat(prepared.getInspection().getSubject()).isSameAs(exact.getInspection().getSubject());
        assertThat(prepared.getBounceToRecipient().getAddress()).isEqualTo("policy-return@example.org");
        assertThat(prepared.getInspection().getBounceToRecipient().getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
        assertThat(prepared.getHeaders().get("Return-Path")).containsExactly("<old-return@example.org>");
        assertThat(MAIL.converter().fromEmail(prepared).toEmlBytes()).containsExactly(INPUT);
    }

    private static EmailGovernanceImpl governance(final Map<String, String> properties) {
        final SimpleJavaMailConfig config = ConfigLoader.builder().withMap(properties).load();
        return new EmailGovernanceImpl(config, SimpleJavaMail.withConfig(config).emailBuilder(), null, null, null, null);
    }
}
