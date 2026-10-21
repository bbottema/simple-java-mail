package org.simplejavamail.mailer.internal;

import jakarta.activation.DataSource;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.EmailProperty.ALL_RECIPIENTS;
import static org.simplejavamail.api.email.EmailProperty.ATTACHMENTS;
import static org.simplejavamail.api.email.EmailProperty.SUBJECT;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class RepeatableEmailCompletionTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void onlyIncomingRecipientValuesAreDeduplicatedAndTheResultStopsGrowing() {
        for (final RecipientType role : Arrays.asList(RecipientType.TO, RecipientType.CC, RecipientType.BCC)) {
            final Recipient a = recipient("a", role);
            final Recipient b = recipient("b", role);
            final Recipient c = recipient("c", role);
            final Recipient d = recipient("d", role);
            final Email supplied = blank().withRecipients(a, a, b).withReplyTo(List.of(a, a, b)).withOverrideReceivers(a, a, b).buildEmail();
            final Email overrides = blank().withRecipients(a, c, c).withReplyTo(List.of(a, c, c)).withOverrideReceivers(a, c, c).buildEmail();
            final Email defaults = blank().withRecipients(b, c, d, d).withReplyTo(List.of(b, c, d, d)).withOverrideReceivers(b, c, d, d).buildEmail();
            final EmailGovernanceImpl governance = governance(defaults, overrides);

            final Email once = governance.produceEmailApplyingDefaultsAndOverrides(supplied);
            final Email twice = governance.produceEmailApplyingDefaultsAndOverrides(once);

            assertThat(once.getRecipients()).containsExactly(c, a, a, b, d);
            assertThat(twice.getRecipients()).containsExactlyElementsOf(once.getRecipients());
            assertThat(twice.getReplyToRecipients()).containsExactlyElementsOf(once.getReplyToRecipients());
            assertThat(twice.getReplyToRecipients()).extracting(Recipient::getAddress)
                    .containsExactly("c@example.org", "a@example.org", "a@example.org", "b@example.org", "d@example.org");
            assertThat(twice.getOverrideReceivers()).containsExactly(c, a, a, b, d);
            assertThat(supplied.getRecipients()).containsExactly(a, a, b);
        }
    }

    @Test
    void noTemplateCompletionPreservesAllSubmittedOccurrences() {
        final Recipient recipient = recipient("supplied", RecipientType.TO);
        final AttachmentResource resource = resource(new UnreadableSource());
        final Email supplied = blank().withRecipients(recipient, recipient).withAttachments(List.of(resource, resource))
                .withEmbeddedImages(List.of(resource, resource)).buildEmail();
        final Email completed = governance(null, null).produceEmailApplyingDefaultsAndOverrides(supplied);

        assertThat(completed.getRecipients()).containsExactly(recipient, recipient);
        assertResources(completed.getAttachments(), resource, resource);
        assertResources(completed.getEmbeddedImages(), resource, resource);
    }

    @Test
    void recipientsWithDifferentNamesRolesCertificatesOrNotificationPreferencesRemainDistinct() {
        final Recipient original = recipient("same", RecipientType.TO);
        final Recipient named = new Recipient("Different name", original.getAddress(), RecipientType.TO, null);
        final Recipient notified = new Recipient(original.getName(), original.getAddress(), RecipientType.TO, null, List.of(FAILURE));
        final Recipient certified = new Recipient(original.getName(), original.getAddress(), RecipientType.TO, mock(X509Certificate.class));
        final Recipient copied = new Recipient(original.getName(), original.getAddress(), RecipientType.CC, null);
        final Email defaults = blank().withRecipients(original, named, notified, certified, copied).buildEmail();
        final Email supplied = blank().withRecipients(original).buildEmail();

        final Email completed = governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(supplied);

        assertThat(completed.getRecipients()).containsExactly(original, named, notified, certified, copied);
        assertThat(governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(completed).getRecipients())
                .containsExactlyElementsOf(completed.getRecipients());
    }

    @Test
    void anotherGovernanceCanAddNewValuesAndReplaceScalarsWithoutReaddingEarlierContributions() {
        final Email firstDefaults = blank().withRecipients(recipient("first", RecipientType.TO)).withSubject("first default").buildEmail();
        final Email secondDefaults = blank().withRecipients(recipient("first", RecipientType.TO), recipient("second", RecipientType.TO))
                .withSubject("second default").withPlainText("later body").withHeader("X-Added", "later").buildEmail();
        final Email first = governance(firstDefaults, null).produceEmailApplyingDefaultsAndOverrides(blank().buildEmail());
        final Email second = governance(secondDefaults, null).produceEmailApplyingDefaultsAndOverrides(first);

        assertThat(second.getRecipients()).extracting(Recipient::getAddress).containsExactly("first@example.org", "second@example.org");
        assertThat(second.getSubject()).isEqualTo("first default");
        assertThat(second.getPlainText()).isEqualTo("later body");
        assertThat(second.getHeaders().get("X-Added")).containsExactly("later");
        assertThat(governance(secondDefaults, blank().withSubject("destination override").buildEmail())
                .produceEmailApplyingDefaultsAndOverrides(second).getSubject()).isEqualTo("destination override");
        assertThat(first.getRecipients()).hasSize(1);
    }

    @Test
    void resourceIdentityDoesNotReadOpaqueSourcesAndKeepsSuppliedOccurrences() {
        final DataSource source = new UnreadableSource();
        final AttachmentResource a = resource(source);
        final AttachmentResource sameSource = resource(source);
        final AttachmentResource different = resource(new UnreadableSource());
        final Email supplied = blank().withAttachments(List.of(a, a)).withEmbeddedImages(List.of(a, a)).buildEmail();
        final Email overrides = blank().withAttachments(List.of(sameSource, different, different))
                .withEmbeddedImages(List.of(sameSource, different, different)).buildEmail();
        final Email defaults = blank().withAttachments(List.of(sameSource, different)).withEmbeddedImages(List.of(sameSource, different)).buildEmail();
        final EmailGovernanceImpl governance = governance(defaults, overrides);

        final Email once = governance.produceEmailApplyingDefaultsAndOverrides(supplied);
        final Email twice = governance.produceEmailApplyingDefaultsAndOverrides(once);

        assertResources(once.getAttachments(), different, a, a);
        assertResources(twice.getAttachments(), different, a, a);
        assertResources(twice.getEmbeddedImages(), different, a, a);
    }

    @Test
    void resourceMetadataAndEncodingChoicesDistinguishTheSameSource() {
        final DataSource source = new UnreadableSource();
        final AttachmentResource original = resource(source);
        final AttachmentResource renamed = AttachmentResource.builder().withName("other").withDataSource(source).build();
        final AttachmentResource described = AttachmentResource.builder().withName("file").withDataSource(source).withDescription("description").build();
        final AttachmentResource identified = AttachmentResource.builder().withName("file").withDataSource(source).withContentId("content-id").build();
        final AttachmentResource encoded = AttachmentResource.builder().withName("file").withDataSource(source).withContentTransferEncoding(BASE_64).build();
        final AttachmentResource preEncoded = AttachmentResource.builder().withName("file").withDataSource(source).withPreEncodedContentTransferEncoding(BASE_64).build();
        final Email defaults = blank().withAttachments(List.of(original, renamed, described, identified, encoded, preEncoded)).buildEmail();
        final EmailGovernanceImpl governance = governance(defaults, null);

        final Email once = governance.produceEmailApplyingDefaultsAndOverrides(blank().withAttachments(List.of(original)).buildEmail());
        final Email twice = governance.produceEmailApplyingDefaultsAndOverrides(once);

        assertResources(twice.getAttachments(), original, renamed, described, identified, encoded, preEncoded);
    }

    @Test
    void unknownSourcesAreNotCollapsedByTheirPublicMetadataEquality() {
        final AttachmentResource first = resource(new ByteArrayDataSource(new byte[] {1}, "text/plain"));
        final AttachmentResource second = resource(new ByteArrayDataSource(new byte[] {2}, "text/plain"));
        assertThat(first).isEqualTo(second); // The public equality contract intentionally remains unchanged.

        final Email completed = governance(blank().withAttachments(List.of(second)).buildEmail(), null)
                .produceEmailApplyingDefaultsAndOverrides(blank().withAttachments(List.of(first)).buildEmail());

        assertResources(completed.getAttachments(), first, second);
    }

    @Test
    void libraryOwnedBytesCompareContentAndMimeTypeWithoutLosingDefensiveCopies() {
        final byte[] bytes = {1, 2};
        final Email supplied = blank().withAttachment("file", bytes, "application/octet-stream")
                .withEmbeddedImage("image", bytes, "image/png").buildEmail();
        bytes[0] = 9;
        final Email defaults = blank().withAttachment("file", new byte[] {1, 2}, "application/octet-stream")
                .withAttachment("file", new byte[] {3, 4}, "application/octet-stream")
                .withAttachment("file", new byte[] {1, 2}, "text/plain")
                .withEmbeddedImage("image", new byte[] {1, 2}, "image/png").buildEmail();
        final Email completed = governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(supplied);

        assertThat(completed.getAttachments()).hasSize(3);
        assertThat(completed.getEmbeddedImages()).hasSize(1);
        assertThat(completed.getAttachments().get(0)).isSameAs(supplied.getAttachments().get(0));
    }

    @Test
    void matchingLibraryPathsDoNotNeedToExistAndDotDotAliasesRemainDistinct(@TempDir final Path directory) {
        final Path absent = directory.resolve("missing.txt");
        final Email supplied = blank().withAttachment("file", absent).buildEmail();
        final Email defaults = blank().withAttachment("file", directory.resolve("missing.txt"))
                .withAttachment("file", directory.resolve("link/../missing.txt"))
                .withAttachment("other name", absent).buildEmail();

        final Email completed = governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(supplied);

        assertThat(completed.getAttachments()).hasSize(3);
        assertThat(Files.exists(absent)).isFalse();
        assertThat(completed.getAttachments().get(0)).isSameAs(supplied.getAttachments().get(0));
    }

    @Test
    void pathsFromDifferentFilesystemsRemainDistinct(@TempDir final Path directory) throws Exception {
        try (FileSystem first = FileSystems.newFileSystem(URI.create("jar:" + directory.resolve("first.zip").toUri()), Map.of("create", "true"));
                FileSystem second = FileSystems.newFileSystem(URI.create("jar:" + directory.resolve("second.zip").toUri()), Map.of("create", "true"))) {
            final Email supplied = blank().withAttachment("file", first.getPath("/missing.txt")).buildEmail();
            final Email defaults = blank().withAttachment("file", first.getPath("/missing.txt"))
                    .withAttachment("file", second.getPath("/missing.txt")).buildEmail();

            final Email completed = governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(supplied);

            assertThat(completed.getAttachments()).hasSize(2);
            assertThat(completed.getAttachments().get(0)).isSameAs(supplied.getAttachments().get(0));
        }
    }

    @Test
    void serializedOwnedByteContentCanStillBeRecognizedWithoutReadingSources() throws Exception {
        final Email supplied = blank().withAttachment("file", new byte[] {1, 2}, "text/plain").buildEmail();
        final Email restored = roundTrip(supplied);

        final Email completed = governance(supplied, null).produceEmailApplyingDefaultsAndOverrides(restored);

        assertResources(completed.getAttachments(), restored.getAttachments().get(0));
    }

    @Test
    void serializedOpaqueSourceDoesNotAcquireIdentityWithItsOriginalSource() throws Exception {
        final AttachmentResource original = resource(new ByteArrayDataSource(new byte[] {1}, "text/plain"));
        final Email supplied = blank().withAttachments(List.of(original)).buildEmail();
        final Email restored = roundTrip(supplied);

        final Email completed = governance(supplied, null).produceEmailApplyingDefaultsAndOverrides(restored);

        assertResources(completed.getAttachments(), restored.getAttachments().get(0), original);
    }

    @Test
    void globalSuppressionSurvivesCompletionCopyingAndAnotherGovernance() {
        final Email template = blank().withSubject("template").withRecipients(recipient("template", RecipientType.TO)).buildEmail();
        final Email supplied = blank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().build()).buildEmail();
        final Email once = governance(template, template).produceEmailApplyingDefaultsAndOverrides(supplied);
        final Email copied = mail.emailBuilder().copying(once).buildEmail();
        final Email twice = governance(template, template).produceEmailApplyingDefaultsAndOverrides(copied);

        assertThat(twice.isIgnoreDefaults()).isTrue();
        assertThat(twice.isIgnoreOverrides()).isTrue();
        assertThat(twice.getSubject()).isNull();
        assertThat(twice.getRecipients()).isEmpty();
    }

    @Test
    void propertySuppressionSurvivesRepeatedCompletionWithoutCreatingNestedTemplateSuppression() {
        final Email template = blank().withSubject("template").withRecipients(recipient("template", RecipientType.TO))
                .withAttachment("file", new byte[] {1}, "text/plain")
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().build()).buildEmail();
        final Email supplied = blank().withTemplateOptions(EmailTemplateOptions.builder()
                .dontApplyDefaultValueFor(ALL_RECIPIENTS, ATTACHMENTS).dontApplyOverrideValueFor(SUBJECT).build()).buildEmail();
        final Email once = governance(template, null).produceEmailApplyingDefaultsAndOverrides(supplied);
        final Email copied = mail.emailBuilder().copying(once).buildEmail();
        final Email twice = governance(template, blank().withSubject("excluded override").buildEmail())
                .produceEmailApplyingDefaultsAndOverrides(copied);

        assertThat(twice.getSubject()).isEqualTo("template");
        assertThat(twice.getRecipients()).isEmpty();
        assertThat(twice.getAttachments()).isEmpty();
        assertThat(twice.isIgnoreDefaults()).isFalse();
        assertThat(twice.getPropertiesNotToApplyDefaultValueFor()).containsExactlyInAnyOrder(ALL_RECIPIENTS, ATTACHMENTS);
        assertThat(twice.getPropertiesNotToApplyOverrideValueFor()).containsExactly(SUBJECT);
    }

    private EmailPopulatingBuilder blank() {
        return mail.emailBuilder().startingBlank();
    }

    private static Recipient recipient(final String name, final RecipientType role) {
        return new Recipient(name, name + "@example.org", role, null);
    }

    private static AttachmentResource resource(final DataSource source) {
        return AttachmentResource.builder().withName("file").withDataSource(source).build();
    }

    private static EmailGovernanceImpl governance(final Email defaults, final Email overrides) {
        return new EmailGovernanceImpl(null, defaults, overrides, null);
    }

    private static void assertResources(final List<AttachmentResource> actual, final AttachmentResource... expected) {
        assertThat(actual.size()).isEqualTo(expected.length);
        for (int index = 0; index < expected.length; index++) {
            assertThat(actual.get(index)).isSameAs(expected[index]);
        }
    }

    private static Email roundTrip(final Email original) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ObjectOutputStream objects = new ObjectOutputStream(output)) {
            objects.writeObject(original);
        }
        try (ObjectInputStream objects = new ObjectInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            return (Email) objects.readObject();
        }
    }

    private static final class UnreadableSource implements DataSource {
        @Override public InputStream getInputStream() { throw new AssertionError("Completion must not open a borrowed source"); }
        @Override public OutputStream getOutputStream() { throw new AssertionError("Completion must not write a borrowed source"); }
        @Override public String getContentType() { throw new AssertionError("Completion must not inspect arbitrary source metadata"); }
        @Override public String getName() { throw new AssertionError("Completion must not inspect arbitrary source metadata"); }
    }
}
