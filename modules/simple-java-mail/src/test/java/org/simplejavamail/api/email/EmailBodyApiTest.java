package org.simplejavamail.api.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import testutil.ConfigLoaderTestHelper;

import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailBodyApiTest {

    @TempDir Path temporaryDirectory;

    @Test
    void java11ConsumerUsesConsistentBodyNamesForEveryInputRepresentation() throws Exception {
        assertCompilation(true,
                "builder.withPlainText(text).appendPlainText(text).prependPlainText(text)"
                + ".appendPlainText(file).prependPlainText(file).appendPlainText(path).prependPlainText(path);"
                + "builder.withHTMLText(text).appendHTMLText(text).prependHTMLText(text)"
                + ".appendHTMLText(file).prependHTMLText(file).appendHTMLText(path).prependHTMLText(path);"
                + "builder.withCalendarText(CalendarMethod.REQUEST, text);"
                + "String plain = builder.getPlainText(); String html = builder.getHTMLText(); String calendar = builder.getCalendarText();"
                + "builder.clearPlainText().clearHTMLText();");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "builder.appendText(text);", "builder.prependText(text);",
            "builder.appendText(file);", "builder.prependText(file);",
            "builder.appendText(path);", "builder.prependText(path);",
            "builder.appendTextHTML(text);", "builder.prependTextHTML(text);",
            "builder.appendTextHTML(file);", "builder.prependTextHTML(file);",
            "builder.appendTextHTML(path);", "builder.prependTextHTML(path);",
            "builder.getText();", "builder.getTextHTML();", "builder.getTextCalendar();"
    })
    void oldBodyMethodNamesRequireMigration(final String statement) throws Exception {
        assertCompilation(false, statement);
    }

    @Test
    void stringAndFileBodiesPreserveOrderUtf8AbsenceAndGetterValues() throws Exception {
        final Path prefix = Files.writeString(temporaryDirectory.resolve("prefix.txt"), "pré-", StandardCharsets.UTF_8);
        final Path suffix = Files.writeString(temporaryDirectory.resolve("suffix.txt"), "-後", StandardCharsets.UTF_8);
        final EmailPopulatingBuilder builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank();
        assertThat(builder.getPlainText()).isNull();
        assertThat(builder.getHTMLText()).isNull();
        assertThat(builder.getCalendarText()).isNull();

        builder.appendPlainText("middle").prependPlainText(prefix.toFile()).appendPlainText(suffix.toFile())
                .prependPlainText("[").appendPlainText("]")
                .prependHTMLText("<p>middle</p>").prependHTMLText(prefix.toFile()).appendHTMLText(suffix.toFile())
                .prependHTMLText("<div>").appendHTMLText("</div>")
                .withCalendarText(CalendarMethod.REQUEST, "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
        assertThat(builder.getPlainText()).isEqualTo("[pré-middle-後]");
        assertThat(builder.getHTMLText()).isEqualTo("<div>pré-<p>middle</p>-後</div>");
        assertThat(builder.getCalendarText()).isEqualTo("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");

        final Email email = builder.buildEmail();
        assertThat(email.getPlainText()).isEqualTo(builder.getPlainText());
        assertThat(email.getHTMLText()).isEqualTo(builder.getHTMLText());
        assertThat(email.getCalendarText()).isEqualTo(builder.getCalendarText());
        final EmailPopulatingBuilder copy = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().copying(email);
        assertThat(copy.getPlainText()).isEqualTo(builder.getPlainText());
        assertThat(copy.getHTMLText()).isEqualTo(builder.getHTMLText());
        assertThat(copy.getCalendarText()).isEqualTo(builder.getCalendarText());

        builder.withPlainText((String) null).withHTMLText((String) null);
        assertThat(builder.getPlainText()).isNull();
        assertThat(builder.getHTMLText()).isNull();
        assertThat(builder.getCalendarText()).isEqualTo(email.getCalendarText());
        Files.delete(prefix);
        Files.delete(suffix);
    }

    @ParameterizedTest(name = "{0} rejects a missing fragment before changing the draft")
    @MethodSource("bodyAugmentations")
    void rejectsNullFragmentsWithoutChangingExistingOrAbsentBodies(final String name,
            final BiConsumer<EmailPopulatingBuilder, String> augment, final boolean html) {
        for (boolean populated : new boolean[]{false, true}) {
            final EmailPopulatingBuilder builder = bodyDraft(populated);
            final Email before = builder.buildEmail();

            assertThatThrownBy(() -> augment.accept(builder, null)).isInstanceOf(NullPointerException.class);

            assertThat(builder.getPlainText()).isEqualTo(before.getPlainText());
            assertThat(builder.getHTMLText()).isEqualTo(before.getHTMLText());
            assertThat(builder.getCalendarMethod()).isEqualTo(CalendarMethod.REQUEST);
            assertThat(builder.getCalendarText()).isEqualTo(before.getCalendarText());
            final Email after = builder.buildEmail();
            assertThat(after.getPlainText()).isEqualTo(before.getPlainText());
            assertThat(after.getHTMLText()).isEqualTo(before.getHTMLText());
            assertThat(before.getPlainText()).isEqualTo(populated ? "plain" : null);
            assertThat(before.getHTMLText()).isEqualTo(populated ? "<p>html</p>" : null);
        }
    }

    @ParameterizedTest(name = "{0} retains its empty-fragment behavior")
    @MethodSource("bodyAugmentations")
    void emptyFragmentsRemainValidWithoutChangingOtherBodyChoices(final String name,
            final BiConsumer<EmailPopulatingBuilder, String> augment, final boolean html) {
        for (boolean populated : new boolean[]{false, true}) {
            final EmailPopulatingBuilder builder = bodyDraft(populated);
            final Email before = builder.buildEmail();

            augment.accept(builder, "");

            assertThat(builder.getPlainText()).isEqualTo(populated ? "plain" : html ? null : "");
            assertThat(builder.getHTMLText()).isEqualTo(populated ? "<p>html</p>" : html ? "" : null);
            assertThat(builder.getCalendarText()).isEqualTo(before.getCalendarText());
            assertThat(before.getPlainText()).isEqualTo(populated ? "plain" : null);
            assertThat(before.getHTMLText()).isEqualTo(populated ? "<p>html</p>" : null);
        }
    }

    private static Stream<Arguments> bodyAugmentations() {
        return Stream.of(
                Arguments.of("appendPlainText", (BiConsumer<EmailPopulatingBuilder, String>) EmailPopulatingBuilder::appendPlainText, false),
                Arguments.of("prependPlainText", (BiConsumer<EmailPopulatingBuilder, String>) EmailPopulatingBuilder::prependPlainText, false),
                Arguments.of("appendHTMLText", (BiConsumer<EmailPopulatingBuilder, String>) EmailPopulatingBuilder::appendHTMLText, true),
                Arguments.of("prependHTMLText", (BiConsumer<EmailPopulatingBuilder, String>) EmailPopulatingBuilder::prependHTMLText, true));
    }

    private static EmailPopulatingBuilder bodyDraft(final boolean populated) {
        final EmailPopulatingBuilder builder = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
                .withCalendarText(CalendarMethod.REQUEST, "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
        if (populated) {
            builder.withPlainText("plain").withHTMLText("<p>html</p>");
        }
        return builder;
    }

    private void assertCompilation(final boolean expected, final String statements) throws Exception {
        final String source = "import org.simplejavamail.api.email.*; import java.io.File; import java.nio.file.Path;"
                + "class BodyConsumer { void use(EmailPopulatingBuilder builder, String text, File file, Path path) {" + statements + "}}";
        final SimpleJavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///BodyConsumer.java"),
                SimpleJavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(final boolean ignoreEncodingErrors) { return source; }
        };
        final StringWriter diagnostics = new StringWriter();
        try (StandardJavaFileManager files = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            final boolean compiled = ToolProvider.getSystemJavaCompiler().getTask(diagnostics, files, null,
                    List.of("--release", "11", "-proc:none", "-classpath", System.getProperty("java.class.path"),
                            "-d", temporaryDirectory.toString()), null, List.of(unit)).call();
            assertThat(compiled).as("%s%n%s", statements, diagnostics).isEqualTo(expected);
        }
    }
}
