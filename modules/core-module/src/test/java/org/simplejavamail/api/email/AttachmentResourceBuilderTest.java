package org.simplejavamail.api.email;

import jakarta.activation.DataSource;
import jakarta.activation.FileTypeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.ContentTransferEncoding.BIT7;

class AttachmentResourceBuilderTest {

    @TempDir Path directory;

    @Test
    void sourceAndCopyAreBorrowedWithoutOpeningOrInspectingContent() {
        final DataSource source = new DataSource() {
            @Override public InputStream getInputStream() { throw new AssertionError("content opened"); }
            @Override public OutputStream getOutputStream() { throw new AssertionError("write opened"); }
            @Override public String getContentType() { throw new AssertionError("content type inspected"); }
            @Override public String getName() { throw new AssertionError("name inspected"); }
        };
        final AttachmentResource attachment = AttachmentResource.builder().withDataSource(source).withName("report.pdf")
                .withDescription("monthly").withContentId("report-42").withContentTransferEncoding(BASE_64).build();
        final AttachmentResource changed = attachment.toBuilder().withDescription("annual").build();

        assertThat(attachment.getDataSource()).isSameAs(source);
        assertThat(changed.getDataSource()).isSameAs(source);
        assertThat(changed.getName()).isEqualTo("report.pdf");
        assertThat(changed.getContentId()).isEqualTo("report-42");
        assertThat(changed.getContentTransferEncoding()).isEqualTo(BASE_64);
        assertThat(attachment.getDescription()).isEqualTo("monthly");
        assertThat(changed.getDescription()).isEqualTo("annual");
    }

    @Test
    void bytesAreCopiedAtAssignmentAndInspectionAndBuiltSnapshotsAreIndependent() throws Exception {
        final byte[] bytes = {1, 2, 3};
        final AttachmentResource.Builder builder = AttachmentResource.builder().withData(bytes, "application/pdf").withName("report.pdf");
        bytes[0] = 9;
        builder.getData()[1] = 9;
        final AttachmentResource first = builder.build();
        builder.withData(new byte[] {4}, "text/plain");

        assertThat(first.readAllBytes()).containsExactly(1, 2, 3);
        assertThat(first.getDataSource().getName()).isEqualTo("report.pdf");
        assertThat(first.getDataSource().getContentType()).isEqualTo("application/pdf");
        assertThat(builder.build().readAllBytes()).containsExactly(4);
        assertThatThrownBy(() -> first.getDataSource().getOutputStream()).isInstanceOf(IOException.class);
    }

    @Test
    void pathRemainsLazyAndMetadataChangesDoNotChangeItsContentType() throws Exception {
        final Path path = directory.resolve("future.txt");
        final AttachmentResource attachment = AttachmentResource.builder().withPath(path).withName("report.bin").build();
        final AttachmentResource copy = attachment.toBuilder().withDescription("description").build();
        assertThat(Files.exists(path)).isFalse();
        assertThat(copy.getDataSource()).isSameAs(attachment.getDataSource());
        assertThat(AttachmentResource.builder().withPath(path).build().getName()).isEqualTo("future.txt");
        Files.write(path, new byte[] {1, 2});
        assertThat(copy.readAllBytes()).containsExactly(1, 2);
        assertThat(copy.getDataSource().getName()).isEqualTo("future.txt");
        assertThat(copy.getDataSource().getContentType()).isEqualTo(FileTypeMap.getDefaultFileTypeMap().getContentType("future.txt"));
    }

    @Test
    void requiredContentAndEncodingConflictsFailAtConstruction() {
        assertThatThrownBy(() -> AttachmentResource.builder().build()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content must be supplied");
        final AttachmentResource.Builder builder = AttachmentResource.builder().withData(new byte[0], "text/plain")
                .withContentTransferEncoding(BIT7).withPreEncodedContentTransferEncoding(BASE_64);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not both");
        final AttachmentResource encoded = builder.clearContentTransferEncoding().build();
        assertThat(encoded.getPreEncodedContentTransferEncoding()).isEqualTo(BASE_64);
        assertThat(encoded.toBuilder().build().getPreEncodedContentTransferEncoding()).isEqualTo(BASE_64);
        assertThat(encoded.toBuilder().resetPreEncodedContentTransferEncoding().build().getPreEncodedContentTransferEncoding()).isNull();
    }

    @Test
    void changingContentAndClearingOptionalMetadataDoNotResetOtherChoices() {
        final AttachmentResource.Builder builder = AttachmentResource.builder().withData(new byte[] {1}, "text/plain")
                .withName("image").withDescription("description").withContentId("cid").withContentTransferEncoding(BASE_64);
        final AttachmentResource first = builder.build();
        builder.withPath(directory.resolve("image.png")).clearDescription().resetName().resetContentId();

        assertThat(builder.getData()).isNull();
        assertThat(builder.getMimetype()).isNull();
        assertThat(builder.getPath()).isNotNull();
        assertThat(builder.getContentTransferEncoding()).isEqualTo(BASE_64);
        assertThat(builder.getDescription()).isNull();
        assertThat(builder.getName()).isNull();
        assertThat(builder.getContentId()).isNull();
        assertThat(first.getDescription()).isEqualTo("description");
        assertThat(first.getName()).isEqualTo("image");
        assertThat(first.getContentId()).isEqualTo("cid");
    }
}
