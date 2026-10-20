package org.simplejavamail.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Compile application code against core: wrong key/type pairs must fail even with a broad Object target type. */
class ConfigKeyCompilationTest {
    @TempDir Path classes;

    @Test
    void validKeyReadsAndFallbacksCompile() throws Exception {
        assertCompiles("Integer port = c.get(ConfigKeys.SMTP_PORT); "
                + "java.time.Duration timeout = c.get(ConfigKeys.SESSION_TIMEOUT); "
                + "Integer selected = c.valueOrProperty(2525, ConfigKeys.SMTP_PORT, 25); "
                + "Integer absent = c.valueOrProperty(null, ConfigKeys.SMTP_PORT); "
                + "java.util.Map<String, String> extras = c.get(ConfigKeys.EXTRA_PROPERTIES); "
                + "java.util.Map<String, SimpleJavaMailConfig> named = c.get(ConfigKeys.NAMED_MAILERS);");
    }

    @Test
    void unsafeUnreleasedGetterFamiliesAreRemoved() throws Exception {
        assertDoesNotCompile("String port = c.getProperty(ConfigLoader.Property.SMTP_PORT);");
        assertDoesNotCompile("String port = c.getStringProperty(ConfigLoader.Property.SMTP_PORT);");
        assertDoesNotCompile("Integer host = c.getIntegerProperty(ConfigLoader.Property.SMTP_HOST);");
        assertDoesNotCompile("Boolean host = c.getBooleanProperty(ConfigLoader.Property.SMTP_HOST);");
    }

    @Test
    void incorrectGetterTargetTypesDoNotCompile() throws Exception {
        assertDoesNotCompile("String port = c.get(ConfigKeys.SMTP_PORT);");
        assertDoesNotCompile("Integer timeout = c.get(ConfigKeys.SESSION_TIMEOUT);");
        assertDoesNotCompile("java.util.Map<String, Integer> extras = c.get(ConfigKeys.EXTRA_PROPERTIES);");
    }

    @Test
    void wrongExplicitAndFallbackTypesCannotInferObject() throws Exception {
        assertDoesNotCompile("Object bad = c.valueOrProperty(\"587\", ConfigKeys.SMTP_PORT);");
        assertDoesNotCompile("Object bad = c.valueOrProperty(\"587\", ConfigKeys.SMTP_PORT, 25);");
        assertDoesNotCompile("Object bad = c.valueOrProperty(587, ConfigKeys.SMTP_PORT, \"25\");");
        assertDoesNotCompile("Object bad = c.valueOrProperty(java.time.Duration.ZERO, ConfigKeys.SMTP_PORT);");
        assertDoesNotCompile("Object bad = c.<Object>valueOrProperty(587, ConfigKeys.SMTP_PORT, \"25\");");
    }

    @Test
    void applicationsCannotConstructOrSubclassKeys() throws Exception {
        assertDoesNotCompile("ConfigKey<String> forged = new ConfigKey<>(ConfigLoader.Property.SMTP_PORT);");
        assertDoesNotCompile("ConfigKey<String> forged = new ConfigKey<String>(null) {};");
    }

    private void assertCompiles(final String body) throws Exception { compile(body, true); }
    private void assertDoesNotCompile(final String body) throws Exception { compile(body, false); }

    private void compile(final String body, final boolean expected) throws Exception {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        final JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///keyconsumer/Use.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
                return "package keyconsumer; import org.simplejavamail.config.*; "
                        + "class Use { void use(SimpleJavaMailConfig c) { " + body + " } }";
            }
        };
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            final boolean actual = compiler.getTask(null, files, diagnostics,
                    List.of("--release", "11", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                    null, List.of(source)).call();
            assertThat(actual).as("%s%n%s", body, diagnostics.getDiagnostics()).isEqualTo(expected);
        }
    }
}
