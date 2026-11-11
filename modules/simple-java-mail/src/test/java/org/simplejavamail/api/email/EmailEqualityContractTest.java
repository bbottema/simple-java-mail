package org.simplejavamail.api.email;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.config.DkimConfig;
import testutil.ConfigLoaderTestHelper;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EmailEqualityContractTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

	@Test
	void unsignedAndDkimConfiguredMessagesAreDifferentInBothDirections() {
		final Email unsigned = message().buildEmail();
		final Email signed = message().signWithDomainKey(dkim("primary")).buildEmail();

		assertThat(unsigned).isNotEqualTo(signed);
		assertThat(signed).isNotEqualTo(unsigned);
	}

	@Test
	void differentDkimSettingsMakeOtherwiseIdenticalMessagesDifferent() {
		final Email first = message().signWithDomainKey(dkim("primary")).buildEmail();
		final Email second = message().signWithDomainKey(dkim("alternate")).buildEmail();

		assertThat(first).isNotEqualTo(second);
		assertThat(second).isNotEqualTo(first);
	}

	@Test
	void equalDkimSettingsAndCopiesRemainEqual() {
		final Email first = message().signWithDomainKey(dkim("primary")).buildEmail();
		final Email equivalent = message().signWithDomainKey(dkim("primary")).buildEmail();
		final Email copied = mail.emailBuilder().copying(first).buildEmail();

		assertThat(first).isEqualTo(equivalent).isEqualTo(copied);
		assertThat(equivalent).isEqualTo(first);
		assertThat(copied).isEqualTo(first);
		assertThat(first.hashCode()).isEqualTo(copied.hashCode());
	}

	@ParameterizedTest
	@ValueSource(strings = {"attachments", "images"})
	void oneResourceDiffersFromTwoOccurrencesOfThatResource(final String resourceType) {
		final AttachmentResource resource = resource("first", new AtomicInteger());
		final Email once = withResources(resourceType, List.of(resource));
		final Email twice = withResources(resourceType, List.of(resource, resource));

		assertThat(once).isNotEqualTo(twice);
		assertThat(twice).isNotEqualTo(once);
	}

	@ParameterizedTest
	@ValueSource(strings = {"attachments", "images"})
	void equalLengthListsMustHaveMatchingResourceOccurrences(final String resourceType) {
		final AttachmentResource first = resource("first", new AtomicInteger());
		final AttachmentResource second = resource("second", new AtomicInteger());
		final Email firstRepeated = withResources(resourceType, List.of(first, second, first));
		final Email secondRepeated = withResources(resourceType, List.of(first, second, second));

		assertThat(firstRepeated).isNotEqualTo(secondRepeated);
		assertThat(secondRepeated).isNotEqualTo(firstRepeated);
	}

	@ParameterizedTest
	@ValueSource(strings = {"attachments", "images"})
	void matchingResourceOccurrencesRemainOrderInsensitive(final String resourceType) {
		final AttachmentResource first = resource("first", new AtomicInteger());
		final AttachmentResource second = resource("second", new AtomicInteger());
		final Email original = withResources(resourceType, List.of(first, second, first));
		final Email reordered = withResources(resourceType, List.of(first, first, second));

		assertThat(original).isEqualTo(reordered);
		assertThat(reordered).isEqualTo(original);
		assertThat(original.hashCode()).isEqualTo(reordered.hashCode());
	}

	@ParameterizedTest
	@ValueSource(strings = {"attachments", "images"})
	void resourceEqualityStillUsesMetadataWithoutReadingBorrowedContent(final String resourceType) {
		final AtomicInteger reads = new AtomicInteger();
		final Email first = withResources(resourceType, List.of(resource("first", reads), resource("first", reads)));
		final Email equivalent = withResources(resourceType, List.of(resource("first", reads), resource("first", reads)));

		assertThat(first).isEqualTo(equivalent);
		assertThat(equivalent).isEqualTo(first);
		assertThat(reads).hasValue(0);
	}

    @ParameterizedTest
    @ValueSource(strings = {"To", "Cc", "Bcc", "Reply-To"})
    void richRecipientHeadersDoNotReintroduceOrderButStillCountOccurrences(final String header) {
        final Email first = received(Map.of(header, "first@example.org, second@example.org, first@example.org"), "");
        final Email reordered = received(Map.of(header, "first@example.org, first@example.org, second@example.org"), "");
        final Email differentOccurrences = received(Map.of(header, "first@example.org, second@example.org, second@example.org"), "");
        assertThat(first.getHeaders().get(header)).isNotEqualTo(reordered.getHeaders().get(header));
        assertThat(first).isEqualTo(reordered);
        assertThat(reordered).isEqualTo(first).isNotEqualTo(differentOccurrences);
        assertThat(differentOccurrences).isNotEqualTo(first);
    }

    @Test
    void newlyReadableDeliveryAuthenticationAndMimeHistoryAreNotAdditionalComparands() {
        final Map<String, String> firstHeaders = Map.of("Received", "from first.example.org", "Authentication-Results", "first.example.org; dkim=pass",
                "DKIM-Signature", "first source signature", "ARC-Seal", "first source seal", "Content-Length", "4", "Content-MD5", "AAAA",
                "Content-Type", "text/plain; charset=UTF-8", "MIME-Version", "1.0 (first source)");
        final Map<String, String> secondHeaders = Map.of("Received", "from second.example.org", "Authentication-Results", "second.example.org; dkim=fail",
                "DKIM-Signature", "second source signature", "ARC-Seal", "second source seal", "Content-Length", "40", "Content-MD5", "BBBB",
                "Content-Type", "text/plain; charset=utf-8", "MIME-Version", "1.0 (second source)");
        final Email first = received(firstHeaders, "");
        final Email second = received(secondHeaders, "");
        assertThat(first.getHeaders()).isNotEqualTo(second.getHeaders());
        assertThat(first.getSourceData().getOrigin().getReference()).isNotEqualTo(second.getSourceData().getOrigin().getReference());
        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Date", "Return-Path"})
    void unavailableTypedValuesDoNotAddSourceOnlyRawFallbackComparands(final String header) {
        final Email first = received(Map.of(header, "uninterpretable first value"), "");
        final Email second = received(Map.of(header, "uninterpretable second value"), "");
        if (header.equals("Date")) {
            assertThat(first.getSentDate()).isNull();
            assertThat(second.getSentDate()).isNull();
        } else {
            assertThat(first.getBounceToRecipient()).isNull();
            assertThat(second.getBounceToRecipient()).isNull();
        }
        assertThat(first.getHeaders().get(header)).isNotEqualTo(second.getHeaders().get(header));
        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void typedIdentityDateAndBounceValuesKeepTheirExistingComparison() {
        final Email first = received(Map.of("Return-Path", "<bounce@example.org>"), "");
        assertThat(first).isNotEqualTo(received(Map.of("Return-Path", "<bounce@example.org>", "Message-ID", "<other@example.org>"), ""));
        assertThat(first).isNotEqualTo(received(Map.of("Return-Path", "<bounce@example.org>", "Date", "Thu, 1 Jan 1970 00:00:02 +0000"), ""));
        assertThat(first).isNotEqualTo(received(Map.of("Return-Path", "<other-bounce@example.org>"), ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"References", "In-Reply-To", "Sender", "Comments", "Keywords"})
    void semanticAdditionalAuthoredHeadersRemainComparands(final String header) {
        final Email first = received(Map.of(header, "<first@example.org>"), "");
        final Email second = received(Map.of(header, "<second@example.org>"), "");
        assertThat(first).isNotEqualTo(second);
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void customHeaderNamesAreCaseInsensitiveWhileTheirOccurrenceOrderAndEmptyValuesRemainMeaningful() {
        final Email first = received(Map.of(), "X-Route: first\r\nX-Route: second\r\n");
        final Email same = received(Map.of(), "x-route: first\r\nx-route: second\r\n");
        final Email reversed = received(Map.of(), "X-Route: second\r\nX-Route: first\r\n");
        assertThat(first).isEqualTo(same).isNotEqualTo(reversed);
        assertThat(received(Map.of(), "X-Empty: \r\n")).isNotEqualTo(received(Map.of(), ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "filename", "size"})
    void standaloneCustomNamesRemainExplicitComparableChoices(final String header) {
        final Email first = message().withHeader(header, "first").buildEmail();
        final Email second = message().withHeader(header, "second").buildEmail();
        assertThat(first).isNotEqualTo(second).isNotEqualTo(message().buildEmail());
        assertThat(mail.emailBuilder().copying(first).buildEmail()).isEqualTo(first);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Disposition-Notification-To", "Return-Receipt-To"})
    void explicitGenericNotificationChoicesRemainSemanticallyDistinct(final String header) {
        final Email first = message().withHeader(header, "first@example.org").buildEmail();
        final Email second = message().withHeader(header, "second@example.org").buildEmail();
        assertThat(first).isNotEqualTo(second);
        assertThat(second).isNotEqualTo(first);
    }

    private Email received(final Map<String, String> replacements, final String additional) {
        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("From", "author@example.org");
        headers.put("To", "reader@example.org");
        headers.put("Subject", "Same message");
        headers.put("Message-ID", "<equality@example.org>");
        headers.put("Date", "Thu, 1 Jan 1970 00:00:01 +0000");
        headers.put("MIME-Version", "1.0");
        headers.put("Content-Type", "text/plain; charset=UTF-8");
        headers.put("Content-Transfer-Encoding", "7bit");
        headers.putAll(replacements);
        final StringBuilder source = new StringBuilder();
        headers.forEach((name, value) -> source.append(name).append(": ").append(value).append("\r\n"));
        return mail.converter().fromEml(source.append(additional).append("\r\nbody").toString()).toEmail();
    }

	private EmailPopulatingBuilder message() {
		return mail.emailBuilder().startingBlank().withSubject("Same message").withPlainText("body");
	}

	private Email withResources(final String resourceType, final List<AttachmentResource> resources) {
		final EmailPopulatingBuilder builder = message();
		switch (resourceType) {
			case "attachments": return builder.withAttachments(resources).buildEmail();
			case "images": return builder.withEmbeddedImages(resources).buildEmail();
			default: throw new IllegalArgumentException("Unknown resource list: " + resourceType);
		}
	}

	private static DkimConfig dkim(final String selector) {
		return DkimConfig.builder()
				.dkimPrivateKeyData(new byte[] {1, 2, 3})
				.dkimSigningDomain("example.org")
				.dkimSelector(selector)
				.build();
	}

	private static AttachmentResource resource(final String name, final AtomicInteger reads) {
		return AttachmentResource.builder().withName(name).withDataSource(new DataSource() {
			@Override public InputStream getInputStream() {
				reads.incrementAndGet();
				throw new AssertionError("Comparing an Email must not read borrowed content");
			}
			@Override public OutputStream getOutputStream() { throw new UnsupportedOperationException("read-only fixture"); }
			@Override public String getContentType() { return "image/png"; }
			@Override public String getName() { return name; }
		}).build();
	}
}
