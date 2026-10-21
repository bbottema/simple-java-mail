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
		return Stream.of("eml", "mime", "outlook").flatMap(source -> Stream.of(false, true).map(fluent -> DynamicTest.dynamicTest(source + (fluent ? " fluent" : " static"), () -> {
			try (WithoutSmime runtime = runtime()) {
				final Object details = details(runtime, importMail(runtime, source, fluent, false));
				assertThat(read(runtime, details, "getSmimeMode").toString()).isEqualTo("PLAIN");
				assertThat(read(runtime, details, "getVerificationStatus").toString()).isEqualTo("NOT_SIGNED");
				assertThat(read(runtime, details, "getDecryptionStatus").toString()).isEqualTo("NOT_ENCRYPTED");
				assertThat(read(runtime, details, "getOriginalProtectedMessage")).isNull();
			}
		})));
	}

	@Test
	void encryptedEmlWithoutTheModuleRetainsUnknownResultsAndExactSource() throws Exception {
		final byte[] original = Files.readAllBytes(protectedFixture("encrypted.eml"));
		try (WithoutSmime runtime = runtime()) {
			final Object email = importProtectedEml(runtime, original);
			final Object details = details(runtime, email);
			assertUnknown(runtime, details, "ENCRYPTED");
			assertThat((byte[]) read(runtime, details, "getOriginalProtectedMessage")).containsExactly(original);
		}
	}

	@TestFactory
	Stream<DynamicTest> protectedImportsReportUnavailableInspectionOnEveryRoute() {
		return Stream.of("eml", "mime", "outlook").flatMap(source -> Stream.of(false, true).flatMap(fluent ->
				Stream.of("signed", "encrypted").map(protection -> DynamicTest.dynamicTest(source + " " + protection + (fluent ? " fluent" : " static"), () -> {
					try (WithoutSmime runtime = runtime()) {
						final Object details = details(runtime, importMail(runtime, source, fluent, false, false, protection));
						assertUnknown(runtime, details, protection.equals("signed") ? "SIGNED" : "ENCRYPTED");
						final byte[] retained = (byte[]) read(runtime, details, "getOriginalProtectedMessage");
						if (source.equals("outlook")) {
							assertThat(retained).as("MSG has no exact original EML").isNull();
						} else if (source.equals("eml")) {
							assertThat(retained).containsExactly(Files.readAllBytes(protectedFixture(protection + ".eml")));
						} else {
							assertThat(retained).isNotEmpty();
						}
					}
				}))));
	}

	@TestFactory
	Stream<DynamicTest> ambiguousAndAliasedDeclarationsKeepUnknownFacts() {
		return Stream.of(
				new String[] {"application/pkcs7-mime", "UNKNOWN"},
				new String[] {"application/x-pkcs7-mime; smime-type=unrecognized", "UNKNOWN"},
				new String[] {"APPLICATION/X-PKCS7-MIME; smime-type=ENVELOPED-DATA", "ENCRYPTED"},
				new String[] {"application/pkcs7-mime; smime-type=signed-data", "SIGNED"}
		).map(example -> DynamicTest.dynamicTest(example[0], () -> {
			final byte[] source = ("From: sender@example.org\r\nContent-Type: " + example[0]
					+ "\r\nContent-Transfer-Encoding: base64\r\n\r\nAQID\r\n").getBytes(UTF_8);
			try (WithoutSmime runtime = runtime()) {
				final Object details = details(runtime, importProtectedEml(runtime, source));
				assertUnknown(runtime, details, example[1]);
				assertThat((byte[]) read(runtime, details, "getOriginalProtectedMessage")).containsExactly(source);
			}
		}));
	}

	@Test
	void protectedInputFactsSurviveCopyAndSerializationButNotOutgoingCompletion() throws Exception {
		final byte[] source = Files.readAllBytes(protectedFixture("encrypted.eml"));
		try (WithoutSmime runtime = runtime()) {
			final Object email = importProtectedEml(runtime, source);
			final Object details = details(runtime, email);
			final byte[] firstRead = (byte[]) read(runtime, details, "getOriginalProtectedMessage");
			firstRead[0] ^= 1;
			assertThat((byte[]) read(runtime, details, "getOriginalProtectedMessage")).containsExactly(source);

			final Class<?> factoryType = runtime.loadClass("org.simplejavamail.api.SimpleJavaMail");
			final Object factory = factoryType.getMethod("fromDefaults").invoke(null);
			final Object starter = factoryType.getMethod("emailBuilder").invoke(factory);
			final Object builder = runtime.loadClass("org.simplejavamail.api.email.EmailStartingBuilder")
					.getMethod("copying", runtime.loadClass("org.simplejavamail.api.email.Email")).invoke(starter, email);
			final Class<?> builderType = runtime.loadClass("org.simplejavamail.api.email.EmailPopulatingBuilder");
			final Object copied = builderType.getMethod("buildEmail").invoke(builder);
			assertUnknown(runtime, details(runtime, copied), "ENCRYPTED");
			assertThat((byte[]) read(runtime, details(runtime, copied), "getOriginalProtectedMessage")).containsExactly(source);
			final Object restored = roundTrip(runtime, copied);
			assertUnknown(runtime, details(runtime, restored), "ENCRYPTED");
			assertThat((byte[]) read(runtime, details(runtime, restored), "getOriginalProtectedMessage")).containsExactly(source);

			final Object completed = builderType.getMethod("buildEmailCompletedWithDefaultsAndOverrides").invoke(builder);
			assertThat(read(runtime, details(runtime, completed), "getSmimeMode").toString()).isEqualTo("PLAIN");
			assertThat(read(runtime, details(runtime, completed), "getOriginalProtectedMessage")).isNull();
		}
	}

	@Test
	void unrelatedProtectedAttachmentDoesNotBecomeAnOwningEnvelopeFact() throws Exception {
		final byte[] source = ("From: sender@example.org\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n"
				+ "--parts\r\nContent-Type: text/plain\r\n\r\nPlain parent\r\n"
				+ "--parts\r\nContent-Type: application/pkcs7-mime; smime-type=enveloped-data\r\n"
				+ "Content-Disposition: attachment; filename=independent.p7m\r\nContent-Transfer-Encoding: base64\r\n\r\nAQID\r\n"
				+ "--parts--\r\n").getBytes(UTF_8);
		try (WithoutSmime runtime = runtime()) {
			final Object details = details(runtime, importProtectedEml(runtime, source));
			assertThat(read(runtime, details, "getSmimeMode").toString()).isEqualTo("PLAIN");
			assertThat(read(runtime, details, "getVerificationStatus").toString()).isEqualTo("NOT_SIGNED");
			assertThat(read(runtime, details, "getDecryptionStatus").toString()).isEqualTo("NOT_ENCRYPTED");
			assertThat(read(runtime, details, "getOriginalProtectedMessage")).isNull();
		}
	}

	private static Object details(final WithoutSmime runtime, final Object email) throws Exception {
		return runtime.loadClass("org.simplejavamail.api.email.Email").getMethod("getOriginalSmimeDetails").invoke(email);
	}

	private static Object read(final WithoutSmime runtime, final Object details, final String getter) throws Exception {
		return runtime.loadClass("org.simplejavamail.api.email.OriginalSmimeDetails").getMethod(getter).invoke(details);
	}

	private static void assertUnknown(final WithoutSmime runtime, final Object details, final String mode) throws Exception {
		assertThat(read(runtime, details, "getSmimeMode").toString()).isEqualTo(mode);
		assertThat(read(runtime, details, "getVerificationStatus").toString()).isEqualTo("UNKNOWN");
		assertThat(read(runtime, details, "getDecryptionStatus").toString()).isEqualTo("UNKNOWN");
		assertThat(read(runtime, details, "getSmimeSignatureValid")).isNull();
		assertThat(read(runtime, details, "getFailureReason")).isNull();
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
