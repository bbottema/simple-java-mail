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
