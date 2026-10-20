package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.EmailProperty.OPENPGP_ENCRYPTION_CONFIG;
import static org.simplejavamail.api.email.EmailProperty.OPENPGP_SIGNING_CONFIG;
import static org.simplejavamail.api.email.EmailProperty.SMIME_SIGNING_CONFIG;
import static org.simplejavamail.api.email.EmailProperty.SUBJECT;

class EmailGovernanceOpenPgpTest {

    private final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void defaultsFillAbsentPoliciesAndExplicitPoliciesReplaceThem() {
        final Email defaults = protectedEmail((byte) 1);
        final Email supplied = protectedEmail((byte) 2);
        final EmailGovernanceImpl governance = governance(defaults, null);

        assertPolicies(governance.produceEmailApplyingDefaultsAndOverrides(blank().buildEmail()), defaults);
        assertPolicies(governance.produceEmailApplyingDefaultsAndOverrides(supplied), supplied);
        assertPolicies(governance.produceEmailApplyingDefaultsAndOverrides(null), defaults);
        assertThat(supplied.getOpenPgpEncryptionConfig().getRecipientPublicKeyRings()).hasSize(1);
    }

    @Test
    void overridesReplaceCompletePoliciesWithoutCombiningRecipientKeys() {
        final Email defaults = protectedEmail((byte) 1);
        final Email supplied = protectedEmail((byte) 2);
        final Email overrides = protectedEmail((byte) 3);

        final Email resolved = governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(supplied);

        assertPolicies(resolved, overrides);
        assertThat(resolved.getOpenPgpEncryptionConfig().getRecipientPublicKeyRings()).hasSize(1);
        assertPolicies(supplied, protectedEmail((byte) 2));
    }

