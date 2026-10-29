package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class CliCalendarHelpTest {

    @Test
    void calendarHelpExplainsRequiredReplacementInputsAndExplicitRemoval() {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final PrintStream originalOut = System.out;
        try (PrintStream capturedOut = new PrintStream(output)) {
            System.setOut(capturedOut);
            CliSupport.runCLI(new String[]{"send", "--email:withCalendarText--help"});
        } finally {
            System.setOut(originalOut);
        }

        assertThat(new String(output.toByteArray(), UTF_8).replaceAll("\\s+", " "))
                .contains("Both arguments are required")
                .contains("without changing the earlier calendar choice")
                .contains("clearCalendarText")
                .contains("does not parse it");
    }
}
