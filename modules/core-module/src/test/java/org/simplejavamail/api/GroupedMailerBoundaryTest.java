package org.simplejavamail.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.mailer.MailerRegularBuilder;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroupedMailerBoundaryTest {
    @TempDir Path output;

    @Test
    void advancedMailerConfigurationCompilesAgainstCoreWithoutRuntimeImplementation() throws Exception {
        final String source = "import org.simplejavamail.api.mailer.*;\n"
                + "import org.simplejavamail.api.mailer.config.*;\n"
                + "import java.time.Duration;\n"
                + "class Consumer { void configure(MailerRegularBuilder<?> builder) {\n"
                + "builder.withSMTPServer(\"localhost\", 587).withConnectionPoolConfig(ConnectionPoolConfig.builder().withMaxSize(8).build())\n"
                + ".withAsyncExecutionConfig(AsyncExecutionConfig.builder().withQueueCapacity(100).waitForCapacity(Duration.ofSeconds(2)).build())\n"
                + ".withProxyConfig(SocksProxyConfig.builder().withHost(\"proxy\").withPort(1080).build())\n"
                + ".withSendingLimitsConfig(SendingLimitsConfig.builder().withMessageRateLimit(10, Duration.ofMinutes(1)).build())\n"
                + ".withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofSeconds(5)).build())\n"
                + ".withTlsConfig(TlsConfig.builder().verifyingServerIdentity(true).build())\n"
                + ".withValidationConfig(ValidationConfig.builder().withMaximumEmailSize(1024).build());\n"
                + "builder.withAsyncExecutionConfig(builder.getAsyncExecutionConfig().toBuilder().rejectWhenFull().build());\n"
                + "} }";
        assertThat(compiles(source)).isTrue();
    }

    @Test
    void movedScalarSettersAndCompanionsAreRemovedFromSupportedRootContracts() throws Exception {
        for (final String call : List.of("withConnectionPoolMaxSize(8)", "resetConnectionPoolMaxSize()", "getConnectionPoolMaxSize()",
                "withSessionTimeout(java.time.Duration.ofSeconds(1))", "getSessionTimeout()", "withThreadPoolSize(2)",
                "withAsyncQueueCapacity(10)", "withAsyncQueueOverflowPolicy(org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy.REJECT)",
                "withProxyHost(\"proxy\")", "getProxyPassword()", "trustingAllHosts(true)", "resetTrustingAllHosts()",
                "withCustomSSLFactoryClass(\"factory\")", "getCustomSSLFactory()", "withMaximumEmailSize(1024)",
                "clearEmailValidator()", "withMessageRateLimit(1, java.time.Duration.ofSeconds(1))")) {
            assertThat(compiles("class Consumer { void configure(org.simplejavamail.api.mailer.MailerRegularBuilder<?> builder) { builder."
                    + call + "; } }")).as(call).isFalse();
        }
        assertThat(MailerRegularBuilder.class.getMethods()).noneMatch(method -> method.toGenericString().contains(".internal."));
    }

    private boolean compiles(final String source) throws Exception {
        final Path file = output.resolve("Consumer.java");
        Files.writeString(file, source);
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            return compiler.getTask(null, files, diagnostics, List.of("--release", "11", "-proc:none", "-classpath",
                    System.getProperty("java.class.path"), "-d", output.toString()), null, files.getJavaFileObjects(file)).call();
        }
    }
}
