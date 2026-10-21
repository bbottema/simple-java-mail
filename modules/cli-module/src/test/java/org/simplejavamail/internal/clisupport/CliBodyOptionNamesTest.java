package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CliBodyOptionNamesTest {

    private static final Map<String, String> OPTION_METHODS = Map.of(
            "appendText", "appendPlainText", "prependText", "prependPlainText",
            "appendTextHTML", "appendHTMLText", "prependTextHTML", "prependHTMLText",
            "appendTextFromFile", "appendPlainText", "prependTextFromFile", "prependPlainText",
            "appendTextHTMLFromFile", "appendHTMLText", "prependTextHTMLFromFile", "prependHTMLText");

    @TempDir Path workingDirectory;

    @Test
    void generatedMetadataAndHelpRetainEveryExistingOptionSpelling() {
        final List<CliDeclaredOptionSpec> options = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(CliBuilderApi.roots());
        assertThat(options).extracting(CliDeclaredOptionSpec::getName).doesNotHaveDuplicates();
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), mock(MailerProvider.class))) {
            final CliExecutionResult generalHelp = execute(environment, "send", "--help");
            assertThat(generalHelp.exitCode()).as(generalHelp.stderr()).isZero();
            OPTION_METHODS.forEach((spelling, method) -> {
                final String optionName = "--email:" + spelling;
                assertThat(options).filteredOn(option -> option.getName().equals(optionName)).singleElement().satisfies(option -> {
                    assertThat(option.getSourceMethod().getName()).isEqualTo(method);
                    assertThat(option.getSourceMethod().getParameterTypes()).containsExactly(spelling.endsWith("FromFile")
                            ? java.io.File.class : String.class);
                });
                assertThat(generalHelp.stdout()).contains(optionName);
                final CliExecutionResult help = execute(environment, "send", optionName + "--help");
                assertThat(help.exitCode()).as(help.stderr()).isZero();
                assertThat(help.stdout()).contains(optionName);
            });
            assertThat(generalHelp.stdout()).doesNotContain("--email:appendPlainText", "--email:prependPlainText",
                    "--email:appendHTMLText", "--email:prependHTMLText");
        }
    }

    @Test
    void existingStringAndRequestRelativeFileOptionsBuildTheSubmittedBodiesInOrder() throws Exception {
        Files.writeString(workingDirectory.resolve("prefix.txt"), "pré-", StandardCharsets.UTF_8);
        Files.writeString(workingDirectory.resolve("suffix.txt"), "-後", StandardCharsets.UTF_8);
        final Mailer mailer = mock(Mailer.class);
        final Mailer.Sync sync = mock(Mailer.Sync.class);
        when(mailer.sync()).thenReturn(sync);
        final MailerProvider.Lease lease = mock(MailerProvider.Lease.class);
        when(lease.mailer()).thenReturn(mailer);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoader.builder().load(), (profile, factory) -> lease)) {
            final CliExecutionResult result = execute(environment, "send", "--email:startingBlank",
                    "--email:from", "sender@example.test", "--email:to", "receiver@example.test",
                    "--email:withPlainText", "middle", "--email:prependTextFromFile", "prefix.txt",
                    "--email:appendTextFromFile", "suffix.txt", "--email:prependText", "[", "--email:appendText", "]",
                    "--email:withHTMLText", "<p>middle</p>", "--email:prependTextHTMLFromFile", "prefix.txt",
                    "--email:appendTextHTMLFromFile", "suffix.txt", "--email:prependTextHTML", "<div>",
                    "--email:appendTextHTML", "</div>");
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            final ArgumentCaptor<Email> email = ArgumentCaptor.forClass(Email.class);
            verify(sync).sendMail(email.capture());
            assertThat(email.getValue().getPlainText()).isEqualTo("[pré-middle-後]");
            assertThat(email.getValue().getHTMLText()).isEqualTo("<div>pré-<p>middle</p>-後</div>");
            verify(lease).close();
        }
        Files.delete(workingDirectory.resolve("prefix.txt"));
        Files.delete(workingDirectory.resolve("suffix.txt"));
    }

    private CliExecutionResult execute(final CliExecutionEnvironment environment, final String... arguments) {
        return CliSupport.execute(arguments, workingDirectory, UUID.randomUUID(), environment, null);
    }
}
