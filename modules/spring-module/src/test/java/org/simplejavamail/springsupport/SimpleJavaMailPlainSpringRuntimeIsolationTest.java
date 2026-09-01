package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleJavaMailPlainSpringRuntimeIsolationTest {

	@Test
	void manualSpringConfigurationWorksWithoutSpringBootOnTheRuntimeClasspath() throws Exception {
		try (URLClassLoader plainSpringClassLoader = createPlainSpringClassLoader()) {
			final ClassLoader originalContextClassLoader = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(plainSpringClassLoader);
				verifyManualSpringContextWithoutBoot(plainSpringClassLoader);
			} finally {
				Thread.currentThread().setContextClassLoader(originalContextClassLoader);
			}
		}
	}

	private static void verifyManualSpringContextWithoutBoot(final ClassLoader plainSpringClassLoader) throws Exception {
		assertThatThrownBy(() -> plainSpringClassLoader.loadClass("org.springframework.boot.autoconfigure.AutoConfiguration"))
				.isInstanceOf(ClassNotFoundException.class);

		final Class<?> applicationContextType =
				plainSpringClassLoader.loadClass("org.springframework.context.annotation.AnnotationConfigApplicationContext");
		final Class<?> springSupportType = plainSpringClassLoader.loadClass(SimpleJavaMailSpringSupport.class.getName());
		final Class<?> simpleJavaMailType = plainSpringClassLoader.loadClass("org.simplejavamail.api.SimpleJavaMail");
		final Class<?> mailerType = plainSpringClassLoader.loadClass("org.simplejavamail.api.mailer.Mailer");
		final Object applicationContext = applicationContextType.getConstructor().newInstance();

		try {
			applicationContextType.getMethod("register", Class[].class)
					.invoke(applicationContext, (Object) new Class<?>[] { springSupportType });
			applicationContextType.getMethod("refresh").invoke(applicationContext);

			assertThat(applicationContextType.getMethod("getBean", Class.class).invoke(applicationContext, simpleJavaMailType)).isNotNull();
			assertThat(applicationContextType.getMethod("getBean", Class.class).invoke(applicationContext, mailerType)).isNotNull();
		} catch (InvocationTargetException invocationFailure) {
			final Throwable targetException = invocationFailure.getTargetException();
			if (targetException instanceof Exception) {
				throw (Exception) targetException;
			}
			if (targetException instanceof Error) {
				throw (Error) targetException;
			}
			throw new IllegalStateException("Manual Spring context failed", targetException);
		} finally {
			applicationContextType.getMethod("close").invoke(applicationContext);
		}
	}

	private static URLClassLoader createPlainSpringClassLoader() {
		final URL[] classpathWithoutBoot = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
				.filter(path -> !Paths.get(path).getFileName().toString().startsWith("spring-boot"))
				.map(SimpleJavaMailPlainSpringRuntimeIsolationTest::toUrl)
				.toArray(URL[]::new);
		return new URLClassLoader(classpathWithoutBoot, ClassLoader.getPlatformClassLoader());
	}

	private static URL toUrl(final String classpathEntry) {
		try {
			return Paths.get(classpathEntry).toUri().toURL();
		} catch (MalformedURLException malformedUrl) {
			throw new IllegalStateException("Invalid test classpath entry " + classpathEntry, malformedUrl);
		}
	}
}
