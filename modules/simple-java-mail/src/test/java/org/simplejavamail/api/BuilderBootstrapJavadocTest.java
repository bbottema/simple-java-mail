package org.simplejavamail.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BuilderBootstrapJavadocTest {

	@TempDir Path classes;

	@ParameterizedTest
	@ValueSource(strings = {
			"email/EmailStartingBuilder", "email/EmailPopulatingBuilder", "mailer/MailerRegularBuilder", "mailer/MailerFromSessionBuilder"
	})
	void publishedBuilderIntroductionContainsACompilableBootstrapExample(final String builder) throws IOException {
		final String source = Files.readString(coreApiSource().resolve(builder + ".java"));
		final String introduction = source.substring(0, source.indexOf("public interface"));
		final Matcher example = Pattern.compile("\\{@code (SimpleJavaMail\\.[^}]+)}").matcher(introduction);
		assertThat(example.find()).as("%s must show how to obtain its builder", builder).isTrue();

		assertCompiles("Object builder = " + example.group(1) + ";");
	}

	@Test
	void configuredFactoryRemainsAValidAlternativeToTheConventionalBootstrap() throws IOException {
		assertCompiles("SimpleJavaMail mail = SimpleJavaMail.withConfig(config);"
				+ "EmailStartingBuilder email = mail.emailBuilder();"
				+ "MailerRegularBuilder<?> regular = mail.mailerBuilder();"
				+ "MailerFromSessionBuilder<?> supplied = mail.mailerBuilder(session);");
	}

	private Path coreApiSource() {
		final Path workingDirectory = Path.of("").toAbsolutePath();
		final Path coreModule = Files.isDirectory(workingDirectory.resolve("modules/core-module"))
				? workingDirectory.resolve("modules/core-module")
				: workingDirectory.resolveSibling("core-module");
		return coreModule.resolve("src/main/java/org/simplejavamail/api");
	}

	private void assertCompiles(final String statements) throws IOException {
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("Compiling public examples requires a JDK").isNotNull();
		final String source = "import org.simplejavamail.api.SimpleJavaMail; import org.simplejavamail.api.email.*;"
				+ "import org.simplejavamail.api.mailer.*; import org.simplejavamail.config.SimpleJavaMailConfig; import jakarta.mail.Session;"
				+ "class BuilderBootstrapConsumer { void use(SimpleJavaMailConfig config, Session session) {" + statements + "}}";
		final SimpleJavaFileObject compilationUnit = new SimpleJavaFileObject(URI.create("string:///BuilderBootstrapConsumer.java"),
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
			assertThat(compiled).as("Public builder example must compile: %s%n%s", statements, diagnostics).isTrue();
		}
	}
}
