package org.simplejavamail.mailer.internal;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Input representation and MIME metadata have separate replacement and validation contracts. */
class AttachmentResourceRouteContractTest {
    private static final byte[] CONTENT = "resource content".getBytes(StandardCharsets.UTF_8);

    static Stream<Method> extraRoutes() {
        return Arrays.stream(AttachmentResource.Builder.class.getDeclaredMethods())
                .filter(method -> method.getReturnType() == AttachmentResource.Builder.class)
                .sorted(java.util.Comparator.comparing(testutil.configcontracts.ConfigurationContractCoverage::signature));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extraRoutes")
    void resourceChoiceRoutesPreserveContentAndMetadata(final Method method, @TempDir final Path directory) throws Exception {
        final String name = method.getName();
        if (name.equals("withData") || name.equals("withDataSource") || name.equals("withPath")) { replacement(method, directory); }
        else if (name.equals("clearData") || name.equals("clearDataSource") || name.equals("clearPath")) { clearContent(method, directory); }
        else { metadata(method, directory); }
    }

    private static void replacement(final Method method, final Path directory) throws Exception {
        final Path oldPath = Files.write(directory.resolve("old.txt"), CONTENT);
        final Path newPath = directory.resolve("new.txt");
        final TrackingSource borrowed = new TrackingSource(CONTENT);
        final AttachmentResource.Builder builder = AttachmentResource.builder().withPath(oldPath).withName("kept.txt")
                .withDescription("kept description").withContentId("kept-id");
        final AttachmentResource old = builder.build();
        final byte[] supplied = CONTENT.clone();
        final Object[] args = method.getName().equals("withData") ? new Object[] {supplied, "text/plain; charset=UTF-8"}
                : new Object[] {method.getName().equals("withPath") ? newPath : borrowed};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        Arrays.fill(supplied, (byte) 'X');
        final AttachmentResource resource = builder.build();

        assertThat(builder.getDataSource()).isSameAs(method.getName().equals("withDataSource") ? borrowed : null);
        assertThat(builder.getPath()).isEqualTo(method.getName().equals("withPath") ? newPath : null);
        if (method.getName().equals("withData")) {
            assertThat(builder.getData()).containsExactly(CONTENT);
            assertThat(builder.getMimetype()).isEqualTo("text/plain; charset=UTF-8");
            final byte[] inspected = builder.getData();
            Arrays.fill(inspected, (byte) 'Y');
            assertThat(builder.getData()).containsExactly(CONTENT);
        } else { assertThat(builder.getData()).isNull(); assertThat(builder.getMimetype()).isNull(); }
        assertThat(borrowed.reads).isZero(); assertThat(borrowed.metadataReads).isZero();
        if (method.getName().equals("withPath")) {
            assertThat(Files.exists(newPath)).isFalse();
            Files.write(newPath, CONTENT);
        }
        assertThat(resource.getName()).isEqualTo("kept.txt");
        assertThat(resource.getDescription()).isEqualTo("kept description");
        assertThat(resource.getContentId()).isEqualTo("kept-id");
        assertConsumerContent(resource, CONTENT);
        assertThat(old.readAllBytes()).containsExactly(CONTENT);
        assertThat(borrowed.closes).isZero();
        if (method.getName().equals("withDataSource")) {
            assertThat(resource.getDataSource()).isSameAs(borrowed);
            assertThat(borrowed.reads).isEqualTo(1);
            assertThat(borrowed.streamCloses).isEqualTo(1);
        }
        for (String field : List.of("data", "mimetype", "dataSource", "path")) { record("field", "resource." + field); }
        record("rule", "resource.content-replacement");
        record("rule", "resource.lazy-ownership");
    }

    private static void clearContent(final Method method, final Path directory) throws Exception {
        final TrackingSource borrowed = new TrackingSource(CONTENT);
        final Path path = Files.write(directory.resolve("content.txt"), CONTENT);
        final AttachmentResource.Builder builder = AttachmentResource.builder().withName("kept.txt").withDescription("kept description");
        if (method.getName().equals("clearData")) { builder.withData(CONTENT, "text/plain"); }
        else if (method.getName().equals("clearPath")) { builder.withPath(path); }
        else { builder.withDataSource(borrowed); }
        final AttachmentResource prior = builder.build();
        assertThat(invoke(builder, method)).isSameAs(builder);
        assertThat(builder.getData()).isNull(); assertThat(builder.getMimetype()).isNull();
        assertThat(builder.getDataSource()).isNull(); assertThat(builder.getPath()).isNull();
        assertThat(builder.getName()).isEqualTo("kept.txt"); assertThat(builder.getDescription()).isEqualTo("kept description");
        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class).hasMessageContaining("Attachment content must be supplied");
        assertThat(borrowed.closes).isZero();
        assertConsumerContent(prior, CONTENT);
        assertConsumerContent(builder.withData(CONTENT, "text/plain").build(), CONTENT);
        final AttachmentResource.Builder unrelated = AttachmentResource.builder();
        if (method.getName().equals("clearDataSource")) { unrelated.withData(CONTENT, "text/plain"); }
        else { unrelated.withDataSource(borrowed); }
        assertThat(invoke(unrelated, method)).isSameAs(unrelated);
        assertConsumerContent(unrelated.build(), CONTENT);
        for (String field : List.of("data", "mimetype", "dataSource", "path")) { record("field", "resource." + field); }
        record("rule", "resource.clear-validation");
    }

