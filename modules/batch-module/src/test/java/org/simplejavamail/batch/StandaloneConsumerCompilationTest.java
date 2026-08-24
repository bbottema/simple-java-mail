package org.simplejavamail.batch;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class StandaloneConsumerCompilationTest {

	@Test
	void exposesStableAutomaticModuleName() throws Exception {
		Path classesDirectory = Paths.get(BatchTransportExecutor.class.getProtectionDomain()
				.getCodeSource().getLocation().toURI());
		Path manifestPath = classesDirectory.resolve(Paths.get("META-INF", "MANIFEST.MF"));
		try (InputStream manifestInput = Files.newInputStream(manifestPath)) {
			assertThat(new Manifest(manifestInput).getMainAttributes().getValue("Automatic-Module-Name"))
					.isEqualTo("org.simplejavamail.batch");
		}
	}

	@Test
	void publicOnlyJava11ConsumerCompilesAndRunsWithoutSimpleJavaMailFacade() throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertThat(compiler).as("A JDK is required to run the standalone consumer test").isNotNull();
		String classPath = System.getProperty("java.class.path");
		assertThat(Arrays.stream(classPath.split(File.pathSeparator))
				.anyMatch(StandaloneConsumerCompilationTest::isFacadeClasspathEntry))
				.as("the consumer classpath must not contain the simple-java-mail facade artifact")
				.isFalse();

		Path source = Paths.get("src", "test", "resources", "standalone-consumer", "StandaloneBatchConsumer.java");
		Path output = Paths.get("target", "standalone-consumer-classes");
		Files.createDirectories(output);
		StringWriter compilerOutput = new StringWriter();

		try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
			Boolean compiled = compiler.getTask(compilerOutput, fileManager, null,
					Arrays.asList("--release", "11", "-classpath", classPath, "-d", output.toString()),
					null, fileManager.getJavaFileObjects(source.toFile())).call();
			assertThat(compiled).withFailMessage(compilerOutput.toString()).isTrue();
		}

		try (URLClassLoader consumerLoader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
			Class<?> consumer = Class.forName("StandaloneBatchConsumer", true, consumerLoader);
			Method main = consumer.getMethod("main", String[].class);
			main.invoke(null, (Object) new String[0]);
		}
	}

	private static boolean isFacadeClasspathEntry(final String path) {
		final String normalized = path.replace('\\', '/');
		return normalized.matches(".*/simple-java-mail-[^/]+\\.jar")
				|| normalized.endsWith("/modules/simple-java-mail/target/classes");
	}
}
