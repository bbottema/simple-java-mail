package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class CliEnvelopeValidationHelpTest {

    @Test
    void rawHeaderHelpExplainsItsMailboxValidationEscapeAndRemainingProtections() {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final PrintStream originalOut = System.out;
        try (PrintStream capturedOut = new PrintStream(output)) {
            System.setOut(capturedOut);
            CliSupport.runCLI(new String[]{"send", "--email:withHeader--help"});
        } finally {
            System.setOut(originalOut);
        }

        assertThat(new String(output.toByteArray(), UTF_8).replaceAll("\\s+", " "))
                .contains("expert escape from typed mailbox validation")
                .contains("injection scanning")
                .contains("mandatory configuration locks");
    }
}
