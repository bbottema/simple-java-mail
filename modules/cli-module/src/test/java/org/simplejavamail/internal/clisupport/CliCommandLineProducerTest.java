package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.MAILER;

class CliCommandLineProducerTest {
    private static final String OPTION = "--mailer:sample";

    @Test
    void commandOptionsOwnTheirSpecsAndKeepOrdinaryAndDetailedHelpSeparate() throws Exception {
        final CommandLine tree = configure(option(), 120);
        final OptionSpec send = tree.getSubcommands().get("send").getCommandSpec().findOption(OPTION);
        assertThat(send.description()).containsExactly("Configure a sample server. (...more)");
        for (final String command : List.of("connect", "probe", "validate")) {
            final OptionSpec ordinary = tree.getSubcommands().get(command).getCommandSpec().findOption(OPTION);
            final OptionSpec detailed = tree.getSubcommands().get(command).getCommandSpec().findOption(OPTION + "--help");
            assertThat(ordinary).isNotSameAs(send).isNotSameAs(detailed);
            assertThat(ordinary.paramLabel()).isEqualTo("host(=String) [port(=Integer)]");
            assertThat(detailed.paramLabel()).isEqualTo(ordinary.paramLabel());
            assertThat(ordinary.description()).containsExactly("Configure a sample server. (...more)");
            assertThat(detailed.description()).contains("Configure a sample server.", "Additional sample details.");
        }
        assertThat(tree.getSubcommands().get("send").getCommandSpec().findOption(OPTION + "--help").description())
                .contains("Configure a sample server.", "Additional sample details.");
    }

    @Test
    void optionOccurrencesStayWithinTheirCommandAndTree() throws Exception {
        final CliDeclaredOptionSpec option = option();
        final CommandLine first = configure(option, 120);
        final CommandLine second = configure(option, 120);
        first.parseArgs("send", OPTION, "first.example.test", "25");
        second.parseArgs("connect", OPTION, "second.example.test", "587");
        assertThat(occurrences(first, "send")).singleElement()
                .satisfies(value -> assertThat(value.arguments()).containsExactly("first.example.test", "25"));
        assertThat(occurrences(second, "connect")).singleElement()
                .satisfies(value -> assertThat(value.arguments()).containsExactly("second.example.test", "587"));
        assertThat(occurrences(first, "connect")).isEmpty();
        assertThat(occurrences(second, "send")).isEmpty();
    }

    @Test
    void eachBuildUsesItsCurrentWidthAndDocumentationContext() throws Exception {
        final CliDeclaredOptionSpec option = option();
        final Method source = option.getSourceMethod();
        try (MockedStatic<TherapiJavadocHelper> documents = mockStatic(TherapiJavadocHelper.class)) {
            documents.when(TherapiJavadocHelper::beginGeneration).thenCallRealMethod();
            documents.when(() -> TherapiJavadocHelper.getJavadocSeeAlsoReferences(source, true, 80))
                    .thenReturn(List.of("Narrow reference"));
            documents.when(() -> TherapiJavadocHelper.getJavadocSeeAlsoReferences(source, true, 160))
                    .thenReturn(List.of("Wide reference"));
            final CommandLine narrow = configure(option, 80);
            final CommandLine wide = configure(option, 160);
            documents.when(() -> TherapiJavadocHelper.getJavadocSeeAlsoReferences(source, true, 80))
                    .thenReturn(List.of("Later documentation context"));
            final CommandLine later = configure(option, 80);
            for (final String command : List.of("send", "connect", "probe", "validate")) {
                assertThat(details(narrow, command)).contains("Narrow reference").doesNotContain("Wide reference", "Later documentation context");
                assertThat(details(wide, command)).contains("Wide reference").doesNotContain("Narrow reference", "Later documentation context");
                assertThat(details(later, command)).contains("Later documentation context").doesNotContain("Narrow reference", "Wide reference");
            }
        }
    }

    private static CliDeclaredOptionSpec option() throws Exception {
        return new CliDeclaredOptionSpec(OPTION, List.of("Configure a sample server.", "Additional sample details."),
                List.of(new CliDeclaredOptionValue("host", "String", "The host", true, new String[0]),
                        new CliDeclaredOptionValue("port", "Integer", "The port", false, new String[0])),
                MAILER, MailerRegularBuilder.class.getMethod("withSMTPServer", String.class, Integer.class));
    }

    private static CommandLine configure(final CliDeclaredOptionSpec option, final int width) {
        try (TherapiJavadocHelper.GenerationScope ignored = TherapiJavadocHelper.beginGeneration()) {
            return CliCommandLineProducer.configurePicoCli(List.of(option), width);
        }
    }

    private static List<CliOptionOccurrences.Occurrence> occurrences(final CommandLine tree, final String command) {
        return ((CliOptionOccurrences) tree.getSubcommands().get(command).getCommandSpec().userObject()).occurrences();
    }

    private static String[] details(final CommandLine tree, final String command) {
        return tree.getSubcommands().get(command).getCommandSpec().findOption(OPTION + "--help").description();
    }
}
