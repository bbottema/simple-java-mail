package org.simplejavamail.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientValidationWarningsApiTest {

    @TempDir Path classes;

    @Test
    void java11ConsumerUsesWarningNamesThroughCoreContracts() throws Exception {
        assertCompilation(true,
                "builder.withClientValidationWarningsOnly(true).resetClientValidationWarningsOnly()"
                + ".useConfiguredClientValidationWarningsOnly();"
                + "Boolean supplied = builder.getClientValidationWarningsOnly();"
                + "Boolean captured = value.getClientValidationWarningsOnly();"
                + "boolean effective = operational.isClientValidationWarningsOnly();"
                + "mailer.withValidationConfig(builder.withClientValidationWarningsOnly(false).build());");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "builder.disablingAllClientValidation(true);",
            "builder.getDisableAllClientValidation();",
            "builder.resetDisableAllClientValidations();",
            "builder.useConfiguredDisableAllClientValidation();",
            "value.getDisableAllClientValidation();",
            "operational.isDisableAllClientValidation();",
            "mailer.withClientValidationWarningsOnly(true);",
            "mailer.disablingAllClientValidation(true);"
    })
    void oldGroupedNamesAndScalarRootAliasesAreUnavailable(final String statement) throws Exception {
        assertCompilation(false, statement);
    }

    private void assertCompilation(final boolean expected, final String statements) throws Exception {
        final String source = "import org.simplejavamail.api.mailer.*; import org.simplejavamail.api.mailer.config.*;"
                + "class ValidationConsumer { void use(ValidationConfig.Builder builder, ValidationConfig value,"
                + "OperationalConfig operational, MailerRegularBuilder<?> mailer) {" + statements + "}}";
        final SimpleJavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///ValidationConsumer.java"),
                SimpleJavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(final boolean ignoreEncodingErrors) { return source; }
        };
        final StringWriter diagnostics = new StringWriter();
        try (StandardJavaFileManager files = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            final boolean compiled = ToolProvider.getSystemJavaCompiler().getTask(diagnostics, files, null,
                    List.of("--release", "11", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                    null, List.of(unit)).call();
            assertThat(compiled).as("%s%n%s", statements, diagnostics).isEqualTo(expected);
        }
    }
}
