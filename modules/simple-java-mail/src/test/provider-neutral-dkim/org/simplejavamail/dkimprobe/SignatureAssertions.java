package org.simplejavamail.dkimprobe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bouncycastle.jce.provider.BouncyCastleProvider;


/**
 * Verifies emitted bytes independently of the production signing and
 * canonicalization code. ISO-8859-1 is used only as a reversible byte mapping.
 */
final class SignatureAssertions {

	private SignatureAssertions() {
	}

	static void assertValidSignature(byte[] message, PublicKey key) throws Exception {
		String wire = new String(message, StandardCharsets.ISO_8859_1);
		int separator = wire.indexOf("\r\n\r\n");
		assertTrue(separator >= 0, "Message must contain a CRLF header/body separator");
		List<String> headers = readHeaders(wire.substring(0, separator));
		int signatureIndex = -1;
		for (int i = 0; i < headers.size(); i++) {
			if (fieldName(headers.get(i)).equals("dkim-signature")) {
				signatureIndex = i;
				break;
			}
		}
		assertTrue(signatureIndex >= 0, "Message must contain a DKIM signature");
		String signatureHeader = headers.get(signatureIndex);
		Map<String, String> tags = parseTags(signatureHeader);
		String algorithm = requiredTag(tags, "a");
		String[] canonicalizations = tags.getOrDefault("c", "simple/simple").split("/", -1);
		String headerMode = canonicalizations[0];
		String bodyMode = canonicalizations.length == 1 ? "simple" : canonicalizations[1];

		byte[] body = canonicalizeBody(wire.substring(separator + 4), bodyMode);
		if (tags.containsKey("l")) {
			int length = Integer.parseInt(tags.get("l"));
			assertTrue(length >= 0 && length <= body.length, "l= must be a valid canonical body octet count");
			body = Arrays.copyOf(body, length);
		}
		String hashAlgorithm = algorithm.equals("rsa-sha1") ? "SHA-1" : "SHA-256";
		assertArrayEquals(decode(requiredTag(tags, "bh")), MessageDigest.getInstance(hashAlgorithm).digest(body),
				"DKIM body hash must match the emitted body bytes");

		ByteArrayOutputStream signedHeaders = new ByteArrayOutputStream();
		boolean[] consumed = new boolean[headers.size()];
		consumed[signatureIndex] = true;
		for (String name : requiredTag(tags, "h").split(":")) {
			for (int i = headers.size() - 1; i >= 0; i--) {
				if (!consumed[i] && fieldName(headers.get(i)).equalsIgnoreCase(name.trim())) {
					signedHeaders.write(canonicalizeHeader(headers.get(i), headerMode));
					signedHeaders.write(new byte[] {'\r', '\n'});
					consumed[i] = true;
					break;
				}
			}
		}
		int colon = signatureHeader.indexOf(':');
		String emptySignature = signatureHeader.substring(0, colon + 1)
				+ signatureHeader.substring(colon + 1).replaceFirst("(?i)(^|;)([ \\t\\r\\n]*b[ \\t]*=)[^;]*", "$1$2");
		signedHeaders.write(canonicalizeHeader(emptySignature, headerMode));
		byte[] signedBytes = signedHeaders.toByteArray();
		Signature verifier;
		if (algorithm.equals("ed25519-sha256")) {
			// RFC 8463 signs the SHA-256 header digest using pure Ed25519.
			verifier = Signature.getInstance("Ed25519", new BouncyCastleProvider());
			signedBytes = MessageDigest.getInstance("SHA-256").digest(signedBytes);
		} else {
			assertTrue(algorithm.equals("rsa-sha1") || algorithm.equals("rsa-sha256"), "Unsupported test algorithm: " + algorithm);
			verifier = Signature.getInstance(algorithm.equals("rsa-sha1") ? "SHA1withRSA" : "SHA256withRSA");
		}
		verifier.initVerify(key);
		verifier.update(signedBytes);
		assertTrue(verifier.verify(decode(requiredTag(tags, "b"))), "DKIM header signature must verify against emitted headers");
	}

	private static List<String> readHeaders(String headerBlock) {
		List<String> headers = new ArrayList<String>();
		for (String line : headerBlock.split("\r\n", -1)) {
			if ((line.startsWith(" ") || line.startsWith("\t")) && !headers.isEmpty()) {
				int last = headers.size() - 1;
				headers.set(last, headers.get(last) + "\r\n" + line);
			} else {
				assertTrue(line.indexOf(':') > 0, "Malformed test message header: " + line);
				headers.add(line);
			}
		}
		return headers;
	}

	private static String fieldName(String header) {
		return header.substring(0, header.indexOf(':')).replaceAll("[ \\t]+$", "").toLowerCase(Locale.ROOT);
	}

	private static Map<String, String> parseTags(String header) {
		Map<String, String> tags = new LinkedHashMap<String, String>();
		String unfolded = header.substring(header.indexOf(':') + 1).replaceAll("\r\n[ \\t]+", " ");
		for (String part : unfolded.split(";")) {
			int equals = part.indexOf('=');
			if (equals >= 0) {
				String name = part.substring(0, equals).trim();
				assertTrue(!tags.containsKey(name), "Duplicate DKIM tag: " + name);
				tags.put(name, part.substring(equals + 1).trim());
			}
		}
		assertEquals("1", requiredTag(tags, "v"), "DKIM version");
		return tags;
	}

	private static String requiredTag(Map<String, String> tags, String name) {
		String value = tags.get(name);
		assertNotNull(value, "Missing DKIM tag: " + name);
		return value;
	}

	private static byte[] decode(String base64) {
		return Base64.getDecoder().decode(base64.replaceAll("[ \\t\\r\\n]", ""));
	}

	private static byte[] canonicalizeHeader(String header, String mode) {
		assertTrue(mode.equals("simple") || mode.equals("relaxed"), "Unknown header canonicalization: " + mode);
		if (mode.equals("relaxed")) {
			int colon = header.indexOf(':');
			String value = header.substring(colon + 1).replaceAll("\r\n[ \\t]+", " ").replaceAll("[ \\t]+", " ");
			value = value.replaceAll("^[ \\t]+|[ \\t]+$", "");
			header = fieldName(header) + ":" + value;
		}
		return header.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] canonicalizeBody(String body, String mode) {
		assertTrue(mode.equals("simple") || mode.equals("relaxed"), "Unknown body canonicalization: " + mode);
		String[] lines = body.split("\r\n", -1);
		if (mode.equals("relaxed")) {
			for (int i = 0; i < lines.length; i++) {
				lines[i] = lines[i].replaceAll("[ \\t]+$", "").replaceAll("[ \\t]+", " ");
			}
		}
		int last = lines.length - 1;
		while (last >= 0 && lines[last].isEmpty()) {
			last--;
		}
		StringBuilder canonical = new StringBuilder();
		for (int i = 0; i <= last; i++) {
			canonical.append(lines[i]).append("\r\n");
		}
		if (last < 0 && mode.equals("simple")) {
			canonical.append("\r\n");
		}
		return canonical.toString().getBytes(StandardCharsets.ISO_8859_1);
	}
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertNotNull(Object value, String message) { assertTrue(value != null, message); }
    private static void assertEquals(Object expected, Object actual, String message) { assertTrue(java.util.Objects.equals(expected, actual), message); }
    private static void assertArrayEquals(byte[] expected, byte[] actual, String message) { assertTrue(java.util.Arrays.equals(expected, actual), message); }}