    @Test
    void wholeEmailSuppressionKeepsItsExistingMeaning() {
        final Email defaults = protectedEmail((byte) 1);
        final Email overrides = protectedEmail((byte) 2);
        final EmailGovernanceImpl governance = governance(defaults, overrides);

        assertPolicies(governance.produceEmailApplyingDefaultsAndOverrides(blank().withTemplateOptions(EmailTemplateOptions.builder().ignoringOverrides().build()).buildEmail()), defaults);
        assertNoPolicies(governance.produceEmailApplyingDefaultsAndOverrides(blank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().build()).buildEmail()));
        assertNoPolicies(governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(blank().withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build()).buildEmail()));
    }

    @Test
    void eachPolicyCanSuppressDefaultsAndOverridesIndependently() {
        final Email defaults = protectedEmail((byte) 1);
        final Email supplied = protectedEmail((byte) 2);
        final Email overrides = protectedEmail((byte) 3);
        final Email encryptionFromDefault = blank()
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(OPENPGP_SIGNING_CONFIG).build())
                .buildEmail();
        final Email signingFromDefault = blank()
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(OPENPGP_ENCRYPTION_CONFIG).build())
                .buildEmail();
        final Email explicitSigning = factory.emailBuilder().copying(supplied)
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyOverrideValueFor(OPENPGP_SIGNING_CONFIG).build())
                .buildEmail();
        final Email explicitEncryption = factory.emailBuilder().copying(supplied)
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyOverrideValueFor(OPENPGP_ENCRYPTION_CONFIG).build())
                .buildEmail();

        final Email onlyEncryption = governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(encryptionFromDefault);
        assertThat(onlyEncryption.getOpenPgpSigningConfig()).isNull();
        assertThat(onlyEncryption.getOpenPgpEncryptionConfig()).isSameAs(defaults.getOpenPgpEncryptionConfig());
        final Email onlySigning = governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(signingFromDefault);
        assertThat(onlySigning.getOpenPgpSigningConfig()).isSameAs(defaults.getOpenPgpSigningConfig());
        assertThat(onlySigning.getOpenPgpEncryptionConfig()).isNull();
        final Email mixedSigning = governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(explicitSigning);
        assertThat(mixedSigning.getOpenPgpSigningConfig()).isSameAs(supplied.getOpenPgpSigningConfig());
        assertThat(mixedSigning.getOpenPgpEncryptionConfig()).isSameAs(overrides.getOpenPgpEncryptionConfig());
        final Email mixedEncryption = governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(explicitEncryption);
        assertThat(mixedEncryption.getOpenPgpSigningConfig()).isSameAs(overrides.getOpenPgpSigningConfig());
        assertThat(mixedEncryption.getOpenPgpEncryptionConfig()).isSameAs(supplied.getOpenPgpEncryptionConfig());
    }

    @Test
    void clearingPermitsFallbackWithoutChangingTemplates() {
        final Email defaults = protectedEmail((byte) 1);
        final Email supplied = factory.emailBuilder().copying(protectedEmail((byte) 2)).clearOpenPgp().buildEmail();

        assertNoPolicies(supplied);
        assertPolicies(governance(defaults, null).produceEmailApplyingDefaultsAndOverrides(supplied), defaults);
        assertPolicies(defaults, protectedEmail((byte) 1));
    }

    @Test
    void copyingPreservesKeysAndAllGovernanceSuppressionChoices() {
        final Email original = factory.emailBuilder().copying(protectedEmail((byte) 1))
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().dontApplyDefaultValueFor(SUBJECT, OPENPGP_SIGNING_CONFIG).dontApplyOverrideValueFor(SUBJECT, OPENPGP_ENCRYPTION_CONFIG).build())
                .buildEmail();

        final Email copy = factory.emailBuilder().copying(original).buildEmail();

        assertPolicies(copy, original);
        assertThat(copy.isIgnoreDefaults()).isTrue();
        assertThat(copy.isIgnoreOverrides()).isTrue();
        assertThat(copy.getPropertiesNotToApplyDefaultValueFor()).containsExactlyInAnyOrder(SUBJECT, OPENPGP_SIGNING_CONFIG);
        assertThat(copy.getPropertiesNotToApplyOverrideValueFor()).containsExactlyInAnyOrder(SUBJECT, OPENPGP_ENCRYPTION_CONFIG);
    }

    @Test
    void copyingRetainsSuppressionBehaviorForExistingOrdinaryFields() {
        final Email original = blank().withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(SUBJECT).dontApplyOverrideValueFor(SUBJECT).build()).buildEmail();
        final Email copied = factory.emailBuilder().copying(original).buildEmail();
        final Email defaults = blank().withSubject("default subject").buildEmail();
        final Email overrides = blank().withSubject("override subject").buildEmail();

        assertThat(governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(copied).getSubject()).isNull();
        assertThat(governance(defaults, overrides).produceEmailApplyingDefaultsAndOverrides(blank().buildEmail()).getSubject())
                .isEqualTo("override subject");
    }

    @Test
    void serializationPreservesOpenPgpMaterialAndSuppressionPrecedence() throws Exception {
        final Email original = factory.emailBuilder().copying(protectedEmail((byte) 1))
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(OPENPGP_SIGNING_CONFIG).dontApplyOverrideValueFor(OPENPGP_ENCRYPTION_CONFIG).build())
                .buildEmail();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Email) input.readObject();
        }

        assertPolicies(restored, original);
        assertThat(restored.getPropertiesNotToApplyDefaultValueFor()).containsExactly(OPENPGP_SIGNING_CONFIG);
        assertThat(restored.getPropertiesNotToApplyOverrideValueFor()).containsExactly(OPENPGP_ENCRYPTION_CONFIG);
        final Email resolved = governance(protectedEmail((byte) 2), protectedEmail((byte) 3))
                .produceEmailApplyingDefaultsAndOverrides(restored);
        assertThat(resolved.getOpenPgpSigningConfig()).isEqualTo(signing((byte) 3));
        assertThat(resolved.getOpenPgpEncryptionConfig()).isEqualTo(encryption((byte) 1));
    }

    @Test
    void incompatibleProtectionFamiliesFailUntilTheInheritedFamilyIsSuppressed() {
        final Email smime = blank().signWithSmime(mock(SmimeSigningConfig.class)).buildEmail();
        final Email openPgp = protectedEmail((byte) 1);

        assertThatThrownBy(() -> governance(openPgp, null).produceEmailApplyingDefaultsAndOverrides(smime))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("S/MIME and OpenPGP/MIME are both configured");
        assertThatThrownBy(() -> governance(null, openPgp).produceEmailApplyingDefaultsAndOverrides(smime))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("S/MIME and OpenPGP/MIME are both configured");
        final Email suppressOpenPgp = factory.emailBuilder().copying(smime)
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(OPENPGP_SIGNING_CONFIG, OPENPGP_ENCRYPTION_CONFIG).dontApplyOverrideValueFor(OPENPGP_SIGNING_CONFIG, OPENPGP_ENCRYPTION_CONFIG).build())
                .buildEmail();
        final Email resolvedSmime = governance(openPgp, openPgp).produceEmailApplyingDefaultsAndOverrides(suppressOpenPgp);
        assertNoPolicies(resolvedSmime);
        assertThat(resolvedSmime.getSmimeSigningConfig()).isSameAs(smime.getSmimeSigningConfig());
        final Email suppressSmime = factory.emailBuilder().copying(openPgp)
                .withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(SMIME_SIGNING_CONFIG).dontApplyOverrideValueFor(SMIME_SIGNING_CONFIG).build())
                .buildEmail();
        assertPolicies(governance(smime, smime).produceEmailApplyingDefaultsAndOverrides(suppressSmime), openPgp);
    }

    private EmailPopulatingBuilder blank() {
        return factory.emailBuilder().startingBlank();
    }

    private Email protectedEmail(final byte keyMarker) {
        return blank().signWithOpenPgp(signing(keyMarker)).encryptWithOpenPgp(encryption(keyMarker)).buildEmail();
    }

    private EmailGovernanceImpl governance(final Email defaults, final Email overrides) {
        return new EmailGovernanceImpl(factory.getConfig(), factory.emailBuilder(), null, defaults, overrides, null);
    }

    private static OpenPgpSigningConfig signing(final byte keyMarker) {
        return OpenPgpSigningConfig.builder().secretKeyRing(new byte[]{keyMarker}).build();
    }

    private static OpenPgpEncryptionConfig encryption(final byte keyMarker) {
        return OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[]{keyMarker}).build();
    }

    private static void assertPolicies(final Email actual, final Email expected) {
        assertThat(actual.getOpenPgpSigningConfig()).isEqualTo(expected.getOpenPgpSigningConfig());
        assertThat(actual.getOpenPgpEncryptionConfig()).isEqualTo(expected.getOpenPgpEncryptionConfig());
    }

    private static void assertNoPolicies(final Email email) {
        assertThat(email.getOpenPgpSigningConfig()).isNull();
        assertThat(email.getOpenPgpEncryptionConfig()).isNull();
    }
}
