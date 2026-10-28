package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CliReplyHelpTest {

    @Test
    void bundledReplyHelpExplainsInitialGenerationAndIndependentEditing() throws Exception {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), provider)) {
            final CliExecutionResult result = CliSupport.execute(new String[] {"send", "--email:replyingTo--help"},
                    Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stdout().replaceAll("\\s+", " "))
                    .contains("generated once", "Script and style", "Later body changes remain independent");
            assertThat(result.stderr()).isEmpty();
            verifyNoInteractions(provider);
        }
    }
}
