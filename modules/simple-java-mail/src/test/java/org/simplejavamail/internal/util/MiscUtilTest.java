package org.simplejavamail.internal.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.activation.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.clisupport.model.Cli;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.regex.Pattern.compile;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.util.MiscUtil.findFirstMatch;

public class MiscUtilTest {

	@Test
	public void checkArgumentNotEmpty() {
		assertThat(MiscUtil.checkArgumentNotEmpty("blah", null)).isEqualTo("blah");
		assertThat(MiscUtil.checkArgumentNotEmpty(234, null)).isEqualTo(234);
	}
	
	@Test
	public void checkArgumentNotEmptyWithEmptyString() {
		assertThatThrownBy(() -> MiscUtil.checkArgumentNotEmpty("", null))
				.isInstanceOf(IllegalArgumentException.class);
	}
	
	@Test
	public void checkArgumentNotEmptyWithNullString() {
		assertThatThrownBy(() -> MiscUtil.checkArgumentNotEmpty(null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}
	
	@Test
	public void valueNullOrEmpty() {
		assertThat(MiscUtil.valueNullOrEmpty("")).isTrue();
		assertThat(MiscUtil.valueNullOrEmpty(null)).isTrue();
		assertThat(MiscUtil.valueNullOrEmpty("blah")).isFalse();
		assertThat(MiscUtil.valueNullOrEmpty(2534)).isFalse();
		assertThat(MiscUtil.valueNullOrEmpty(new ArrayList<>())).isTrue();
	}

	@Test
	public void testBuildLogString() {
		assertThat(MiscUtil.buildLogStringForSOCKSCommunication(new byte[] { 1, 2, 3 }, true)).isEqualTo("Received: 1 2 3 ");
		assertThat(MiscUtil.buildLogStringForSOCKSCommunication(new byte[] { 32, 121, 101 }, false)).isEqualTo("Sent: 20 79 65 ");
	}

	@Test
	public void testToInt() {
		assertThat(MiscUtil.toInt((byte) -1)).isEqualTo(255);
		assertThat(MiscUtil.toInt((byte) 0)).isEqualTo(0);
		assertThat(MiscUtil.toInt((byte) 1)).isEqualTo(1);
		assertThat(MiscUtil.toInt((byte) 10)).isEqualTo(10);
		assertThat(MiscUtil.toInt((byte) 100)).isEqualTo(100);
		assertThat(MiscUtil.toInt((byte) -100)).isEqualTo(156);
		assertThat(MiscUtil.toInt((byte) -10)).isEqualTo(246);
	}

	@Test
	public void testEncodeText() {
		assertThat(MiscUtil.encodeText(null)).isNull();
		assertThat(MiscUtil.encodeText("moo moo")).isEqualTo("moo moo");
		assertThat(MiscUtil.encodeText("<html><body>moo</body></html>")).isEqualTo("<html><body>moo</body></html>");
		assertThat(MiscUtil.encodeText("moo moo\u0207")).isEqualTo("=?UTF-8?B?bW9vIG1vb8iH?=");
		assertThat(MiscUtil.encodeText("<html><body>\u0207</body></html>")).isEqualTo("=?UTF-8?B?PGh0bWw+PGJvZHk+yIc8L2JvZHk+PC9odG1sPg==?=");
	}

	@Test
	public void testExtractEmailAddresses_MissingAddress() {
		assertThatThrownBy(() -> MiscUtil.extractEmailAddresses(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void testExtractEmailAddresses_EmptyAddress() {
		assertThatThrownBy(() -> MiscUtil.extractEmailAddresses(""))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	public void testExtractCID() {
		assertThat(MiscUtil.extractCID(null)).isNull();
		assertThat(MiscUtil.extractCID("")).isEqualTo("");
		assertThat(MiscUtil.extractCID("moo")).isEqualTo("moo");
		assertThat(MiscUtil.extractCID("<moo>")).isEqualTo("moo");
	}

	@Test
	public void testReadInputStreamToString()
			throws IOException {
		ByteArrayInputStream i = new ByteArrayInputStream(new byte[] { 'm', 'o', 'o', 'm', 'o', 'o', '1', '2', '3' });
		assertThat(MiscUtil.readInputStreamToString(i, UTF_8)).isEqualTo("moomoo123");
	}

	@Test
	public void testReadInputStreamToBytes()
			throws IOException {
		final byte[] input = { 'm', 'o', 'o', 'm', 'o', 'o', '1', '2', '3' };
		ByteArrayInputStream i = new ByteArrayInputStream(input);
		assertThat(MiscUtil.readInputStreamToBytes(i)).isEqualTo(input);
	}

	@Test
	public void testExtractEmailAddresses_SingleAddress() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a@b.com");
		assertThat(singleAddressList).hasSize(1);
		assertThat(singleAddressList).contains("a@b.com");
	}

	@Test
	public void testClassAvailable() {
		assertThat(MiscUtil.classAvailable("moomoo.MooMoo")).isFalse();
		assertThat(MiscUtil.classAvailable("java.util.AbstractList")).isTrue();
	}

	@Test
	public void testZip() {
		final Map.Entry<Integer, Float>[] zip = MiscUtil.zip(new Integer[] { 1, 2, 3 }, new Float[] { 1.2f, 2.3f, 3.4f });
		assertThat(zip).containsExactly(
				new AbstractMap.SimpleEntry<>(1, 1.2f),
				new AbstractMap.SimpleEntry<>(2, 2.3f),
				new AbstractMap.SimpleEntry<>(3, 3.4f)
		);
	}

	@Test
	public void testNormalizeNewlines() {
		assertThat(MiscUtil.normalizeNewlines(null)).isNull();
		assertThat(MiscUtil.normalizeNewlines("123")).isEqualTo("123");
		assertThat(MiscUtil.normalizeNewlines("123\n")).isEqualTo("123\n");
		assertThat(MiscUtil.normalizeNewlines("123\r\n")).isEqualTo("123\n");
		assertThat(MiscUtil.normalizeNewlines("123\r")).isEqualTo("123\n");
	}

	@Test
	@SuppressWarnings("unused")
	public void testCountMandatoryParameters() throws NoSuchMethodException {
		Method methodWithZeroParameters = new Object() {public void methodWithZeroParameters() {}}.getClass().getDeclaredMethod("methodWithZeroParameters");
		Method methodWithZeroMandatoryParameters = new Object() {public void methodWithZeroMandatoryParameters(@Nullable @Cli.Optional Integer optionalInt) {}}.getClass().getDeclaredMethod("methodWithZeroMandatoryParameters", Integer.class);
		Method methodWithOnlyMandatoryParameters = new Object() {public void methodWithOnlyMandatoryParameters(Integer mandatoryInt) {}}.getClass().getDeclaredMethod("methodWithOnlyMandatoryParameters", Integer.class);
		Method methodWithMixedMandatoryParameters = new Object() {public void methodWithMixedMandatoryParameters(@Nullable @Cli.Optional Integer optionalInt, Integer mandatoryInt) {}}.getClass().getDeclaredMethod("methodWithMixedMandatoryParameters", Integer.class, Integer.class);
		Method methodWithNullableParameter = new Object() {public void methodWithNullableParameter(@Nullable Integer nullableInt) {}}.getClass().getDeclaredMethod("methodWithNullableParameter", Integer.class);

		assertThat(MiscUtil.countMandatoryParameters(methodWithZeroParameters)).isEqualTo(0);
		assertThat(MiscUtil.countMandatoryParameters(methodWithZeroMandatoryParameters)).isEqualTo(0);
		assertThat(MiscUtil.countMandatoryParameters(methodWithOnlyMandatoryParameters)).isEqualTo(1);
		assertThat(MiscUtil.countMandatoryParameters(methodWithMixedMandatoryParameters)).isEqualTo(1);
		assertThat(MiscUtil.countMandatoryParameters(methodWithNullableParameter)).isEqualTo(1);
	}

	@Test
	public void testReadFileContent()
			throws IOException {
		assertThatThrownBy(() -> FileUtil.readFileContent(new File("moo")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("File not found: moo");

		assertThat(FileUtil.readFileContent(new File("src/test/resources/ignore.properties")))
				.contains("simplejavamail.defaults.bcc.address=moo");
	}

	@Test
	public void testWriteFileContent()
			throws IOException {
		FileUtil.writeFileBytes(new File("target/test.file"), "This is a test".getBytes());

		assertThat(FileUtil.readFileBytes(new File("target/test.file")))
				.isEqualTo("This is a test".getBytes());
		assertThat(FileUtil.readFileContent(new File("target/test.file")))
				.isEqualTo("This is a test");
	}

	@Test
	public void testExtractEmailAddresses_MultipleAddressesWithCommas() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a1@b.com,a2@b.com,a3@b.com");
		assertThat(singleAddressList).hasSize(3);
		assertThat(singleAddressList).contains("a1@b.com", "a2@b.com", "a3@b.com");
	}
	
	@Test
	public void testExtractEmailAddresses_MultipleAddressesWithSemicolons() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a1@b.com;a2@b.com;a3@b.com");
		assertThat(singleAddressList).hasSize(3);
		assertThat(singleAddressList).contains("a1@b.com", "a2@b.com", "a3@b.com");
	}
	
	@Test
	public void testExtractEmailAddresses_MultipleAddressesMixedCommasAndSemicolons() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a1@b.com,a2@b.com;a3@b.com;a4@b.com,a5@b.com");
		assertThat(singleAddressList).hasSize(5);
		assertThat(singleAddressList).contains("a1@b.com", "a2@b.com", "a3@b.com", "a4@b.com", "a5@b.com");
	}
	
	@Test
	public void testExtractEmailAddresses_MultipleAddressesTralingSpaces() {
		String[] singleAddressList = MiscUtil.extractEmailAddresses("a1@b.com, a2@b.com ;a3@b.com;a4@b.com , a5@b.com,a6@b.com");
		assertThat(singleAddressList).hasSize(6);
		assertThat(singleAddressList).contains("a1@b.com", "a2@b.com", "a3@b.com", "a4@b.com", "a5@b.com", "a6@b.com");
	}
	
	@Test
	public void testExtractEmailAddresses() {
		String testInput = "name@domain.com,Sixpack, \"Joe 1\" <name@domain.com>, Sixpack, Joe 2 <name@domain.com> ;Sixpack, Joe, 3<name@domain" +
				".com> , nameFoo@domain.com,nameBar@domain.com;nameBaz@domain.com; \" Joe Sixpack 4 \"  <name@domain.com>;";
		assertThat(MiscUtil.extractEmailAddresses(testInput)).containsExactlyInAnyOrder(
				"name@domain.com",
				"Sixpack, \"Joe 1\" <name@domain.com>",
				"Sixpack, Joe 2 <name@domain.com>",
				"Sixpack, Joe, 3<name@domain.com>",
				"nameFoo@domain.com",
				"nameBar@domain.com",
				"nameBaz@domain.com",
				"\" Joe Sixpack 4 \"  <name@domain.com>"
		);
	}
	
	@Test
	public void testAddRecipientByInternetAddress() {
		assertThat(MiscUtil.interpretRecipient(null, false, "a@b.com", null)).isEqualTo(new Recipient(null, "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, " a@b.com ", null)).isEqualTo(new Recipient(null, "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, " <a@b.com> ", null)).isEqualTo(new Recipient(null, "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, " < a@b.com > ", null)).isEqualTo(new Recipient(null, "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, "moo <a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, "moo<a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, " moo< a@b.com   > ", null)).isEqualTo(new Recipient("moo", "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, "\"moo\" <a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, "\"moo\"<a@b.com>", null)).isEqualTo(new Recipient("moo", "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, " \"moo\"< a@b.com   > ", null)).isEqualTo(new Recipient("moo", "a@b.com", null, null));
		assertThat(MiscUtil.interpretRecipient(null, false, " \"  m oo  \"< a@b.com   > ", null)).isEqualTo(new Recipient("  m oo  ", "a@b.com", null, null));
		// next one is unparsable by InternetAddress#parse(), so it should be taken as is
		assertThat(MiscUtil.interpretRecipient(null, false, " \"  m oo  \" a@b.com    ", null)).isEqualTo(new Recipient(null, " \"  m oo  \" a@b.com    ", null, null));
	}

	@Test
	public void testFindFirstMatch() {
		assertThat(findFirstMatch(compile("method=(\\w+)"), "Content-Type: text/calendar; method=REQUEST; charset=UTF-8")).hasValue("REQUEST");
        assertThat(findFirstMatch(compile("method=(\\w+)"), "Content-Type: text/calendar; charset=UTF-8")).isEmpty();
        assertThat(findFirstMatch(compile("method=(\\w+)"), "")).isEmpty();
        assertThat(findFirstMatch(compile("method=(\\w+)"), "Content-Type: text/calendar; method=RE$QUEST; charset=UTF-8")).isNotEmpty();
        assertThat(findFirstMatch(compile("method=(\\w+)"), "method=REJECT; method=REQUEST")).hasValue("REJECT");
        assertThat(findFirstMatch(compile("(?i)method=(\\w+)"), "Content-Type: text/calendar; METHOD=REQUEST; charset=UTF-8")).hasValue("REQUEST");
	}

	@Test
	public void embeddedImageDiskResolutionHonorsRealBaseDirectory(@TempDir final Path tempDir)
			throws IOException {
		final Path baseDir = Files.createDirectory(tempDir.resolve("assets"));
		final Path insideFile = Files.write(baseDir.resolve("inside.txt"), "inside".getBytes(UTF_8));
		Files.write(tempDir.resolve("secret.txt"), "outside".getBytes(UTF_8));
		final Path siblingDir = Files.createDirectory(tempDir.resolve("assets-private"));
		final Path siblingFile = Files.write(siblingDir.resolve("secret.txt"), "sibling".getBytes(UTF_8));

		assertThat(readDataSource(MiscUtil.tryResolveImageFileDataSourceFromDisk(baseDir.toString(), false, "inside.txt"))).isEqualTo("inside");
		assertThat(MiscUtil.tryResolveImageFileDataSourceFromDisk(baseDir.toString(), false, "../secret.txt")).isNull();
		assertThat(MiscUtil.tryResolveImageFileDataSourceFromDisk(baseDir.toString(), false, siblingFile.toString())).isNull();
		assertThat(readDataSource(MiscUtil.tryResolveImageFileDataSourceFromDisk(baseDir.toString(), true, "../secret.txt"))).isEqualTo("outside");
		assertThat(readDataSource(MiscUtil.tryResolveImageFileDataSourceFromDisk(baseDir.toString(), true, siblingFile.toString()))).isEqualTo("sibling");
		assertThat(readDataSource(MiscUtil.tryResolveImageFileDataSourceFromDisk(null, false, insideFile.toString()))).isEqualTo("inside");
		assertThat(MiscUtil.tryResolveImageFileDataSourceFromDisk(tempDir.resolve("missing-base").toString(), false, insideFile.toString())).isNull();
		assertThat(readDataSource(MiscUtil.tryResolveImageFileDataSourceFromDisk(tempDir.resolve("missing-base").toString(), true, insideFile.toString()))).isEqualTo("inside");
	}

	@Test
	public void embeddedImageDiskResolutionRejectsSymlinkEscapes(@TempDir final Path tempDir)
			throws IOException {
		final Path baseDir = Files.createDirectory(tempDir.resolve("assets"));
		final Path outsideDir = Files.createDirectory(tempDir.resolve("private"));
		Files.write(outsideDir.resolve("secret.txt"), "outside".getBytes(UTF_8));
		try {
			Files.createSymbolicLink(baseDir.resolve("linked-private"), outsideDir);
		} catch (UnsupportedOperationException | IOException | SecurityException e) {
			assumeTrue(false, "Symbolic links are not available in this test environment: " + e.getMessage());
		}

		assertThat(MiscUtil.tryResolveImageFileDataSourceFromDisk(baseDir.toString(), false, "linked-private/secret.txt")).isNull();
		assertThat(readDataSource(MiscUtil.tryResolveImageFileDataSourceFromDisk(baseDir.toString(), true, "linked-private/secret.txt"))).isEqualTo("outside");
	}

	@Test
	public void embeddedImageClasspathResolutionUsesPathSegments()
			throws IOException {
		assertThat(readDataSource(MiscUtil.tryResolveFileDataSourceFromClassPath("/pkcs12", false, "/how-to.html")))
				.contains("Create Self-Signed S/MIME Certificates");
		assertThat(MiscUtil.tryResolveFileDataSourceFromClassPath("/pkcs12", false, "/pkcs12/../log4j2.xml")).isNull();
		assertThat(MiscUtil.tryResolveFileDataSourceFromClassPath("/pkcs12", false, "/pkcs12-outside/secret.txt")).isNull();
		assertThat(readDataSource(MiscUtil.tryResolveFileDataSourceFromClassPath("/pkcs12", true, "/pkcs12-outside/secret.txt"))).contains("classpath sibling");
		assertThat(readDataSource(MiscUtil.tryResolveFileDataSourceFromClassPath(null, false, "/pkcs12-outside/secret.txt"))).contains("classpath sibling");
	}

	@Test
	public void embeddedImageUrlResolutionContainsOriginPathAndRedirects()
			throws IOException {
		final HttpServer outsideServer = startContentServer(null);
		final String outsideOrigin = originOf(outsideServer);
		final HttpServer baseServer = startContentServer(outsideOrigin);
		try {
			final String baseOrigin = originOf(baseServer);
			final URL baseUrl = new URL(baseOrigin + "/assets");

			assertThat(readDataSource(MiscUtil.tryResolveUrlDataSource(baseUrl, false, "inside.png"))).isEqualTo("inside");
			assertThat(readDataSource(MiscUtil.tryResolveUrlDataSource(baseUrl, false, "redirect-inside"))).isEqualTo("inside");
			assertThat(MiscUtil.tryResolveUrlDataSource(baseUrl, false, baseOrigin + "/assets-private/secret.png")).isNull();
			assertThat(MiscUtil.tryResolveUrlDataSource(baseUrl, false, "../private/secret.png")).isNull();
			assertThat(MiscUtil.tryResolveUrlDataSource(baseUrl, false, "%2e%2e/private/secret.png")).isNull();
			assertThat(MiscUtil.tryResolveUrlDataSource(baseUrl, false, "%252e%252e/private/secret.png")).isNull();
			assertThat(MiscUtil.tryResolveUrlDataSource(baseUrl, false, "file:/assets/inside.png")).isNull();
			assertThat(MiscUtil.tryResolveUrlDataSource(baseUrl, false, "http://localhost:" + baseServer.getAddress().getPort() + "/assets/inside.png")).isNull();
			assertThat(MiscUtil.tryResolveUrlDataSource(baseUrl, false, outsideOrigin + "/assets/secret.png")).isNull();
			assertThat(MiscUtil.tryResolveUrlDataSource(baseUrl, false, "redirect-outside")).isNull();
			assertThat(MiscUtil.tryResolveUrlDataSource(baseUrl, false, "redirect-other-origin")).isNull();

			assertThat(readDataSource(MiscUtil.tryResolveUrlDataSource(baseUrl, true, baseOrigin + "/assets-private/secret.png"))).isEqualTo("outside");
			assertThat(readDataSource(MiscUtil.tryResolveUrlDataSource(baseUrl, true, outsideOrigin + "/assets/secret.png"))).isEqualTo("outside");
			assertThat(readDataSource(MiscUtil.tryResolveUrlDataSource(null, false, outsideOrigin + "/assets/secret.png"))).isEqualTo("outside");
		} finally {
			baseServer.stop(0);
			outsideServer.stop(0);
		}
	}

	private static HttpServer startContentServer(@Nullable final String redirectOrigin)
			throws IOException {
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> handleContentRequest(exchange, redirectOrigin));
		server.start();
		return server;
	}

	private static void handleContentRequest(@NotNull final HttpExchange exchange, @Nullable final String redirectOrigin)
			throws IOException {
		final String path = exchange.getRequestURI().getRawPath();
		if ("/assets/redirect-inside".equals(path)) {
			redirect(exchange, "/assets/inside.png");
			return;
		}
		if ("/assets/redirect-outside".equals(path)) {
			redirect(exchange, "/private/secret.png");
			return;
		}
		if ("/assets/redirect-other-origin".equals(path)) {
			redirect(exchange, redirectOrigin + "/assets/secret.png");
			return;
		}

		final byte[] response = ("/assets/inside.png".equals(path) ? "inside" : "outside").getBytes(UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "image/png");
		exchange.sendResponseHeaders(200, response.length);
		try (OutputStream responseBody = exchange.getResponseBody()) {
			responseBody.write(response);
		}
	}

	private static void redirect(@NotNull final HttpExchange exchange, @NotNull final String location)
			throws IOException {
		exchange.getResponseHeaders().set("Location", location);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	private static String originOf(@NotNull final HttpServer server) {
		return "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort();
	}

	private static String readDataSource(@Nullable final DataSource dataSource)
			throws IOException {
		assertThat(dataSource).isNotNull();
		try (InputStream inputStream = dataSource.getInputStream()) {
			return MiscUtil.readInputStreamToString(inputStream, UTF_8);
		}
	}
}
