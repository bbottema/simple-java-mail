package org.simplejavamail.internal.mailprovider.angus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AngusMailFromParametersTest {

    @ParameterizedTest
    @ValueSource(strings = {"sender@example.test", "\"name > SMTPUTF8\"@example.test", "\"name \\\" > SMTPUTF8\"@example.test", ""})
    void removesOnlyTheBareParameterNotSenderTextOrOtherParameters(final String sender) throws Exception {
        final String prefix = "MAIL FROM:<" + sender + ">";
        assertThat(new AngusMailFromParameters(false, null).applyToCommand(prefix + " SMTPUTF8 AUTH=name>value XTRACE=SMTPUTF8 REQUIRETLS"))
                .isEqualTo(prefix + " AUTH=name>value XTRACE=SMTPUTF8 REQUIRETLS");
    }

    @Test
    void retainsOneRequiredDeclarationAndPreservesOtherParameters() throws Exception {
        assertThat(new AngusMailFromParameters(true, "SMTPUTF8").applyToCommand("MAIL FROM:<sender@example.test> SMTPUTF8 RET=FULL smtputf8 ENVID=id"))
                .isEqualTo("MAIL FROM:<sender@example.test> SMTPUTF8 RET=FULL ENVID=id");
    }

    @Test
    void addsMissingDeclarationWithoutTouchingUnrelatedCommands() throws Exception {
        final AngusMailFromParameters parameters = new AngusMailFromParameters(true, null);
        assertThat(parameters.applyToCommand("MAIL FROM:<> BODY=8BITMIME")).isEqualTo("MAIL FROM:<> BODY=8BITMIME SMTPUTF8");
        assertThat(parameters.applyToCommand("RCPT TO:<SMTPUTF8@example.test>")).isEqualTo("RCPT TO:<SMTPUTF8@example.test>");
    }

    @Test
    void malformedSenderCannotCauseTheHookToRewriteTheAddress() {
        assertThatThrownBy(() -> new AngusMailFromParameters(false, null).applyToCommand("MAIL FROM:<\"unterminated@example.test> SMTPUTF8"))
                .hasMessageContaining("sender address has unmatched quotes or brackets");
    }
}
