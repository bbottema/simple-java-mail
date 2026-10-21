package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.MethodJavadoc;
import com.github.therapi.runtimejavadoc.RuntimeJavadoc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.internal.clisupport.CliDataLocator;
import org.simplejavamail.internal.clisupport.serialization.JavadocCacheSerialization;
import org.simplejavamail.internal.clisupport.serialization.SerializationUtil;

import java.io.File;
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

		TherapiJavadocHelper.persistCache(cacheFile, cache);
		Map<String, MethodJavadoc> restored = TherapiJavadocHelper.loadTherapiCache(cacheFile);

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
	void bundledCacheContainsReadableJavadocsRatherThanRelyingOnFallback() throws Exception {
		File cacheFile = new File(CliDataLocator.locateTherapiDataFile());
		Map<String, MethodJavadoc> cache = JavadocCacheSerialization.deserialize(Files.readAllBytes(cacheFile.toPath()));
		Method subject = EmailPopulatingBuilder.class.getMethod("withSubject", String.class);
		assertThat(cache).containsKey(subject.toString());
		assertThat(cache.get(subject.toString()).getComment().toString()).contains("optional subject");
	}

	@Test
	void legacyAndCorruptCacheFilesStillAllowEmbeddedJavadocFallback() throws Exception {
		Map<String, MethodJavadoc> cache = subjectCache();
		File legacy = new File(temporaryDirectory, "legacy.data");
		Files.write(legacy.toPath(), SerializationUtil.serialize(cache));
		assertThat(TherapiJavadocHelper.loadTherapiCache(legacy)).isEmpty();

		File corrupt = new File(temporaryDirectory, "corrupt.data");
		byte[] valid = JavadocCacheSerialization.serialize(cache);
		Files.write(corrupt.toPath(), java.util.Arrays.copyOf(valid, valid.length - 1));
		assertThat(TherapiJavadocHelper.loadTherapiCache(corrupt)).isEmpty();
	}

	@Test
	void missingAndUnreadableOptionalCacheFilesAllowFallback() {
		assertThat(TherapiJavadocHelper.loadTherapiCache(new File(temporaryDirectory, "missing.data"))).isEmpty();
		assertThat(TherapiJavadocHelper.loadTherapiCache(temporaryDirectory)).isEmpty();
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
				TherapiJavadocHelper.persistCache(cacheFile, subjectCache());
				System.out.println("cache written");
			} else {
				Map<String, MethodJavadoc> cache = TherapiJavadocHelper.loadTherapiCache(cacheFile);
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
