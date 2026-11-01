package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.MethodJavadoc;
import com.github.therapi.runtimejavadoc.RuntimeJavadoc;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.internal.clisupport.CliDataLocator;
import org.simplejavamail.internal.clisupport.serialization.SerializationUtil;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.simplejavamail.internal.clisupport.CliMetadataException;
import static org.mockito.Mockito.mockStatic;

class TherapiJavadocCacheTest {

	@TempDir
	File temporaryDirectory;

	@Test
	void persistedCacheRoundTripsRealJavadocInsteadOfFallingBackToAnEmptyMap() throws Exception {
		Method method = EmailPopulatingBuilder.class.getMethod("withSubject", String.class);
		MethodJavadoc original = RuntimeJavadoc.getJavadoc(method);
		assertThat(original.getComment().toString()).contains("optional subject");
		Map<String, MethodJavadoc> cache = new LinkedHashMap<>();
		cache.put(method.toString(), original);
		File cacheFile = new File(temporaryDirectory, "therapi.data");

		Files.write(cacheFile.toPath(), TherapiJavadocHelper.serializeCache(cache));
		Map<String, MethodJavadoc> restored = TherapiJavadocHelper.loadTherapiCache(Files.readAllBytes(cacheFile.toPath()));

		assertThat(restored).containsOnlyKeys(method.toString());
		MethodJavadoc restoredJavadoc = restored.get(method.toString());
		assertThat(restoredJavadoc.getName()).isEqualTo(original.getName());
		assertThat(restoredJavadoc.getParamTypes()).containsExactlyElementsOf(original.getParamTypes());
		assertThat(restoredJavadoc.getComment().toString()).isEqualTo(original.getComment().toString());
		assertThat(restoredJavadoc.getParams()).extracting(param -> param.getName()).containsExactly("subject");
		assertThat(restoredJavadoc.getParams().get(0).getComment().toString())
				.isEqualTo(original.getParams().get(0).getComment().toString());
	}

	@Test
	void cacheWrittenByAnotherJvmIsRestoredWithoutRebuildingJavadocs() throws Exception {
		File cacheFile = new File(temporaryDirectory, "cold-therapi.data");
		runCacheProcess("write", cacheFile);
		runCacheProcess("read", cacheFile);
	}

    @Test
    void aFailedBuildLookupCannotEnableSourceLookupForLaterRuntimeRequests() throws Exception {
        final Method method = EmailPopulatingBuilder.class.getMethod("withSubject", String.class);
        try (MockedStatic<RuntimeJavadoc> source = mockStatic(RuntimeJavadoc.class)) {
            source.when(() -> RuntimeJavadoc.getJavadoc(method)).thenThrow(new IllegalStateException("broken build source"));
            assertThatThrownBy(() -> {
                try (TherapiJavadocHelper.GenerationScope ignored = TherapiJavadocHelper.beginGeneration()) {
                    TherapiJavadocHelper.getParamDescriptions(method);
                }
            }).hasMessage("broken build source");
            assertThat(TherapiJavadocHelper.getParamDescriptions(method)).extracting(parameter -> parameter.getName()).containsExactly("subject");
            source.verify(() -> RuntimeJavadoc.getJavadoc(method), org.mockito.Mockito.times(1));
        }
    }

	@Test
	void bundledCacheContainsReadableJavadocsRatherThanRelyingOnFallback() throws Exception {
        Map<String, MethodJavadoc> cache = TherapiJavadocHelper.loadTherapiCache(CliDataLocator.readTherapiDataFile());
		Method subject = EmailPopulatingBuilder.class.getMethod("withSubject", String.class);
		assertThat(cache).containsKey(subject.toString());
		assertThat(cache.get(subject.toString()).getComment().toString()).contains("optional subject");
	}

