package org.simplejavamail.converter;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.internal.protectionsupport.model.InboundProtectionResult;
import org.simplejavamail.converter.internal.ConfiguredEmailConverterImpl;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class OpenPgpUnavailableInspectionTest {

	private static final byte[] PLAIN = ("From: sender@example.org\r\nSubject: readable without OpenPGP\r\n"
			+ "Content-Type: text/plain; charset=UTF-8\r\n\r\nPlain content").getBytes(UTF_8);

	@TestFactory
	Stream<DynamicTest> ordinaryImportsDistinguishUnavailableInspectionFromKnownPlainContent() {
		return Stream.of("eml", "mime").flatMap(source -> Stream.of(false, true).flatMap(fluent ->
				Stream.of("plain", "signed", "encrypted").map(protection -> DynamicTest.dynamicTest(
						source + " " + protection + (fluent ? " fluent" : " static"), () -> {
					try (WithoutOpenPgp runtime = runtime()) {
						assertModuleAbsent(runtime);
						final byte[] original = source(protection);
						final Object email = importMail(runtime, source, fluent, original, false, false);
						final Object details = details(runtime, email);
						if (protection.equals("plain")) {
							assertPlain(runtime, details);
							assertThat(readEmail(runtime, email, "getPlainText")).isEqualTo("Plain content");
						} else {
							assertUnknown(runtime, details, protection);
							assertThat((byte[]) read(runtime, details, "getOriginalProtectedMessage")).containsExactly(original);
							if (protection.equals("signed")) {
								assertThat(readEmail(runtime, email, "getPlainText")).isEqualTo("Héllo from OpenPGP.js");
								assertThat(attachment(runtime, email, "proof.txt")).isEqualTo("independent fixture");
							} else {
								assertThat(attachment(runtime, email, "encrypted.asc")).contains("-----BEGIN PGP MESSAGE-----");
							}
						}
					}
				}))));
	}

	@TestFactory
	Stream<DynamicTest> explicitReceiveConfigurationRequiresTheModuleEvenForPlainContent() {
		return Stream.of("eml", "mime").flatMap(source -> Stream.of(false, true).flatMap(fluent ->
				Stream.of("plain", "signed", "encrypted").map(protection -> DynamicTest.dynamicTest(
						source + " " + protection + (fluent ? " fluent" : " static"), () -> {
					try (WithoutOpenPgp runtime = runtime()) {
						assertThatThrownBy(() -> importMail(runtime, source, fluent, source(protection), true, false))
								.hasMessageContaining("OpenPGP").hasMessageContaining("openpgp-module");
					}
				}))));
	}

	@TestFactory
	Stream<DynamicTest> clearingTheExplicitRequestRestoresOrdinaryUnavailableInspection() {
		return Stream.of("eml", "mime").flatMap(source -> Stream.of("signed", "encrypted").map(protection ->
				DynamicTest.dynamicTest(source + " " + protection, () -> {
					try (WithoutOpenPgp runtime = runtime()) {
						final Object email = importMail(runtime, source, true, source(protection), true, true);
						assertUnknown(runtime, details(runtime, email), protection);
					}
				})));
	}

	@TestFactory
	Stream<DynamicTest> unreadableClassificationCannotBypassAnExplicitModuleRequirement() {
		return Stream.of(false, true).map(fluent -> DynamicTest.dynamicTest(fluent ? "fluent" : "static", () -> {
			try (WithoutOpenPgp runtime = runtime()) {
				assertModuleAbsent(runtime);
				final ClassLoader previous = Thread.currentThread().getContextClassLoader();
				try {
					Thread.currentThread().setContextClassLoader(runtime);
					final Constructor<?> constructor = runtime.loadClass(ClassificationFailureMessage.class.getName()).getDeclaredConstructor();
					constructor.setAccessible(true);
					final Object unreadable = constructor.newInstance();
					assertThatThrownBy(() -> importMail(runtime, "mime", fluent, PLAIN, true, false, unreadable))
							.hasMessageContaining("OpenPGP").hasMessageContaining("openpgp-module");
				} finally {
					Thread.currentThread().setContextClassLoader(previous);
				}
			}
		}));
	}

	@TestFactory
	Stream<DynamicTest> unavailableInputFactsSurviveCopyAndSerializationButNotOutgoingCompletion() {
		return Stream.of("signed", "encrypted").map(protection -> DynamicTest.dynamicTest(protection, () -> {
			final byte[] original = source(protection);
			try (WithoutOpenPgp runtime = runtime()) {
				final Object email = importMail(runtime, "eml", true, original, false, false);
				final Object details = details(runtime, email);
				final byte[] firstRead = (byte[]) read(runtime, details, "getOriginalProtectedMessage");
				firstRead[0] ^= 1;
				assertThat((byte[]) read(runtime, details, "getOriginalProtectedMessage")).containsExactly(original);

				final Class<?> factoryType = runtime.loadClass("org.simplejavamail.api.SimpleJavaMail");
				final Object factory = factoryType.getMethod("fromDefaults").invoke(null);
				final Object starter = factoryType.getMethod("emailBuilder").invoke(factory);
				final Object builder = runtime.loadClass("org.simplejavamail.api.email.EmailStartingBuilder")
						.getMethod("copying", runtime.loadClass("org.simplejavamail.api.email.Email")).invoke(starter, email);
				final Class<?> builderType = runtime.loadClass("org.simplejavamail.api.email.EmailPopulatingBuilder");
				final Object copied = builderType.getMethod("buildEmail").invoke(builder);
				assertUnknown(runtime, details(runtime, copied), protection);
				assertThat((byte[]) read(runtime, details(runtime, copied), "getOriginalProtectedMessage")).containsExactly(original);
				final Object restored = roundTrip(runtime, copied);
				assertUnknown(runtime, details(runtime, restored), protection);
				assertThat((byte[]) read(runtime, details(runtime, restored), "getOriginalProtectedMessage")).containsExactly(original);

				final Object completed = builderType.getMethod("buildEmailCompletedWithDefaultsAndOverrides").invoke(builder);
				assertPlain(runtime, details(runtime, completed));
			}
		}));
	}

	@Test
	void unreadableClassificationKeepsUnknownFactsWhileReadableContentStillImports() throws Exception {
		try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
			modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
			final Email email = SimpleJavaMail.fromDefaults().converter().fromMimeMessage(new ClassificationFailureMessage()).toEmail();
			assertThat(email.getPlainText()).isEqualTo("Plain content");
			assertUnclassified(email.getOriginalOpenPgpDetails());
		}
	}

	@Test
	void classificationFailureDoesNotClaimARecognizedOpenPgpEnvelope() throws Exception {
		try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
			modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
			final MimeMessage source = new ClassificationFailureMessage();
			final Method preprocess = ConfiguredEmailConverterImpl.class.getDeclaredMethod(
					"preprocessOpenPgp", MimeMessage.class, OpenPgpReceiveConfig.class);
			preprocess.setAccessible(true);
			final InboundProtectionResult<?> result = (InboundProtectionResult<?>) preprocess.invoke(null, source, null);
			assertThat(result.isRecognized()).isFalse();
			assertThat(result.getEffectiveMimeMessage()).isSameAs(source);
			assertUnclassified((OriginalOpenPgpDetails) result.getDetails());
		}
	}

	private static void assertUnclassified(final OriginalOpenPgpDetails details) {
		assertThat(details.getOpenPgpMode().name()).isEqualTo("UNKNOWN");
		assertThat(details.getSignatureStatus().name()).isEqualTo("UNKNOWN");
		assertThat(details.getDecryptionStatus().name()).isEqualTo("UNKNOWN");
		assertThat(details.getFailureReason()).contains("OpenPGP");
	}

	private static void assertModuleAbsent(final WithoutOpenPgp runtime) throws Exception {
		assertThat(runtime.loadClass("org.simplejavamail.internal.moduleloader.ModuleLoader")
				.getMethod("openPgpModuleAvailable").invoke(null)).isEqualTo(false);
		assertThatThrownBy(() -> runtime.loadClass("org.simplejavamail.internal.openpgpsupport.OpenPgpSupport"))
				.isInstanceOf(ClassNotFoundException.class);
	}

	private static void assertPlain(final WithoutOpenPgp runtime, final Object details) throws Exception {
		assertThat(read(runtime, details, "getOpenPgpMode").toString()).isEqualTo("PLAIN");
		assertThat(read(runtime, details, "getSignatureStatus").toString()).isEqualTo("NOT_PRESENT");
		assertThat(read(runtime, details, "getDecryptionStatus").toString()).isEqualTo("NOT_ENCRYPTED");
		assertThat(read(runtime, details, "getOriginalProtectedMessage")).isNull();
	}

	private static void assertUnknown(final WithoutOpenPgp runtime, final Object details, final String protection) throws Exception {
		assertThat(read(runtime, details, "getOpenPgpMode").toString()).isEqualTo(protection.equals("signed") ? "SIGNED" : "ENCRYPTED");
		assertThat(read(runtime, details, "getSignatureStatus").toString()).isEqualTo("UNKNOWN");
		assertThat(read(runtime, details, "getDecryptionStatus").toString()).isEqualTo(protection.equals("signed") ? "NOT_ENCRYPTED" : "UNKNOWN");
		assertThat(read(runtime, details, "getFailureReason").toString()).contains("openpgp-module is not available");
	}

	private static Object details(final WithoutOpenPgp runtime, final Object email) throws Exception {
		return readEmail(runtime, email, "getOriginalOpenPgpDetails");
	}

	private static Object readEmail(final WithoutOpenPgp runtime, final Object email, final String getter) throws Exception {
		return runtime.loadClass("org.simplejavamail.api.email.Email").getMethod(getter).invoke(email);
	}

	private static Object read(final WithoutOpenPgp runtime, final Object details, final String getter) throws Exception {
		return runtime.loadClass("org.simplejavamail.api.email.OriginalOpenPgpDetails").getMethod(getter).invoke(details);
	}

	private static String attachment(final WithoutOpenPgp runtime, final Object email, final String name) throws Exception {
		final Class<?> attachmentType = runtime.loadClass("org.simplejavamail.api.email.AttachmentResource");
		for (Object attachment : (List<?>) readEmail(runtime, email, "getAttachments")) {
			if (name.equals(attachmentType.getMethod("getName").invoke(attachment))) {
				return (String) attachmentType.getMethod("readAllData").invoke(attachment);
			}
		}
		throw new AssertionError("No retained attachment named " + name);
	}

	private static Object importMail(final WithoutOpenPgp runtime, final String source, final boolean fluent, final byte[] original,
			final boolean configure, final boolean clear) throws Exception {
		return importMail(runtime, source, fluent, original, configure, clear, null);
	}

	private static Object importMail(final WithoutOpenPgp runtime, final String source, final boolean fluent, final byte[] original,
			final boolean configure, final boolean clear, final Object suppliedMime) throws Exception {
		final ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(runtime);
			final Class<?> configType = runtime.loadClass("org.simplejavamail.api.email.config.OpenPgpReceiveConfig");
			final Object configBuilder = configure ? configType.getMethod("builder").invoke(null) : null;
			final Object config = configure ? configBuilder.getClass().getMethod("build").invoke(configBuilder) : null;
			final Class<?> converterType = runtime.loadClass("org.simplejavamail.converter.EmailConverter");
			final Class<?> mimeType = runtime.loadClass("jakarta.mail.internet.MimeMessage");
			final Object mime = suppliedMime != null ? suppliedMime : source.equals("mime")
					? converterType.getMethod("emlToMimeMessage", InputStream.class).invoke(null, new ByteArrayInputStream(original)) : null;
			if (!fluent) {
				if (source.equals("mime")) {
					return converterType.getMethod("mimeMessageToEmail", mimeType,
							runtime.loadClass("org.simplejavamail.api.mailer.config.Pkcs12Config"), configType).invoke(null, mime, null, config);
				}
				return configure ? converterType.getMethod("emlToEmailWithOpenPgp", InputStream.class, configType)
						.invoke(null, new ByteArrayInputStream(original), config)
						: converterType.getMethod("emlToEmail", InputStream.class).invoke(null, new ByteArrayInputStream(original));
			}
			final Class<?> factoryType = runtime.loadClass("org.simplejavamail.api.SimpleJavaMail");
			final Object factory = factoryType.getMethod("fromDefaults").invoke(null);
			final Object converter = factoryType.getMethod("converter").invoke(factory);
			final Class<?> configuredType = runtime.loadClass("org.simplejavamail.api.converter.ConfiguredEmailConverter");
			final Object stage = source.equals("mime")
					? configuredType.getMethod("fromMimeMessage", mimeType).invoke(converter, mime)
					: configuredType.getMethod("fromEml", byte[].class).invoke(converter, (Object) original);
			final Class<?> stageType = runtime.loadClass("org.simplejavamail.api.converter.ConfiguredEmailConverter$IncomingConversion");
			if (configure) {
				stageType.getMethod("withOpenPgpReceiveConfig", configType).invoke(stage, config);
			}
			if (clear) {
				stageType.getMethod("clearOpenPgpReceiveConfig").invoke(stage);
			}
			return stageType.getMethod("toEmail").invoke(stage);
		} catch (InvocationTargetException failure) {
			if (failure.getCause() instanceof Exception) {
				throw (Exception) failure.getCause();
			}
			throw failure;
		} finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	private static Object roundTrip(final WithoutOpenPgp runtime, final Object email) throws Exception {
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

	private static byte[] source(final String protection) throws IOException {
		return protection.equals("plain") ? PLAIN.clone() : Files.readAllBytes(Path.of(determineResourceFolder("simple-java-mail"),
				"test/resources/openpgpjs/" + protection + "-mixed.eml"));
	}

	private static WithoutOpenPgp runtime() {
		final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
		final URL[] urls = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator))).map(entry -> {
			try {
				return new File(entry).toURI().toURL();
			} catch (Exception failure) {
				throw new IllegalStateException(failure);
			}
		}).toArray(URL[]::new);
		return new WithoutOpenPgp(urls);
	}

	private static final class WithoutOpenPgp extends URLClassLoader {
		private WithoutOpenPgp(final URL[] urls) {
			super(urls, ClassLoader.getPlatformClassLoader());
		}

		@Override
		protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("org.simplejavamail.internal.openpgpsupport.")) {
				throw new ClassNotFoundException("OpenPGP deliberately absent from this isolated application: " + name);
			}
			return super.loadClass(name, resolve);
		}
	}

	private static final class ClassificationFailureMessage extends MimeMessage {
		private boolean failClassification = true;

		private ClassificationFailureMessage() throws MessagingException {
			super(Session.getInstance(new Properties()), new ByteArrayInputStream(PLAIN));
		}

		@Override
		public boolean isMimeType(final String mimeType) throws MessagingException {
			if (failClassification) {
				failClassification = false;
				throw new MessagingException("Input headers unavailable during OpenPGP classification");
			}
			return super.isMimeType(mimeType);
		}
	}
}
