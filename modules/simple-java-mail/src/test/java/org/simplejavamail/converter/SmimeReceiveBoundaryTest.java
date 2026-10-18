package org.simplejavamail.converter;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmimeReceiveBoundaryTest {

	private static final String EML = "From: sender@example.org\r\nSubject: readable without S/MIME\r\n\r\nPlain content";

	@TestFactory
	Stream<DynamicTest> explicitDecryptionRequiresItsModuleOnEveryImportRoute() {
		return Stream.of("eml", "mime", "outlook").flatMap(source -> Stream.of(false, true).map(fluent ->
				DynamicTest.dynamicTest(source + (fluent ? " fluent" : " static"), () -> {
					try (WithoutSmime runtime = runtime()) {
						assertThatThrownBy(() -> importMail(runtime, source, fluent, true))
								.hasMessageContaining("S/MIME").hasMessageContaining("smime-module");
					}
				})));
	}

	@TestFactory
	Stream<DynamicTest> ordinaryInspectionStillWorksWithoutTheModule() {
		return Stream.of("eml", "mime", "outlook").map(source -> DynamicTest.dynamicTest(source, () -> {
			try (WithoutSmime runtime = runtime()) {
				assertThat(importMail(runtime, source, true, false)).isNotNull();
			}
		}));
	}

	@TestFactory
	Stream<DynamicTest> clearingTheExplicitRequestRestoresOrdinaryInspection() {
		return Stream.of("eml", "mime", "outlook").map(source -> DynamicTest.dynamicTest(source, () -> {
			try (WithoutSmime runtime = runtime()) {
				assertThat(importMail(runtime, source, true, true, true)).isNotNull();
			}
		}));
	}

	private static Object importMail(final WithoutSmime runtime, final String source, final boolean fluent, final boolean decrypt) throws Exception {
		return importMail(runtime, source, fluent, decrypt, false);
	}

	private static Object importMail(final WithoutSmime runtime, final String source, final boolean fluent, final boolean decrypt,
			final boolean clearDecryption) throws Exception {
		final Thread caller = Thread.currentThread();
		final ClassLoader previous = caller.getContextClassLoader();
		try {
			caller.setContextClassLoader(runtime);
			final Class<?> keysType = runtime.loadClass("org.simplejavamail.api.mailer.config.Pkcs12Config");
			final Object keys = decrypt ? keys(keysType) : null;
			return fluent ? fluentImport(runtime, keysType, keys, source, clearDecryption) : staticImport(runtime, keysType, keys, source);
		} catch (InvocationTargetException failure) {
			final Throwable cause = failure.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			throw failure;
		} finally {
			caller.setContextClassLoader(previous);
		}
	}

	private static Object keys(final Class<?> keysType) throws Exception {
		final Object builder = keysType.getMethod("builder").invoke(null);
		builder.getClass().getMethod("pkcs12Store", byte[].class).invoke(builder, new byte[] {1});
		builder.getClass().getMethod("storePassword", String.class).invoke(builder, "unused");
		builder.getClass().getMethod("keyAlias", String.class).invoke(builder, "unused");
		builder.getClass().getMethod("keyPassword", String.class).invoke(builder, "unused");
		return builder.getClass().getMethod("build").invoke(builder);
	}

	private static Object fluentImport(final WithoutSmime runtime, final Class<?> keysType, final Object keys, final String source,
			final boolean clearDecryption) throws Exception {
		final Class<?> factoryType = runtime.loadClass("org.simplejavamail.api.SimpleJavaMail");
		final Object factory = factoryType.getMethod("fromDefaults").invoke(null);
		final Object converter = factoryType.getMethod("converter").invoke(factory);
		final Class<?> converterType = runtime.loadClass("org.simplejavamail.api.converter.ConfiguredEmailConverter");
		final Object stage;
		final Class<?> stageType;
		if ("outlook".equals(source)) {
			stage = converterType.getMethod("fromOutlookMsg", File.class).invoke(converter, outlook());
			stageType = runtime.loadClass("org.simplejavamail.api.converter.ConfiguredEmailConverter$OutlookConversion");
		} else if ("mime".equals(source)) {
			final Class<?> mimeType = runtime.loadClass("jakarta.mail.internet.MimeMessage");
			stage = converterType.getMethod("fromMimeMessage", mimeType).invoke(converter, mime(runtime));
			stageType = runtime.loadClass("org.simplejavamail.api.converter.ConfiguredEmailConverter$MimeMessageConversion");
		} else {
			stage = converterType.getMethod("fromEml", String.class).invoke(converter, EML);
			stageType = runtime.loadClass("org.simplejavamail.api.converter.ConfiguredEmailConverter$EmlConversion");
		}
		if (keys != null) {
			stageType.getMethod("withSmimeDecryption", keysType).invoke(stage, keys);
		}
		if (clearDecryption) {
			stageType.getMethod("clearSmimeDecryption").invoke(stage);
		}
		return stageType.getMethod("toEmail").invoke(stage);
	}

	private static Object staticImport(final WithoutSmime runtime, final Class<?> keysType, final Object keys, final String source) throws Exception {
		final Class<?> converter = runtime.loadClass("org.simplejavamail.converter.EmailConverter");
		if ("outlook".equals(source)) {
			return converter.getMethod("outlookMsgToEmail", File.class, keysType).invoke(null, outlook(), keys);
		}
		if ("mime".equals(source)) {
			return converter.getMethod("mimeMessageToEmail", runtime.loadClass("jakarta.mail.internet.MimeMessage"), keysType)
					.invoke(null, mime(runtime), keys);
		}
		return converter.getMethod("emlToEmail", String.class, keysType).invoke(null, EML, keys);
	}

	private static Object mime(final WithoutSmime runtime) throws Exception {
		return runtime.loadClass("org.simplejavamail.converter.EmailConverter").getMethod("emlToMimeMessage", String.class).invoke(null, EML);
	}

	private static File outlook() {
		return Path.of(determineResourceFolder("simple-java-mail"), "test/resources/test-messages/simple email with TO and CC.msg").toFile();
	}

	private static WithoutSmime runtime() {
		final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		final URL[] urls = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator))).map(entry -> {
			try {
				return new File(entry).toURI().toURL();
			} catch (Exception failure) {
				throw new IllegalStateException(failure);
			}
		}).toArray(URL[]::new);
		return new WithoutSmime(urls);
	}

	private static final class WithoutSmime extends URLClassLoader {
		private WithoutSmime(final URL[] urls) {
			super(urls, ClassLoader.getPlatformClassLoader());
		}

		@Override
		protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("org.simplejavamail.internal.smimesupport.")) {
				throw new ClassNotFoundException("S/MIME deliberately absent from this isolated application: " + name);
			}
			return super.loadClass(name, resolve);
		}
	}
}
