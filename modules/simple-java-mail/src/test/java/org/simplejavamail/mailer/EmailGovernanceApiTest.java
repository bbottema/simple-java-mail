package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailGovernanceApiTest {

	@TempDir Path classes;

	@Test
	void java11ConsumerUsesTheEmailApiForSharedSigningPolicy() throws Exception {
		assertCompilation(true,
				"Email defaults = mail.emailBuilder().startingBlank().signWithDomainKey(dkim).buildEmail();"
				+ "builder.withEmailDefaults(defaults).withEmailOverrides(defaults);"
				+ "Email configured = mail.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides();"
				+ "builder.withEmailDefaults(mail.emailBuilder().copying(configured).clearDkim().buildEmail());"
				+ "builder.clearEmailDefaults();");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"builder.withDefaultDkimSigning(dkim);",
			"builder.withDefaultDkimSigning(new byte[]{1}, \"example.org\", \"selector\", null);",
			"builder.clearDefaultDkimSigning();",
			"builder.getDefaultDkimSigningConfig();",
			"builder.isDefaultDkimSigningConfigured();"
	})
	void removedDkimMailerApiRequiresMigration(final String statement) throws Exception {
		assertCompilation(false, statement);
	}

	private void assertCompilation(final boolean expectedSuccess, final String statements) throws Exception {
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("Consumer compilation requires a JDK").isNotNull();
		final String source = "import org.simplejavamail.api.SimpleJavaMail; import org.simplejavamail.api.mailer.MailerRegularBuilder;"
				+ "import org.simplejavamail.api.email.Email; import org.simplejavamail.api.email.config.DkimConfig;"
				+ "class GovernanceConsumer { void use(SimpleJavaMail mail, MailerRegularBuilder<?> builder, DkimConfig dkim) {"
				+ statements + "}}";
		final SimpleJavaFileObject compilationUnit = new SimpleJavaFileObject(URI.create("string:///GovernanceConsumer.java"),
				SimpleJavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
				return source;
			}
		};
		final StringWriter diagnostics = new StringWriter();
		try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
			final boolean compiled = compiler.getTask(diagnostics, files, null,
					List.of("--release", "11", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
					null, List.of(compilationUnit)).call();
			assertThat(compiled).as("%s%n%s", statements, diagnostics).isEqualTo(expectedSuccess);
		}
	}
}
