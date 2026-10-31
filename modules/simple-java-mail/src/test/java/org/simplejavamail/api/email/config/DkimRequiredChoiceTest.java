package org.simplejavamail.api.email.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DkimRequiredChoiceTest {

    @Test
    public void nullDomainReplacementKeepsEarlierChoicesAndSnapshot() {
        final DkimConfig.DkimConfigBuilder builder = configuredBuilder();
        final DkimConfig earlier = builder.build();

        assertThatThrownBy(() -> builder.dkimSigningDomain(null))
                .isInstanceOf(NullPointerException.class).hasMessage("dkimSigningDomain");

        assertThat(builder.getDkimSigningDomain()).isEqualTo("example.org");
        assertThat(builder.getDkimSelector()).isEqualTo("mail");
        assertThat(builder.getDkimPrivateKeyData()).containsExactly(1, 2, 3);
        assertThat(builder.build()).isEqualTo(earlier);
        builder.dkimSigningDomain("replacement.example.org");
        assertThat(earlier.getDkimSigningDomain()).isEqualTo("example.org");
    }

    @Test
    public void nullSelectorReplacementKeepsEarlierChoicesAndSnapshot() {
        final DkimConfig.DkimConfigBuilder builder = configuredBuilder();
        final DkimConfig earlier = builder.build();

        assertThatThrownBy(() -> builder.dkimSelector(null))
                .isInstanceOf(NullPointerException.class).hasMessage("dkimSelector");

        assertThat(builder.getDkimSigningDomain()).isEqualTo("example.org");
        assertThat(builder.getDkimSelector()).isEqualTo("mail");
        assertThat(builder.getDkimPrivateKeyData()).containsExactly(1, 2, 3);
        assertThat(builder.build()).isEqualTo(earlier);
        builder.dkimSelector("replacement");
        assertThat(earlier.getDkimSelector()).isEqualTo("mail");
    }

    @Test
    public void nullDomainLeavesUnfinishedBuilderUnchanged() {
        final DkimConfig.DkimConfigBuilder builder = DkimConfig.builder();

        assertThatThrownBy(() -> builder.dkimSigningDomain(null))
                .isInstanceOf(NullPointerException.class).hasMessage("dkimSigningDomain");

        assertThat(builder.getDkimSigningDomain()).isNull();
        assertThat(builder.getDkimSelector()).isNull();
        assertThat(builder.getDkimPrivateKeyData()).isNull();
    }

    @Test
    public void nullSelectorLeavesUnfinishedBuilderUnchanged() {
        final DkimConfig.DkimConfigBuilder builder = DkimConfig.builder();

        assertThatThrownBy(() -> builder.dkimSelector(null))
                .isInstanceOf(NullPointerException.class).hasMessage("dkimSelector");

        assertThat(builder.getDkimSigningDomain()).isNull();
        assertThat(builder.getDkimSelector()).isNull();
        assertThat(builder.getDkimPrivateKeyData()).isNull();
    }

    @Test
    public void omittedIdentityChoicesRetainTheirExistingConstructionBoundary() {
        final DkimConfig.DkimConfigBuilder builder = DkimConfig.builder();
        assertThat(builder.getDkimSigningDomain()).isNull();
        assertThat(builder.getDkimSelector()).isNull();
        assertThat(builder.getDkimPrivateKeyData()).isNull();

        final DkimConfig config = builder.dkimPrivateKeyData(new byte[] {1}).build();

        assertThat(config.getDkimSigningDomain()).isNull();
        assertThat(config.getDkimSelector()).isNull();
        assertThat(config.getDkimPrivateKeyData()).containsExactly(1);
    }

    @Test
    public void nonNullChoicesRemainUninterpretedUntilTheSigningBoundary() {
        final DkimConfig config = configuredBuilder()
                .dkimSigningDomain("").dkimSelector("")
                .excludedHeadersFromDkimDefaultSigningList("From")
                .signingAlgorithm("future-module-algorithm")
                .build();

        assertThat(config.getDkimSigningDomain()).isEmpty();
        assertThat(config.getDkimSelector()).isEmpty();
        assertThat(config.getExcludedHeadersFromDkimDefaultSigningList()).containsExactly("From");
        assertThat(config.getSigningAlgorithm()).isEqualTo("future-module-algorithm");
    }

    @Test
    public void optionalNullChoicesStillRestoreModuleDefaults() {
        final DkimConfig config = configuredBuilder()
                .useLengthParam(true).headerCanonicalization(DkimConfig.Canonicalization.SIMPLE)
                .bodyCanonicalization(DkimConfig.Canonicalization.SIMPLE)
                .signingAlgorithm("SHA256_WITH_RSA")
                .excludedHeadersFromDkimDefaultSigningList("Date")
                .useLengthParam(null).headerCanonicalization(null).bodyCanonicalization(null)
                .signingAlgorithm(null).excludedHeadersFromDkimDefaultSigningList((String[]) null)
                .build();

        assertThat(config.getUseLengthParam()).isNull();
        assertThat(config.getHeaderCanonicalization()).isNull();
        assertThat(config.getBodyCanonicalization()).isNull();
        assertThat(config.getSigningAlgorithm()).isNull();
        assertThat(config.getExcludedHeadersFromDkimDefaultSigningList()).isNull();
        assertThat(config.getDkimSigningDomain()).isEqualTo("example.org");
        assertThat(config.getDkimSelector()).isEqualTo("mail");
        assertThat(config.getDkimPrivateKeyData()).containsExactly(1, 2, 3);
    }

    private static DkimConfig.DkimConfigBuilder configuredBuilder() {
        return DkimConfig.builder().dkimPrivateKeyData(new byte[] {1, 2, 3})
                .dkimSigningDomain("example.org").dkimSelector("mail");
    }
}
