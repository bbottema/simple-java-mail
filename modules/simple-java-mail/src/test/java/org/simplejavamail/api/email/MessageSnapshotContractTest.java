package org.simplejavamail.api.email;

import org.simplejavamail.api.email.config.EmailTemplateOptions;
import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.NEVER;

class MessageSnapshotContractTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
	private final Recipient recipient = mail.recipientBuilder().to("Recipient", "recipient@example.org");

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void copyingRetainsDeliveryOverridesAndRecipientMetadata(final boolean copyBuilder) {
		final X509Certificate certificate = mock(X509Certificate.class);
		final Recipient first = new Recipient("Test inbox", "test@example.org", BCC, certificate, List.of(FAILURE, DELAY));
		final Recipient second = new Recipient("Other test inbox", "other@example.org", CC, null, List.of(NEVER));
		final List<Recipient> deliveryRecipients = List.of(first, second, first);
		final EmailPopulatingBuilder source = mail.emailBuilder().startingBlank()
				.withRecipients(recipient)
				.withOverrideReceivers(deliveryRecipients)
				.withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().dontApplyOverrideValueFor(EmailProperty.HEADERS).build());
		final Email original = source.buildEmail();
		final EmailPopulatingBuilder copy = copyBuilder ? mail.emailBuilder().copying(source) : mail.emailBuilder().copying(original);
		final Email copied = copy.withSubject("Unrelated edit").buildEmail();

		assertThat(copied.getOverrideReceivers()).containsExactly(first, second, first);
		assertThat(copied.getOverrideReceivers().get(0).getSmimeCertificate()).isSameAs(certificate);
		assertThat(copied.getOverrideReceivers().get(0).getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE, DELAY);
		assertThat(copied.getOverrideReceivers().get(1).getDeliveryStatusNotificationNotifyOptions()).containsExactly(NEVER);
		assertThat(copied.getRecipients()).containsExactly(recipient);
		assertThat(copied.isIgnoreDefaults()).isTrue();
		assertThat(copied.getPropertiesNotToApplyOverrideValueFor()).containsExactly(EmailProperty.HEADERS);

		copy.clearOverrideReceivers().withOverrideReceivers(recipient);
		source.clearOverrideReceivers();
		assertThat(copied.getOverrideReceivers()).containsExactlyElementsOf(deliveryRecipients);
		assertThat(original.getOverrideReceivers()).containsExactlyElementsOf(deliveryRecipients);
	}

	@Test
	void copyingADeserializedEmailRetainsItsDeliveryOverride() throws Exception {
		final Recipient redirect = new Recipient("Test inbox", "test@example.org", TO, null, List.of(FAILURE));
		final Email restored = roundTrip(mail.emailBuilder().startingBlank()
				.withRecipients(recipient).withOverrideReceivers(redirect, redirect).buildEmail());

		final Email copied = mail.emailBuilder().copying(restored).withSubject("After deserialization").buildEmail();

		assertThat(copied.getOverrideReceivers()).containsExactly(redirect, redirect);
		assertThat(copied.getRecipients()).containsExactly(recipient);
	}

	@Test
	void buildingDetachesAllMessageCollectionsFromLaterBuilderChanges() {
		final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) mail.emailBuilder().startingBlank()
				.withRecipients(recipient).withReplyTo(recipient).withOverrideReceivers(recipient)
				.withHeader("X-Route", "first").withHeader("X-Nullable", null)
				.withAttachment("attachment", new byte[] {1}, "text/plain")
				.withEmbeddedImage("image", new byte[] {2}, "image/png");
		builder.withDecryptedAttachments(singletonList(resource()));
		final Email email = builder.buildEmail();

		builder.clearRecipients().clearReplyTo().clearOverrideReceivers().clearAttachments().clearEmbeddedImages();
		builder.withHeader("X-Route", "second", true);
		builder.clearDecryptedAttachments();

		assertThat(email.getRecipients()).containsExactly(recipient);
		assertThat(email.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly(recipient.getAddress());
		assertThat(email.getOverrideReceivers()).containsExactly(recipient);
		assertThat(email.getAttachments()).hasSize(1);
		assertThat(email.getEmbeddedImages()).hasSize(1);
		assertThat(email.getDecryptedAttachments()).hasSize(1);
		assertThat(email.getHeaders().get("X-Route")).containsExactly("first");
		assertThat(email.getHeaders().get("X-Nullable")).containsExactly((String) null);
	}

	@Test
	void emailCollectionsCannotBeChangedThroughReadAccessors() {
		final Email email = mail.emailBuilder().startingBlank().withOverrideReceivers(recipient)
				.withHeader("X-Route", "first").buildEmail();
		assertThatThrownBy(() -> email.getOverrideReceivers().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> email.getHeaders().get("X-Route").clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> email.getHeaders().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void builderGettersReturnDetachedModifiableCopies() {
		final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) mail.emailBuilder().startingBlank()
				.withRecipients(recipient).withReplyTo(recipient).withOverrideReceivers(recipient)
				.withHeader("X-Route", "first")
				.withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(EmailProperty.SUBJECT).dontApplyOverrideValueFor(EmailProperty.HEADERS).build());
		builder.withDecryptedAttachments(singletonList(resource()));

		builder.getRecipients().clear();
		builder.getReplyToRecipients().clear();
		builder.getOverrideReceivers().clear();
		builder.getDecryptedAttachments().clear();
		builder.getHeaders().get("X-Route").clear();
		builder.getTemplateOptions().toBuilder().getPropertiesNotToApplyDefaultValueFor().clear();
		builder.getTemplateOptions().toBuilder().getPropertiesNotToApplyOverrideValueFor().clear();

		final Email email = builder.buildEmail();
		assertThat(email.getRecipients()).containsExactly(recipient);
		assertThat(email.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly(recipient.getAddress());
		assertThat(email.getOverrideReceivers()).containsExactly(recipient);
		assertThat(email.getDecryptedAttachments()).hasSize(1);
		assertThat(email.getHeaders().get("X-Route")).containsExactly("first");
		assertThat(email.getPropertiesNotToApplyDefaultValueFor()).containsExactly(EmailProperty.SUBJECT);
		assertThat(email.getPropertiesNotToApplyOverrideValueFor()).containsExactly(EmailProperty.HEADERS);
	}

	@Test
	void recipientGroupsOwnTheirListAndPreserveOccurrences() throws Exception {
		final List<Recipient> source = new ArrayList<>(Arrays.asList(recipient, null, recipient));
		final Recipients group = new Recipients(source, "Default", "Fixed");
		source.clear();
		assertThat(group.getRecipients()).containsExactly(recipient, null, recipient);
		assertThatThrownBy(() -> group.getRecipients().clear()).isInstanceOf(UnsupportedOperationException.class);
		final Recipients restored = roundTrip(group);
		assertThat(restored).isEqualTo(group);
		assertThatThrownBy(() -> restored.getRecipients().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void deserializationRepairsLegacyRecipientGroupListSharing() throws Exception {
		final List<Recipient> shared = new ArrayList<>(singletonList(recipient));
		final Recipients group = new Recipients(shared, null, null);
		final Field field = Recipients.class.getDeclaredField("recipients");
		field.setAccessible(true);
		field.set(group, shared);
		final Object[] restored = roundTrip(new Object[] {group, shared});
		((List<?>) restored[1]).clear();
		assertThat(((Recipients) restored[0]).getRecipients()).containsExactly(recipient);
	}

	@Test
	void arrayConveniencesCopyTheirInputWithoutReadingContentSources() throws Exception {
		final byte[] attachment = "plain".getBytes(US_ASCII);
		final byte[] encodedAttachment = "ZW5jb2RlZA==".getBytes(US_ASCII);
		final byte[] image = "image".getBytes(US_ASCII);
		final byte[] encodedImage = "aW1hZ2U=".getBytes(US_ASCII);
		final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank()
				.withAttachment("plain", attachment, "text/plain")
				.withPreEncodedAttachment("encoded", encodedAttachment, "text/plain", BASE_64)
				.withEmbeddedImage("image", image, "image/png")
				.withPreEncodedEmbeddedImage("encoded-image", encodedImage, "image/png", BASE_64);
		Arrays.fill(attachment, (byte) 'x');
		Arrays.fill(encodedAttachment, (byte) 'x');
		Arrays.fill(image, (byte) 'x');
		Arrays.fill(encodedImage, (byte) 'x');
		final Email email = builder.buildEmail();
		assertThat(email.getAttachments().get(0).readAllBytes()).containsExactly("plain".getBytes(US_ASCII));
		assertThat(email.getAttachments().get(1).readAllBytes()).containsExactly("ZW5jb2RlZA==".getBytes(US_ASCII));
		assertThat(email.getEmbeddedImages().get(0).readAllBytes()).containsExactly("image".getBytes(US_ASCII));
		assertThat(email.getEmbeddedImages().get(1).readAllBytes()).containsExactly("aW1hZ2U=".getBytes(US_ASCII));
	}

	@Test
	void structuralSnapshotsStillBorrowLazyDataSourcesAndForwardedMessages() throws Exception {
		final AtomicInteger reads = new AtomicInteger();
		final byte[] borrowedBytes = {1};
		final DataSource lazySource = new DataSource() {
			@Override public InputStream getInputStream() { reads.incrementAndGet(); return new ByteArrayInputStream(borrowedBytes); }
			@Override public OutputStream getOutputStream() throws IOException { throw new IOException("read-only fixture"); }
			@Override public String getContentType() { return "application/octet-stream"; }
			@Override public String getName() { return "lazy"; }
		};
		final MimeMessage forwarded = new MimeMessage(Session.getInstance(new Properties()));
		forwarded.setSubject("Forwarded");
		forwarded.setText("body");
		final Email email = mail.emailBuilder().forwarding(forwarded).withAttachment("lazy", lazySource).buildEmail();
		final Email copied = mail.emailBuilder().copying(email).withSubject("Copied without reading content").buildEmail();
		assertThat(email.getAttachments().get(0).getDataSource()).isSameAs(lazySource);
		assertThat(email.getEmailToForward()).isSameAs(forwarded);
		assertThat(copied.getAttachments().get(0).getDataSource()).isSameAs(lazySource);
		assertThat(copied.getEmailToForward()).isSameAs(forwarded);
		assertThat(reads).hasValue(0);
		borrowedBytes[0] = 2;
		forwarded.setSubject("Changed by owner");
		assertThat(email.getAttachments().get(0).readAllBytes()).containsExactly((byte) 2);
		assertThat(email.getEmailToForward().getSubject()).isEqualTo("Changed by owner");
		assertThat(reads).hasValue(1);
	}

	@Test
	void datesAreDetachedAtBothInputAndReadBoundaries() {
		final Date source = new Date(12345);
		final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().fixingSentDate(source);
		final Email email = builder.buildEmail();
		source.setTime(0);
		builder.getSentDate().setTime(0);
		email.getSentDate().setTime(0);
		assertThat(builder.getSentDate()).isEqualTo(new Date(12345));
		assertThat(email.getSentDate()).isEqualTo(new Date(12345));
	}

	@Test
	void deserializationDetachesLegacySharedCollectionsWithoutReplacingTheEmailSubtype() throws Exception {
		final Email email = mail.emailBuilder().startingBlank().withHeader("X-Route", "first").buildEmail();
		final List<Recipient> sharedEnvelope = new ArrayList<>(singletonList(recipient));
		final List<String> sharedHeaderValues = new ArrayList<>(singletonList("first"));
		final Map<String, Collection<String>> sharedHeaders = new LinkedHashMap<>();
		sharedHeaders.put("X-Route", sharedHeaderValues);
		setField(email, "overrideReceivers", sharedEnvelope);
		setField(email, "headers", sharedHeaders);
		final Object[] restored = roundTrip(new Object[] {email, sharedEnvelope, sharedHeaderValues});
		final Email restoredEmail = (Email) restored[0];
		((List<?>) restored[1]).clear();
		((List<?>) restored[2]).clear();
		assertThat(restoredEmail).isInstanceOf(InternalEmail.class);
		assertThat(restoredEmail.getOverrideReceivers()).containsExactly(recipient);
		assertThat(restoredEmail.getHeaders().get("X-Route")).containsExactly("first");
		assertThatThrownBy(() -> restoredEmail.getOverrideReceivers().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	private static AttachmentResource resource() {
		return new AttachmentResource("decrypted", new ByteArrayDataSource(new byte[] {3}, "text/plain"));
	}

	private static void setField(final Email email, final String name, final Object value) throws ReflectiveOperationException {
		final Field field = Email.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(email, value);
	}

	@SuppressWarnings("unchecked")
	private static <T> T roundTrip(final T value) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(value);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (T) input.readObject();
		}
	}
}
