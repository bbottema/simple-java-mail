package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.config.ConfigLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageFileResolutionContextTest {
    @TempDir Path directory;

    @Test
    void scopedConstructionUsesTheRequestDirectoryAndThenRestoresOrdinaryLibraryResolution() throws Exception {
        final Path image = Files.write(directory.resolve("logo.png"), new byte[] {1, 2, 3});
        final String workingDirectory = System.getProperty("user.dir");
        try (ImageFileResolutionContext ignored = ImageFileResolutionContext.install(directory, Path.of("."))) {
            assertThat(build("logo.png").getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
            assertThat(System.getProperty("user.dir")).isEqualTo(workingDirectory);
        }

        final String processRelativeImage = Path.of("").toAbsolutePath().relativize(image.toAbsolutePath()).toString();
        assertThat(build(processRelativeImage).getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(System.getProperty("user.dir")).isEqualTo(workingDirectory);
    }

    @Test
    void nestedScopesRestoreTheirParentAndRejectOutOfOrderClosure() throws Exception {
        Files.write(directory.resolve("logo.png"), new byte[] {1});
        final Path nestedDirectory = Files.createDirectory(directory.resolve("nested"));
        Files.write(nestedDirectory.resolve("logo.png"), new byte[] {2});
        assertThatThrownBy(() -> ImageFileResolutionContext.install(directory, null)).isInstanceOf(NullPointerException.class);
        final ImageFileResolutionContext first = ImageFileResolutionContext.install(directory, directory);
        try {
            assertThatThrownBy(() -> ImageFileResolutionContext.install(directory, null)).isInstanceOf(NullPointerException.class);
            try (ImageFileResolutionContext nested = ImageFileResolutionContext.install(nestedDirectory, directory)) {
                assertThat(build("logo.png").getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 2);
                assertThatThrownBy(first::close).isInstanceOf(IllegalStateException.class);
            }
            assertThat(build("logo.png").getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 1);
        } finally {
            first.close();
        }
        try (ImageFileResolutionContext second = ImageFileResolutionContext.install(directory, directory)) {
            first.close();
            assertThat(build("logo.png").getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 1);
        }
    }

    @Test
    void configuredBaseOwnsAlreadyPrefixedSourcesWithinARequestScope() throws Exception {
        final Path configuration = Files.createDirectory(directory.resolve("configuration"));
        final Path request = Files.createDirectory(directory.resolve("request"));
        Files.createDirectory(configuration.resolve("images"));
        Files.createDirectory(request.resolve("images"));
        Files.write(configuration.resolve("images/logo.png"), new byte[] {1});
        Files.write(request.resolve("images/logo.png"), new byte[] {2});
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.embeddedimages.dynamicresolution.base.dir", "images",
                "simplejavamail.embeddedimages.dynamicresolution.enable.dir", true,
                "simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful", true)).load());
        try (ImageFileResolutionContext ignored = ImageFileResolutionContext.install(request, configuration)) {
            final Email email = mail.emailBuilder().startingBlank().withHTMLText("<img src='images/logo.png'>").buildEmail();
            assertThat(email.getEmbeddedImages()).hasSize(1);
            assertThat(email.getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 1);
        }
    }

    private static Email build(final String source) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().startingBlank()
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(true)
                        .embeddedImageAutoResolutionMustBeSuccesful(true).build())
                .withHTMLText("<img src='" + source + "'>").buildEmail();
    }
}
