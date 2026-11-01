package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import testutil.ConfigLoaderTestHelper;

import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CliImageSourceHelpTest {
    @ParameterizedTest
    @MethodSource("imageOptions")
    void bundledImageHelpExplainsTheHtmlBoundaryWithoutOpeningAMailer(final String option, final String example) throws Exception {
        final MailerProvider provider = mock(MailerProvider.class);
        try (CliExecutionEnvironment environment = new CliExecutionEnvironment(ConfigLoaderTestHelper.emptyConfig(), provider)) {
            final CliExecutionResult result = CliSupport.execute(new String[] {"send", option + "--help"},
                    Path.of(".").toAbsolutePath(), UUID.randomUUID(), environment, null);
            assertThat(result.exitCode()).as(result.stderr()).isZero();
            assertThat(result.stderr()).isEmpty();
            assertThat(result.stdout().replaceAll("\\s+", " ")).contains("interpreted once", example);
            verifyNoInteractions(provider);
        }
    }

    private static Stream<Arguments> imageOptions() {
        return Stream.of(
                Arguments.of("--email:withEmbeddedImageAutoResolutionForFiles", "src=\"a&amp;amp;b.png\""),
                Arguments.of("--email:withEmbeddedImageAutoResolutionForClassPathResources", "src=\"a&amp;b.png\""),
                Arguments.of("--email:withEmbeddedImageAutoResolutionForURLs", "logo.png?x=1&amp;y=2"));
    }
}
