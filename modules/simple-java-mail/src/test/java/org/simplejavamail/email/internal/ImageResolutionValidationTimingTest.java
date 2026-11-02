package org.simplejavamail.email.internal;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.config.ConfigLoader;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageResolutionValidationTimingTest {
    private static final String PREFIX = "simplejavamail.embeddedimages.dynamicresolution.";
    private static final String LOCKED_PREFIX = "simplejavamail.locked.embeddedimages.dynamicresolution.";
    private static final String INVALID_BASE = "not-an-image-url";

    @TempDir
    Path directory;

    enum Repair { DISABLE, REPLACE, CLEAR }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unusedMalformedBaseDoesNotPreventCreatingOrBuildingAnEmail(final boolean explicitDisabled) {
        final Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(PREFIX + "base.url", INVALID_BASE);
        if (explicitDisabled) {
            settings.put(PREFIX + "enable.url", "false");
        }
        final EmailPopulatingBuilder draft = factory(settings).emailBuilder().startingBlank().withPlainText("Hello");

        assertThat(draft.getImageResolutionConfig().getEmbeddedImageBaseUrl()).isNull();
        assertThat(draft.getImageResolutionConfig().isEmbeddedImageBaseUrlConfigured()).isFalse();
        assertThat(draft.buildEmail().getPlainText()).isEqualTo("Hello");
        assertThat(draft.buildEmail().getEmbeddedImages()).isEmpty();
    }

    @Test
    void activeMalformedBaseIsRejectedOnlyAtBuildAndBeforeAnyImageRequest() throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image.png", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, 1);
            try (java.io.OutputStream output = exchange.getResponseBody()) {
                output.write(7);
            }
        });
        server.start();
        try {
            final String html = image("http://127.0.0.1:" + server.getAddress().getPort() + "/image.png");
            final EmailPopulatingBuilder draft = malformedFactory(true).emailBuilder().startingBlank()
                    .withImageResolutionConfig(ImageResolutionConfig.builder().embeddedImageAutoResolutionMustBeSuccesful(true).build())
                    .withHTMLText(html);

            assertThat(draft.getImageResolutionConfig().getEmbeddedImageAutoResolutionForURLs()).isNull();
            for (int attempt = 0; attempt < 2; attempt++) {
                assertInvalidBase(draft);
                assertThat(draft.getHTMLText()).isEqualTo(html);
                assertThat(draft.getEmbeddedImages()).isEmpty();
            }
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @EnumSource(Repair.class)
    void finalChoicesCanDisableReplaceOrClearTheMalformedInheritedBase(final Repair repair) throws Exception {
        final EmailPopulatingBuilder draft = malformedFactory(true).emailBuilder().startingBlank();
        applyRepair(draft, repair);

        final Email result = draft.buildEmail();
        if (repair == Repair.DISABLE) {
            assertThat(result.getPlainText()).isEqualTo("Disabled");
            assertThat(result.getEmbeddedImages()).isEmpty();
        } else {
            assertImage(result);
        }
    }

    @ParameterizedTest
    @EnumSource(Repair.class)
    void aFailedBuildCanBeCorrectedWithoutReplacingTheDraft(final Repair repair) throws Exception {
        final EmailPopulatingBuilder draft = malformedFactory(true).emailBuilder().startingBlank().withPlainText("Before");
        assertInvalidBase(draft);

        applyRepair(draft, repair);
        final Email result = draft.buildEmail();
        assertThatCode(draft::buildEmail).doesNotThrowAnyException();
        if (repair == Repair.DISABLE) {
            assertThat(result.getPlainText()).isEqualTo("Disabled");
        } else {
            assertImage(result);
        }
    }

    @Test
    void groupReplacementAndRestorationUseTheCapturedBaseAgain() throws Exception {
        final EmailPopulatingBuilder draft = malformedFactory(true).emailBuilder().startingBlank()
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageBaseUrl(directory.toUri().toURL()).build())
                .withPlainText("First");
        final Email first = draft.buildEmail();

        draft.withImageResolutionConfig(ImageResolutionConfig.builder().build());
        assertThat(draft.getImageResolutionConfig().isEmbeddedImageBaseUrlConfigured()).isFalse();
        assertInvalidBase(draft);
        draft.withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(false).build());
        assertThat(draft.buildEmail().getPlainText()).isEqualTo("First");
        draft.useConfiguredImageResolutionConfig();
        assertThat(draft.getImageResolutionConfig().getEmbeddedImageAutoResolutionForURLs()).isNull();
        assertInvalidBase(draft);
        assertThat(first.getPlainText()).isEqualTo("First");
        assertThat(first.getEmbeddedImages()).isEmpty();
    }

    @Test
    void reenablingUrlResolutionAfterASuccessfulBuildDoesNotReuseTheInactiveBase() {
        final EmailPopulatingBuilder draft = malformedFactory(false).emailBuilder().startingBlank().withPlainText("First");
        final Email first = draft.buildEmail();

        draft.withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(true).build());
        assertInvalidBase(draft);
        draft.withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(false).build());
        assertThat(draft.buildEmail().getPlainText()).isEqualTo("First");
        assertThat(first.getPlainText()).isEqualTo("First");
    }

    @Test
    void completionAlsoIgnoresAnOrdinaryMalformedBaseWhenItsFactoryDisablesUrlResolution() {
        final EmailPopulatingBuilder draft = malformedFactory(false).emailBuilder().startingBlank().withPlainText("Original");

        assertThat(draft.buildEmailCompletedWithDefaultsAndOverrides().getPlainText()).isEqualTo("Original");
    }

    @Test
    void completionRetainsItsIndependentlyActiveGovernanceConfiguration() {
        final EmailPopulatingBuilder draft = malformedFactory(true).emailBuilder().startingBlank().withPlainText("Original")
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(false).build());
        assertThat(draft.buildEmail().getPlainText()).isEqualTo("Original");

        assertThatThrownBy(draft::buildEmailCompletedWithDefaultsAndOverrides).isInstanceOf(EmailException.class)
                .hasCauseInstanceOf(MalformedURLException.class);
    }

    @Test
    void copyingCollectsItsOwnFinalImageChoicesBeforeTheOrdinaryBuild() {
        final SimpleJavaMail mail = malformedFactory(true);
        final Email first = mail.emailBuilder().startingBlank().withPlainText("Original")
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(false).build())
                .buildEmail();

        final EmailPopulatingBuilder copy = mail.emailBuilder().copying(first)
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(false).build());
        assertThat(copy.buildEmail().getPlainText()).isEqualTo("Original");
        copy.useConfiguredImageResolutionConfig();
        assertInvalidBase(copy);
        assertThat(first.getPlainText()).isEqualTo("Original");
    }

    @Test
    void aMalformedMandatoryBaseStillFailsWhenUrlResolutionIsDisabled() {
        final EmailPopulatingBuilder draft = factory(Map.of(LOCKED_PREFIX + "base.url", INVALID_BASE,
                PREFIX + "enable.url", "false")).emailBuilder().startingBlank().withPlainText("Hello");

        assertInvalidBase(draft);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMandatoryBaseCannotBeRemovedOrReplacedWhileResolutionIsDisabled(final boolean remove) throws Exception {
        final String lockedBase = directory.toUri().toURL().toExternalForm();
        final EmailPopulatingBuilder draft = factory(Map.of(LOCKED_PREFIX + "base.url", lockedBase,
                PREFIX + "enable.url", "false")).emailBuilder().startingBlank();
        assertThatCode(draft::buildEmail).doesNotThrowAnyException();
        final ImageResolutionConfig.Builder choices = ImageResolutionConfig.builder();
        if (remove) {
            choices.clearEmbeddedImageBaseUrl();
        } else {
            choices.withEmbeddedImageBaseUrl("https://other.example.org/images/");
        }
        draft.withImageResolutionConfig(choices.build());

        assertThatThrownBy(draft::buildEmail).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locked").hasMessageContaining("base.url");
        draft.useConfiguredImageResolutionConfig();
        assertThatCode(draft::buildEmail).doesNotThrowAnyException();
    }

    @Test
    void malformedMandatoryBaseCannotBeRepairedByAnOrdinaryReplacement() {
        final EmailPopulatingBuilder draft = factory(Map.of(LOCKED_PREFIX + "base.url", INVALID_BASE,
                PREFIX + "enable.url", "false")).emailBuilder().startingBlank()
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageBaseUrl("https://example.org/images/").build());

        assertThatThrownBy(draft::buildEmail).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locked").hasMessageContaining("base.url");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mandatoryActivationCannotBeChangedByTheGroup(final boolean enabled) {
        final EmailPopulatingBuilder draft = factory(Map.of(LOCKED_PREFIX + "enable.url", Boolean.toString(enabled),
                PREFIX + "base.url", INVALID_BASE)).emailBuilder().startingBlank()
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(!enabled)
                        .withEmbeddedImageBaseUrl("https://example.org/images/").build());

        assertThatThrownBy(draft::buildEmail).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("locked").hasMessageContaining("enable.url");
    }

    @Test
    void scalarAdaptersCanOverrideClearAndRestoreTheCapturedBase() throws Exception {
        final InternalEmailPopulatingBuilder draft = (InternalEmailPopulatingBuilder) malformedFactory(true).emailBuilder().startingBlank();
        Files.write(directory.resolve("logo.png"), new byte[] {1, 2, 3});
        draft.withEmbeddedImageBaseUrl(directory.toUri().toURL()).withHTMLText(image("logo.png"));
        assertImage(draft.buildEmail());

        draft.clearEmbeddedImageBaseUrl().withHTMLText(image(directory.resolve("logo.png").toUri().toString()));
        assertImage(draft.buildEmail());
        draft.useConfiguredImageResolutionConfig();
        assertInvalidBase(draft);
        draft.withEmbeddedImageAutoResolutionForURLs(false).withPlainText("Disabled");
        assertThat(draft.buildEmail().getPlainText()).isEqualTo("Disabled");
    }

    @Test
    void explicitUrlConversionAndRequiredArgumentsStillRejectBeforeChangingChoices() throws Exception {
        final ImageResolutionConfig original = ImageResolutionConfig.builder().withEmbeddedImageBaseUrl(directory.toUri().toURL()).build();
        final InternalEmailPopulatingBuilder draft = (InternalEmailPopulatingBuilder) malformedFactory(false).emailBuilder().startingBlank()
                .withImageResolutionConfig(original);

        assertThatThrownBy(() -> ImageResolutionConfig.builder().withEmbeddedImageBaseUrl(INVALID_BASE))
                .isInstanceOf(IllegalArgumentException.class).hasCauseInstanceOf(MalformedURLException.class);
        assertThatThrownBy(() -> draft.withEmbeddedImageBaseUrl(INVALID_BASE))
                .isInstanceOf(EmailException.class).hasCauseInstanceOf(MalformedURLException.class);
        assertThatThrownBy(() -> draft.withImageResolutionConfig(null)).isInstanceOf(NullPointerException.class);
        assertThat(draft.getImageResolutionConfig()).isSameAs(original);
        assertThatCode(draft::buildEmail).doesNotThrowAnyException();
    }

    private void applyRepair(final EmailPopulatingBuilder draft, final Repair repair) throws Exception {
        final Path image = directory.resolve("logo.png");
        Files.write(image, new byte[] {1, 2, 3});
        final ImageResolutionConfig.Builder choices = ImageResolutionConfig.builder();
        switch (repair) {
            case DISABLE:
                choices.withEmbeddedImageAutoResolutionForURLs(false);
                draft.withPlainText("Disabled");
                break;
            case REPLACE:
                choices.withEmbeddedImageBaseUrl(directory.toUri().toURL());
                draft.withHTMLText(image("logo.png"));
                break;
            case CLEAR:
                choices.clearEmbeddedImageBaseUrl();
                draft.withHTMLText(image(image.toUri().toString()));
                break;
            default:
                throw new AssertionError(repair);
        }
        draft.withImageResolutionConfig(choices.embeddedImageAutoResolutionMustBeSuccesful(true).build());
    }

    private static SimpleJavaMail malformedFactory(final boolean enabled) {
        return factory(Map.of(PREFIX + "base.url", INVALID_BASE, PREFIX + "enable.url", Boolean.toString(enabled)));
    }

    private static SimpleJavaMail factory(final Map<String, ?> settings) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(settings).load());
    }

    private static void assertInvalidBase(final EmailPopulatingBuilder draft) {
        assertThatThrownBy(draft::buildEmail).isInstanceOf(EmailException.class)
                .hasCauseInstanceOf(MalformedURLException.class).hasMessageContaining(INVALID_BASE);
    }

    private static void assertImage(final Email email) {
        assertThat(email.getEmbeddedImages()).isNotEmpty();
        assertThat(email.getEmbeddedImages().get(email.getEmbeddedImages().size() - 1).readAllBytes())
                .containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(email.getHTMLText()).startsWith("<img src='cid:");
    }

    private static String image(final String source) {
        return "<img src='" + source + "'>";
    }
}
