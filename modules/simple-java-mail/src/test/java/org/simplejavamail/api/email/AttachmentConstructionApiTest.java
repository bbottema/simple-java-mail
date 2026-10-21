package org.simplejavamail.api.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentConstructionApiTest {

    @TempDir Path classes;

    @Test
    void java11ConsumerUsesNamedMetadataAndRetainedConveniences() throws Exception {
        assertCompilation(true,
                "AttachmentResource resource = AttachmentResource.builder().withName(\"report.pdf\").withDataSource(source)"
                + ".withDescription(\"Monthly report\").withContentId(\"report-42\").withContentTransferEncoding(encoding).build();"
                + "builder.withAttachment(resource).withEmbeddedImage(resource.toBuilder().withName(\"chart\").build());"
                + "builder.withAttachment(path).withAttachment(\"report\", path).withAttachment(\"report\", source)"
                + ".withAttachment(\"report\", data, \"application/pdf\");"
                + "builder.withPreEncodedAttachment(\"report\", source, encoding)"
                + ".withPreEncodedAttachment(\"report\", data, \"application/pdf\", encoding);"
                + "builder.withEmbeddedImage(\"chart\", source).withEmbeddedImage(\"chart\", data, \"image/png\")"
                + ".withPreEncodedEmbeddedImage(\"chart\", source, encoding)"
                + ".withPreEncodedEmbeddedImage(\"chart\", data, \"image/png\", encoding);"
                + "AttachmentResource.builder().withPath(path).withDescription(\"lazy\").build();"
                + "AttachmentResource.builder().withData(data, \"image/png\").withPreEncodedContentTransferEncoding(encoding).build();");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "new AttachmentResource(\"report\", source);",
            "new AttachmentResource(\"report\", source, \"description\");",
            "new AttachmentResource(\"report\", source, \"description\", encoding);",
            "new AttachmentResource(\"report\", source, \"description\", encoding, \"cid\");",
            "AttachmentResource.ofPreEncodedData(\"report\", source, \"description\", encoding);",
            "AttachmentResource.ofPreEncodedData(\"report\", source, \"description\", encoding, \"cid\");",
            "builder.withAttachment(\"report\", data, \"application/pdf\", \"description\");",
            "builder.withAttachment(\"report\", data, \"application/pdf\", \"description\", encoding);",
            "builder.withAttachment(\"report\", source, \"description\");",
            "builder.withAttachment(\"report\", source, \"description\", encoding);",
            "builder.withAttachment(\"report\", source, \"description\", encoding, \"cid\");",
            "builder.withPreEncodedAttachment(\"report\", data, \"application/pdf\", \"description\", encoding);",
            "builder.withPreEncodedAttachment(\"report\", source, \"description\", encoding);",
            "builder.withPreEncodedAttachment(\"report\", source, \"description\", encoding, \"cid\");",
            "builder.withEmbeddedImage(\"chart\", source, \"cid\");",
            "builder.withPreEncodedEmbeddedImage(\"chart\", source, encoding, \"cid\");"
    })
    void positionalMetadataRequiresMigration(final String statement) throws Exception {
        assertCompilation(false, statement);
    }

    private void assertCompilation(final boolean expected, final String statements) throws Exception {
        final String source = "import org.simplejavamail.api.email.*; import jakarta.activation.DataSource; import java.nio.file.Path;"
                + "class AttachmentConsumer { void use(EmailPopulatingBuilder builder, DataSource source, Path path, byte[] data,"
                + " ContentTransferEncoding encoding) {" + statements + "}}";
        final SimpleJavaFileObject unit = new SimpleJavaFileObject(URI.create("string:///AttachmentConsumer.java"),
                SimpleJavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(final boolean ignoreEncodingErrors) { return source; }
        };
        final StringWriter diagnostics = new StringWriter();
        try (StandardJavaFileManager files = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            final boolean compiled = ToolProvider.getSystemJavaCompiler().getTask(diagnostics, files, null,
                    List.of("--release", "11", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                    null, List.of(unit)).call();
            assertThat(compiled).as("%s%n%s", statements, diagnostics).isEqualTo(expected);
        }
    }
}