    private static void metadata(final Method method, final Path directory) throws Exception {
        final String name = method.getName();
        final boolean preEncoded = name.contains("PreEncoded");
        final byte[] bytes = preEncoded ? Base64.getEncoder().encode(CONTENT) : CONTENT;
        final Path path = Files.write(directory.resolve("fallback.txt"), bytes);
        final AttachmentResource.Builder builder = AttachmentResource.builder().withPath(path).withName("old.txt")
                .withDescription("old description").withContentId("old-id");
        if (preEncoded) { builder.withPreEncodedContentTransferEncoding(ContentTransferEncoding.BASE_64); }
        else { builder.withContentTransferEncoding(ContentTransferEncoding.BIT7); }
        final AttachmentResource previous = builder.build();
        final Object[] args = method.getParameterCount() == 0 ? new Object[0] : new Object[] {
                method.getParameterTypes()[0] == ContentTransferEncoding.class ? ContentTransferEncoding.BASE_64 : "new"};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final AttachmentResource resource = builder.build();
        final boolean remove = method.getParameterCount() == 0;
        final String field;
        if (name.endsWith("Name")) {
            assertThat(builder.getName()).isEqualTo(remove ? null : "new");
            assertThat(resource.getName()).isEqualTo(remove ? "fallback.txt" : "new"); field = "name";
        } else if (name.endsWith("Description")) { assertThat(resource.getDescription()).isEqualTo(remove ? null : "new"); field = "description"; }
        else if (name.endsWith("ContentId")) { assertThat(resource.getContentId()).isEqualTo(remove ? null : "new"); field = "contentId"; }
        else if (preEncoded) { assertThat(resource.getPreEncodedContentTransferEncoding()).isEqualTo(remove ? null : ContentTransferEncoding.BASE_64); field = "preEncodedContentTransferEncoding"; }
        else { assertThat(resource.getContentTransferEncoding()).isEqualTo(remove ? null : ContentTransferEncoding.BASE_64); field = "contentTransferEncoding"; }
        if (!field.equals("name")) { assertThat(resource.getName()).isEqualTo("old.txt"); }
        if (!field.equals("description")) { assertThat(resource.getDescription()).isEqualTo("old description"); }
        if (!field.equals("contentId")) { assertThat(resource.getContentId()).isEqualTo("old-id"); }
        if (!field.equals("contentTransferEncoding")) { assertThat(resource.getContentTransferEncoding()).isEqualTo(preEncoded ? null : ContentTransferEncoding.BIT7); }
        assertThat(previous.getName()).isEqualTo("old.txt");
        assertThat(previous.getDescription()).isEqualTo("old description");
        assertThat(previous.getContentId()).isEqualTo("old-id");
        assertConsumerContent(resource, bytes);
        record("field", "resource." + field);
    }

    @Test
    void normalAndPreencodedChoicesRemainIndependentUntilBuildValidation() {
        final TrackingSource borrowed = new TrackingSource(CONTENT);
        final AttachmentResource.Builder builder = AttachmentResource.builder().withDataSource(borrowed)
                .withContentTransferEncoding(ContentTransferEncoding.BASE_64).withPreEncodedContentTransferEncoding(ContentTransferEncoding.BIT7);
        assertThat(builder.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        assertThat(builder.getPreEncodedContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BIT7);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not both");
        assertThat(borrowed.reads).isZero(); assertThat(borrowed.metadataReads).isZero();
        assertThat(builder.clearPreEncodedContentTransferEncoding().build().getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        record("rule", "resource.encoding-exclusion");
    }

    private static void assertConsumerContent(final AttachmentResource resource, final byte[] expected) {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final AttachmentResource captured = mail.emailBuilder().startingBlank().withAttachment(resource).buildEmail().getAttachments().get(0);
        assertThat(captured.getDataSource()).isSameAs(resource.getDataSource());
        assertThat(captured.readAllBytes()).containsExactly(expected);
    }

    private static final class TrackingSource implements DataSource, AutoCloseable {
        private final byte[] bytes;
        private int reads;
        private int metadataReads;
        private int closes;
        private int streamCloses;

        private TrackingSource(final byte[] bytes) { this.bytes = bytes.clone(); }
        @Override public InputStream getInputStream() {
            reads++;
            return new ByteArrayInputStream(bytes) { @Override public void close() throws IOException { streamCloses++; super.close(); } };
        }
        @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
        @Override public String getContentType() { metadataReads++; return "text/plain"; }
        @Override public String getName() { metadataReads++; return "source.txt"; }
        @Override public void close() { closes++; }
    }
}
