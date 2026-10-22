package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.config.ConfigLoader;
import org.springframework.context.annotation.Configuration;

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
				verifyManualSpringContextWithoutBoot(plainSpringClassLoader, false, false);
			} finally {
				Thread.currentThread().setContextClassLoader(originalContextClassLoader);
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void annotationHonorsClasspathPropertiesWithoutBootOrTelemetryOnTheClasspath(final boolean enabled) throws Exception {
		try (URLClassLoader plainSpringClassLoader = createPlainSpringClassLoader(enabled ? "telemetry-enabled.properties" : "telemetry-disabled.properties")) {
			assertThatThrownBy(() -> plainSpringClassLoader.loadClass("io.opentelemetry.api.OpenTelemetry"))
					.isInstanceOf(ClassNotFoundException.class);
			assertThatThrownBy(() -> plainSpringClassLoader.loadClass("org.simplejavamail.internal.telemetry.otel.OpenTelemetrySupport"))
					.isInstanceOf(ClassNotFoundException.class);
			final ClassLoader original = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(plainSpringClassLoader);
				if (enabled) {
					assertThatThrownBy(() -> verifyManualSpringContextWithoutBoot(plainSpringClassLoader, true, true))
							.hasStackTraceContaining("OpenTelemetry module not found");
				} else {
					verifyManualSpringContextWithoutBoot(plainSpringClassLoader, true, false);
				}
			} finally {
				Thread.currentThread().setContextClassLoader(original);
			}
		}
	}

	private static void verifyManualSpringContextWithoutBoot(final ClassLoader plainSpringClassLoader, final boolean annotated,
			final boolean expectedActivation) throws Exception {
		assertThatThrownBy(() -> plainSpringClassLoader.loadClass("org.springframework.boot.autoconfigure.AutoConfiguration"))
				.isInstanceOf(ClassNotFoundException.class);

		final Class<?> applicationContextType =
				plainSpringClassLoader.loadClass("org.springframework.context.annotation.AnnotationConfigApplicationContext");
		final Class<?> springSupportType = plainSpringClassLoader.loadClass(SimpleJavaMailSpringSupport.class.getName());
		final Class<?> simpleJavaMailType = plainSpringClassLoader.loadClass("org.simplejavamail.api.SimpleJavaMail");
		final Class<?> mailerType = plainSpringClassLoader.loadClass("org.simplejavamail.api.mailer.Mailer");
		final Object applicationContext = applicationContextType.getConstructor().newInstance();

		try {
			if (annotated) {
				applicationContextType.getMethod("register", Class[].class).invoke(applicationContext,
						(Object) new Class<?>[] { plainSpringClassLoader.loadClass(AnnotatedTelemetry.class.getName()) });
			}
			applicationContextType.getMethod("register", Class[].class)
					.invoke(applicationContext, (Object) new Class<?>[] { springSupportType });
			applicationContextType.getMethod("refresh").invoke(applicationContext);

			assertThat(applicationContextType.getMethod("getBean", Class.class).invoke(applicationContext, simpleJavaMailType)).isNotNull();
			assertThat(applicationContextType.getMethod("getBean", Class.class).invoke(applicationContext, mailerType)).isNotNull();
			if (annotated) {
				final Class<?> configType = plainSpringClassLoader.loadClass("org.simplejavamail.config.SimpleJavaMailConfig");
				final Class<?> configKeyType = plainSpringClassLoader.loadClass("org.simplejavamail.config.ConfigKey");
				final Object enabledKey = plainSpringClassLoader.loadClass("org.simplejavamail.config.ConfigKeys").getField("TELEMETRY_ENABLED").get(null);
				final Object config = applicationContextType.getMethod("getBean", Class.class).invoke(applicationContext, configType);
				assertThat(configType.getMethod("get", configKeyType).invoke(config, enabledKey)).isEqualTo(expectedActivation);
			}
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
		return createPlainSpringClassLoader(null);
	}

	private static URLClassLoader createPlainSpringClassLoader(final String classpathProperties) {
		final URL[] classpathWithoutBoot = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
				.filter(path -> !Paths.get(path).getFileName().toString().startsWith("spring-boot"))
				.filter(path -> !Paths.get(path).getFileName().toString().startsWith("open-telemetry-module"))
				.filter(path -> !Paths.get(path).getFileName().toString().startsWith("opentelemetry-"))
				.map(SimpleJavaMailPlainSpringRuntimeIsolationTest::toUrl)
				.toArray(URL[]::new);
		return new URLClassLoader(classpathWithoutBoot, ClassLoader.getPlatformClassLoader()) {
			@Override
			public URL getResource(final String name) {
				return classpathProperties != null && ConfigLoader.DEFAULT_CONFIG_FILENAME.equals(name)
						? findResource(classpathProperties) : super.getResource(name);
			}
		};
	}

	private static URL toUrl(final String classpathEntry) {
		try {
			return Paths.get(classpathEntry).toUri().toURL();
		} catch (MalformedURLException malformedUrl) {
			throw new IllegalStateException("Invalid test classpath entry " + classpathEntry, malformedUrl);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@EnableSjmTelemetry
	static class AnnotatedTelemetry {
	}
}