	@Test
	void bundledReplyJavadocDescribesInitialAlternativeGeneration() throws Exception {
		final Method reply = EmailStartingBuilder.class.getMethod("replyingTo", MimeMessage.class, boolean.class, String.class);
		final Map<String, MethodJavadoc> cache = TherapiJavadocHelper.loadTherapiCache(CliDataLocator.readTherapiDataFile());
		assertThat(cache).containsKey(reply.toString());
		assertThat(cache.get(reply.toString()).getComment().toString())
				.contains("generated once", "Later body changes remain independent");
	}

	@Test
	void legacyAndCorruptCacheFilesAreInstallationErrors() throws Exception {
		Map<String, MethodJavadoc> cache = subjectCache();
		File legacy = new File(temporaryDirectory, "legacy.data");
		Files.write(legacy.toPath(), SerializationUtil.serialize(cache));
		assertThatThrownBy(() -> TherapiJavadocHelper.loadTherapiCache(Files.readAllBytes(legacy.toPath()))).isInstanceOf(CliMetadataException.class);

		File corrupt = new File(temporaryDirectory, "corrupt.data");
		byte[] valid = TherapiJavadocHelper.serializeCache(cache);
		Files.write(corrupt.toPath(), java.util.Arrays.copyOf(valid, valid.length - 1));
		assertThatThrownBy(() -> TherapiJavadocHelper.loadTherapiCache(Files.readAllBytes(corrupt.toPath()))).isInstanceOf(CliMetadataException.class);
	}

	@Test
	void missingAndUnreadableRequiredCachesFailClearly() throws Exception {
		assertThatThrownBy(() -> TherapiJavadocHelper.loadTherapiCache((byte[]) null)).isInstanceOf(CliMetadataException.class);
		try (MockedStatic<CliDataLocator> locator = mockStatic(CliDataLocator.class)) {
			locator.when(CliDataLocator::readTherapiDataFile).thenThrow(new IOException("Unreadable optional cache"));
			assertThatThrownBy(TherapiJavadocHelper::loadTherapiCache).isInstanceOf(CliMetadataException.class);
		}
	}

	private void runCacheProcess(String mode, File cacheFile) throws Exception {
		String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
		List<String> command = new ArrayList<>();
		Collections.addAll(command, new File(new File(System.getProperty("java.home"), "bin"), executable).getAbsolutePath(),
				"-cp", System.getProperty("java.class.path"), CacheProcess.class.getName(), mode, cacheFile.getAbsolutePath());
		File log = new File(temporaryDirectory, mode + ".log");
		Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start();
		boolean exited = process.waitFor(20, TimeUnit.SECONDS);
		if (!exited) {
			process.destroyForcibly();
		}
		String output = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
		assertThat(exited).as(output).isTrue();
		assertThat(process.exitValue()).as(output).isEqualTo(0);
		assertThat(output).contains("write".equals(mode) ? "cache written" : "restored cached subject");
	}

	private static Map<String, MethodJavadoc> subjectCache() throws Exception {
		Method method = EmailPopulatingBuilder.class.getMethod("withSubject", String.class);
		Map<String, MethodJavadoc> cache = new LinkedHashMap<>();
		cache.put(method.toString(), RuntimeJavadoc.getJavadoc(method));
		return cache;
	}

	public static class CacheProcess {
		public static void main(String[] args) throws Exception {
			File cacheFile = new File(args[1]);
			if ("write".equals(args[0])) {
				Files.write(cacheFile.toPath(), TherapiJavadocHelper.serializeCache(subjectCache()));
				System.out.println("cache written");
			} else {
				Map<String, MethodJavadoc> cache = TherapiJavadocHelper.loadTherapiCache(Files.readAllBytes(cacheFile.toPath()));
				Method subject = EmailPopulatingBuilder.class.getMethod("withSubject", String.class);
				if (cache.size() != 1 || !cache.containsKey(subject.toString())
						|| !cache.get(subject.toString()).getComment().toString().contains("optional subject")) {
					throw new AssertionError("Cold cache read discarded the persisted Javadocs");
				}
				System.out.println("restored cached subject");
			}
		}
	}
}
