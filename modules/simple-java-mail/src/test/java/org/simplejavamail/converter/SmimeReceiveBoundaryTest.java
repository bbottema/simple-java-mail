package org.simplejavamail.converter;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
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
		return Stream.of("eml", "mime", "outlook").flatMap(source -> Stream.of(false, true).map(fluent ->
				DynamicTest.dynamicTest(source + (fluent ? " fluent" : " static"), () -> {
			try (WithoutSmime runtime = runtime()) {
				final Object email = importMail(runtime, source, fluent, false);
				final Object family = details(runtime, email);
				assertThat(read(runtime, family, "isInspectionComplete")).isEqualTo(true);
				assertThat((java.util.List<?>) read(runtime, family, "getSignatures")).isEmpty();
				assertThat((java.util.List<?>) read(runtime, family, "getDecryptions")).isEmpty();
			}
		})));
	}

	@Test
	void encryptedEmlWithoutTheModuleRetainsUnavailableProcessingAndExactOriginal() throws Exception {
		final byte[] bytes = Files.readAllBytes(protectedFixture("encrypted.eml"));
		try (WithoutSmime runtime = runtime()) {
			final Object email = importProtectedEml(runtime, bytes);
			assertUnavailable(runtime, email, false);
			assertThat(original(runtime, email)).containsExactly(bytes);
		}
	}

	@TestFactory
	Stream<DynamicTest> protectedImportsReportUnavailableInspectionOnEveryRoute() {
		return Stream.of("eml", "mime", "outlook").flatMap(source -> Stream.of(false, true).flatMap(fluent ->
				Stream.of("signed", "encrypted").map(protection -> DynamicTest.dynamicTest(source + " " + protection
						+ (fluent ? " fluent" : " static"), () -> {
			try (WithoutSmime runtime = runtime()) {
				final Object email = importMail(runtime, source, fluent, false, false, protection);
				assertUnavailable(runtime, email, protection.equals("signed"));
				if (source.equals("mime")) {
					assertThat(original(runtime, email)).as("a MIME object is not captured file input").isNull();
				} else {
					assertThat(original(runtime, email)).containsExactly(Files.readAllBytes(protectedFixture(
							protection + (source.equals("outlook") ? ".msg" : ".eml"))));
				}
			}
		}))));
	}

	@TestFactory
	Stream<DynamicTest> ambiguousAndAliasedDeclarationsKeepHonestFacts() {
		return Stream.of(
				new String[] {"application/pkcs7-mime", "UNKNOWN"},
				new String[] {"application/x-pkcs7-mime; smime-type=unrecognized", "UNKNOWN"},
				new String[] {"APPLICATION/X-PKCS7-MIME; smime-type=ENVELOPED-DATA", "ENCRYPTED"},
				new String[] {"application/pkcs7-mime; smime-type=signed-data", "SIGNED"}
		).map(example -> DynamicTest.dynamicTest(example[0], () -> {
			final byte[] bytes = ("From: sender@example.org\r\nContent-Type: " + example[0]
					+ "\r\nContent-Transfer-Encoding: base64\r\n\r\nAQID\r\n").getBytes(UTF_8);
			try (WithoutSmime runtime = runtime()) {
				final Object email = importProtectedEml(runtime, bytes);
				if (example[1].equals("UNKNOWN")) assertUnclassified(runtime, email);
				else assertUnavailable(runtime, email, example[1].equals("SIGNED"));
				assertThat(original(runtime, email)).containsExactly(bytes);
			}
		}));
	}

	@TestFactory
	Stream<DynamicTest> malformedRootDeclarationsRemainUnknownWithoutTheModule() {
		return Stream.of("not-a-content-type", "application/pkcs7-mime; =broken").map(type -> DynamicTest.dynamicTest(type, () -> {
			final byte[] bytes = ("From: sender@example.org\r\nContent-Type: " + type + "\r\n\r\nOrdinary content").getBytes(UTF_8);
			try (WithoutSmime runtime = runtime()) {
				assertUnclassified(runtime, importProtectedEml(runtime, bytes));
			}
		}));
	}

	@Test
	void protectedInputFactsSurviveCopySerializationAndOutgoingCompletion() throws Exception {
		final byte[] bytes = Files.readAllBytes(protectedFixture("encrypted.eml"));
		try (WithoutSmime runtime = runtime()) {
			final Object email = importProtectedEml(runtime, bytes);
			final byte[] firstRead = original(runtime, email);
			firstRead[0] ^= 1;
			assertThat(original(runtime, email)).containsExactly(bytes);
			final Class<?> factoryType = runtime.loadClass("org.simplejavamail.api.SimpleJavaMail");
			final Object factory = factoryType.getMethod("fromDefaults").invoke(null);
			final Object starter = factoryType.getMethod("emailBuilder").invoke(factory);
			final Object builder = runtime.loadClass("org.simplejavamail.api.email.EmailStartingBuilder")
					.getMethod("copying", runtime.loadClass("org.simplejavamail.api.email.Email")).invoke(starter, email);
			final Class<?> builderType = runtime.loadClass("org.simplejavamail.api.email.EmailPopulatingBuilder");
			for (Object retained : new Object[] {builderType.getMethod("buildEmail").invoke(builder),
					roundTrip(runtime, email), builderType.getMethod("buildEmailCompletedWithDefaultsAndOverrides").invoke(builder)}) {
				assertUnavailable(runtime, retained, false);
				assertThat(original(runtime, retained)).containsExactly(bytes);
			}
		}
	}

	@Test
	void unrelatedProtectedAttachmentDoesNotBecomeAnOwningEnvelopeFact() throws Exception {
		final byte[] bytes = ("From: sender@example.org\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n"
				+ "--parts\r\nContent-Type: text/plain\r\n\r\nPlain parent\r\n"
				+ "--parts\r\nContent-Type: application/pkcs7-mime; smime-type=enveloped-data\r\n"
				+ "Content-Disposition: attachment; filename=independent.p7m\r\nContent-Transfer-Encoding: base64\r\n\r\nAQID\r\n"
				+ "--parts--\r\n").getBytes(UTF_8);
		try (WithoutSmime runtime = runtime()) {
			final Object email = importProtectedEml(runtime, bytes);
			final Object family = details(runtime, email);
			assertThat((java.util.List<?>) read(runtime, family, "getSignatures")).isEmpty();
			final Class<?> inspectionType = runtime.loadClass("org.simplejavamail.api.email.EmailInspection");
			final Class<?> valueType = runtime.loadClass("org.simplejavamail.api.email.ValueInspection");
			final Object value = inspectionType.getMethod("getPlainText").invoke(inspection(runtime, email));
			assertThat(valueType.getMethod("getSignatureStatus").invoke(value).toString()).isEqualTo("UNSIGNED");
			assertThat(valueType.getMethod("getEncryptionStatus").invoke(value).toString()).isEqualTo("NOT_ENCRYPTED");
			assertThat((java.util.List<?>) valueType.getMethod("getDecryptions").invoke(value)).isEmpty();
			final Object file = inspectionType.getMethod("getAttachment", int.class).invoke(inspection(runtime, email), 0);
			assertThat(valueType.getMethod("getEncryptionStatus").invoke(file).toString()).isEqualTo("ENCRYPTED");
			final java.util.List<?> attempts = (java.util.List<?>) valueType.getMethod("getDecryptions").invoke(file);
			final java.util.List<?> history = (java.util.List<?>) read(runtime, family, "getDecryptions");
			assertThat(attempts).hasSize(1);
			assertThat(history).hasSize(1);
			final Class<?> attemptType = runtime.loadClass("org.simplejavamail.api.email.DecryptionInspection");
			assertThat(attemptType.getMethod("getStatus").invoke(attempts.get(0)).toString()).isEqualTo("UNAVAILABLE");
			assertThat(attemptType.getMethod("getReference").invoke(attempts.get(0)))
					.isEqualTo(attemptType.getMethod("getReference").invoke(history.get(0)));
			assertThat(original(runtime, email)).containsExactly(bytes);
		}
	}

	private static Object inspection(final WithoutSmime runtime, final Object email) throws Exception {
		return runtime.loadClass("org.simplejavamail.api.email.Email").getMethod("getInspection").invoke(email);
	}

	private static Object details(final WithoutSmime runtime, final Object email) throws Exception {
		return runtime.loadClass("org.simplejavamail.api.email.EmailInspection").getMethod("getSmime").invoke(inspection(runtime, email));
	}

	private static Object read(final WithoutSmime runtime, final Object family, final String getter) throws Exception {
		return runtime.loadClass("org.simplejavamail.api.email.ProtectionInspection").getMethod(getter).invoke(family);
	}

	private static byte[] original(final WithoutSmime runtime, final Object email) throws Exception {
		final Object resource = runtime.loadClass("org.simplejavamail.api.email.EmailInspection").getMethod("getOriginalMessage").invoke(inspection(runtime, email));
		return resource == null ? null : (byte[]) runtime.loadClass("org.simplejavamail.api.email.AttachmentResource").getMethod("readAllBytes").invoke(resource);
	}

	private static void assertUnavailable(final WithoutSmime runtime, final Object email, final boolean signed) throws Exception {
		final Object family = details(runtime, email);
		final java.util.List<?> signatures = (java.util.List<?>) read(runtime, family, "getSignatures");
		final java.util.List<?> decryptions = (java.util.List<?>) read(runtime, family, "getDecryptions");
		assertThat(read(runtime, family, "isInspectionComplete")).isEqualTo(false);
		assertThat(signatures).isEmpty();
		if (signed) {
			assertThat(decryptions).isEmpty();
		} else {
			assertThat(decryptions).isNotEmpty();
			for (Object decryption : decryptions) {
				assertThat(runtime.loadClass("org.simplejavamail.api.email.DecryptionInspection").getMethod("getStatus").invoke(decryption).toString()).isEqualTo("UNAVAILABLE");
			}
		}
	}

	private static void assertUnclassified(final WithoutSmime runtime, final Object email) throws Exception {
		final Object family = details(runtime, email);
		assertThat(read(runtime, family, "isInspectionComplete")).isEqualTo(false);
		assertThat((java.util.List<?>) read(runtime, family, "getSignatures")).isEmpty();
		assertThat((java.util.List<?>) read(runtime, family, "getDecryptions")).isEmpty();
	}

	private static Object roundTrip(final WithoutSmime runtime, final Object email) throws Exception {
		final ByteArrayOutputStream serialized = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(serialized)) {
			output.writeObject(email);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized.toByteArray())) {
			@Override
			protected Class<?> resolveClass(final ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
				return Class.forName(descriptor.getName(), false, runtime);
			}
		}) {
			return input.readObject();
		}
	}

	private static Object importProtectedEml(final WithoutSmime runtime, final byte[] original) throws Exception {
		final ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(runtime);
			return runtime.loadClass("org.simplejavamail.converter.EmailConverter")
					.getMethod("emlToEmail", java.io.InputStream.class)
					.invoke(null, new ByteArrayInputStream(original));
		} finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
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
		return importMail(runtime, source, fluent, decrypt, clearDecryption, null);
	}

	private static Object importMail(final WithoutSmime runtime, final String source, final boolean fluent, final boolean decrypt,
			final boolean clearDecryption, final String protection) throws Exception {
		final Thread caller = Thread.currentThread();
		final ClassLoader previous = caller.getContextClassLoader();
		try {
			caller.setContextClassLoader(runtime);
			final Class<?> keysType = runtime.loadClass("org.simplejavamail.api.mailer.config.Pkcs12Config");
			final Object keys = decrypt ? keys(keysType) : null;
			final String eml = protection == null ? EML : Files.readString(protectedFixture(protection + ".eml"), UTF_8);
			final File outlook = protection == null ? outlook() : protectedFixture(protection + ".msg").toFile();
			return fluent ? fluentImport(runtime, keysType, keys, source, clearDecryption, eml, outlook)
					: staticImport(runtime, keysType, keys, source, eml, outlook);
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
			final boolean clearDecryption, final String eml, final File outlook) throws Exception {
		final Class<?> factoryType = runtime.loadClass("org.simplejavamail.api.SimpleJavaMail");
		final Object factory = factoryType.getMethod("fromDefaults").invoke(null);
		final Object converter = factoryType.getMethod("converter").invoke(factory);
		final Class<?> converterType = runtime.loadClass("org.simplejavamail.api.converter.ConfiguredEmailConverter");
		final Object stage;
		final Class<?> stageType;
		if ("outlook".equals(source)) {
			stage = converterType.getMethod("fromOutlookMsg", File.class).invoke(converter, outlook);
			stageType = runtime.loadClass("org.simplejavamail.api.converter.ConfiguredEmailConverter$OutlookConversion");
		} else if ("mime".equals(source)) {
			final Class<?> mimeType = runtime.loadClass("jakarta.mail.internet.MimeMessage");
			stage = converterType.getMethod("fromMimeMessage", mimeType).invoke(converter, mime(runtime, eml));
			stageType = runtime.loadClass("org.simplejavamail.api.converter.ConfiguredEmailConverter$MimeMessageConversion");
		} else {
			stage = converterType.getMethod("fromEml", String.class).invoke(converter, eml);
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

	private static Object staticImport(final WithoutSmime runtime, final Class<?> keysType, final Object keys, final String source,
			final String eml, final File outlook) throws Exception {
		final Class<?> converter = runtime.loadClass("org.simplejavamail.converter.EmailConverter");
		if ("outlook".equals(source)) {
			return converter.getMethod("outlookMsgToEmail", File.class, keysType).invoke(null, outlook, keys);
		}
		if ("mime".equals(source)) {
			return converter.getMethod("mimeMessageToEmail", runtime.loadClass("jakarta.mail.internet.MimeMessage"), keysType)
					.invoke(null, mime(runtime, eml), keys);
		}
		return converter.getMethod("emlToEmail", String.class, keysType).invoke(null, eml, keys);
	}

	private static Object mime(final WithoutSmime runtime, final String eml) throws Exception {
		return runtime.loadClass("org.simplejavamail.converter.EmailConverter").getMethod("emlToMimeMessage", String.class).invoke(null, eml);
	}

	private static File outlook() {
		return Path.of(determineResourceFolder("simple-java-mail"), "test/resources/test-messages/simple email with TO and CC.msg").toFile();
	}

	private static Path protectedFixture(final String suffix) {
		return Path.of(determineResourceFolder("simple-java-mail"), "test/resources/pkcs12/test messages/S_MIME test message " + suffix);
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
