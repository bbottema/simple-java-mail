package org.simplejavamail.mailer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSender;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.mailer.internal.MailerImpl;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class MailerExecutionApiTest {

	@TempDir Path classes;

	@Test
	void viewsExposeExactlyTheApprovedAbstractOperations() throws Exception {
		assertThat(Mailer.class.getMethod("sync").getReturnType()).isEqualTo(Mailer.Sync.class);
		assertThat(Mailer.class.getMethod("async").getReturnType()).isEqualTo(Mailer.Async.class);
		for (Class<?> view : List.of(Mailer.Sync.class, Mailer.Async.class)) {
			assertThat(view.getDeclaredMethods()).hasSize(3).allSatisfy(method -> {
				assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
				assertThat(method.isDefault()).isFalse();
			});
			assertThat(view.getInterfaces()).isEmpty();
			assertThat(AutoCloseable.class.isAssignableFrom(view)).isFalse();
			assertThat(Mailer.class.isAssignableFrom(view)).isFalse();
			assertThat(MailSender.class.isAssignableFrom(view)).isFalse();
		}
		assertThat(Mailer.Sync.class.getMethod("sendMail", Email.class).getReturnType()).isEqualTo(MailSubmissionReceipt.class);
		assertThat(Mailer.Sync.class.getMethod("sendMailsInSimpleBatch", Iterable.class).getReturnType()).isEqualTo(void.class);
		assertThat(Mailer.Sync.class.getMethod("testConnection").getReturnType()).isEqualTo(void.class);
		assertGenericReturn(Mailer.Async.class.getMethod("sendMail", Email.class), MailSend.class, MailSubmissionReceipt.class);
		assertGenericReturn(Mailer.Async.class.getMethod("sendMailsInSimpleBatch", Iterable.class), MailSend.class, Void.class);
		assertGenericReturn(Mailer.Async.class.getMethod("testConnection"), CompletableFuture.class, Void.class);
		for (Class<?> owner : List.of(Mailer.class, MailerImpl.class)) {
			assertThat(owner.getMethods()).extracting(Method::getName).doesNotContain(
					"sendMail", "sendMailSync", "sendMailAsync", "sendMailAndGetReceipt", "sendMailAndGetReceiptSync",
					"sendMailAndGetReceiptAsync", "sendMailsInSimpleBatch", "testConnection");
		}
		assertThat(MailerGenericBuilder.class.getMethods()).extracting(Method::getName).doesNotContain("async", "isAsync");
		assertThat(OperationalConfig.class.getMethods()).extracting(Method::getName).doesNotContain("isAsync");
	}

	@Test
	void java11ConsumerCanIgnoreOrRetainReceiptsAndUseEveryOperation() throws Exception {
		assertCompilation(true,
				"Mailer.Sync sync = mailer.sync(); Mailer.Async async = mailer.async();"
				+ "sync.sendMail(email); MailSubmissionReceipt receipt = sync.sendMail(email);"
				+ "MailSend<MailSubmissionReceipt> send = async.sendMail(email); send.requestCancellation();"
				+ "CompletableFuture<MailSubmissionReceipt> completed = send.getCompletion();"
				+ "sync.sendMailsInSimpleBatch(emails); MailSend<Void> batch = async.sendMailsInSimpleBatch(emails);"
				+ "sync.testConnection(); CompletableFuture<Void> test = async.testConnection();");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"builder.async();", "builder.isAsync();", "mailer.getOperationalConfig().isAsync();",
			"mailer.sendMail(email);", "mailer.sendMail(email, true);", "mailer.sendMail(email, false);",
			"mailer.sendMailSync(email);", "mailer.sendMailAsync(email);",
			"mailer.sendMailAndGetReceipt(email);", "mailer.sendMailAndGetReceipt(email, true);",
			"mailer.sendMailAndGetReceiptSync(email);", "mailer.sendMailAndGetReceiptAsync(email);",
			"mailer.sendMailsInSimpleBatch(emails);", "mailer.sendMailsInSimpleBatch(emails, true);",
			"mailer.testConnection();", "mailer.testConnection(true);", "mailer.testConnection(false);",
			"mailer.sync().sendMailAndGetReceipt(email);", "mailer.async().sendMailAndGetReceipt(email);",
			"mailer.sync().sendMail(email, true);", "mailer.async().testConnection(true);",
			"MailSend<Void> send = mailer.async().sendMail(email);", "mailer.async().close();"
	})
	void removedApiRequiresAnExplicitMigration(final String statement) throws Exception {
		assertCompilation(false, statement);
	}

	private void assertCompilation(final boolean expectedSuccess, final String statements) throws Exception {
		final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("Consumer compilation requires a JDK").isNotNull();
		final String source = "import org.simplejavamail.api.mailer.*; import org.simplejavamail.api.email.Email;"
				+ "import java.util.concurrent.CompletableFuture; class ExecutionViewConsumer {"
				+ "void use(Mailer mailer, MailerRegularBuilder<?> builder, Email email, Iterable<Email> emails) {"
				+ statements + "}}";
		final SimpleJavaFileObject compilationUnit = new SimpleJavaFileObject(URI.create("string:///ExecutionViewConsumer.java"),
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

	private static void assertGenericReturn(final Method method, final Class<?> rawType, final Class<?> resultType) {
		final ParameterizedType returned = (ParameterizedType) method.getGenericReturnType();
		assertThat(returned.getRawType()).isEqualTo(rawType);
		assertThat(returned.getActualTypeArguments()).containsExactly(resultType);
	}
}
