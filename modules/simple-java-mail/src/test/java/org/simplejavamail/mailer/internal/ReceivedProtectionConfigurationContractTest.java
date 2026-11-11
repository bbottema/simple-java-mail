package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.ApprovedSigners;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.ForwardOptions;
import org.simplejavamail.api.email.ProtectionRequirements;
import org.simplejavamail.api.email.ReplyOptions;
import org.simplejavamail.api.email.ValueInspection;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import testutil.ConfigLoaderTestHelper;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.Owner.APPROVED_SIGNERS;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.Owner.CONVERTER_CONTENT_PERMISSION;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.Owner.EMAIL_HELPER_OPTIONS;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.Owner.PROTECTION_REQUIREMENTS;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Actual received content/output witnesses for application requirements and operation-local consent. No global property owns these choices. */
class ReceivedProtectionConfigurationContractTest {
    private static final String PGP = "0123456789ABCDEF0123456789ABCDEF01234567";
    private static final String OTHER_PGP = "FEDCBA9876543210FEDCBA9876543210FEDCBA98";
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    static Stream<Method> optionRoutes() {
        return EmailContractTestSupport.routes(Set.of(EMAIL_HELPER_OPTIONS), ReplyOptions.Builder.class, ForwardOptions.Builder.class);
    }

    static Stream<Method> conversionRoutes() {
        return EmailContractTestSupport.routes(Set.of(CONVERTER_CONTENT_PERMISSION), ConfiguredEmailConverter.EmailConversion.class);
    }

    static Stream<Method> requirementRoutes() {
        return EmailContractTestSupport.routes(Set.of(PROTECTION_REQUIREMENTS), ProtectionRequirements.Builder.class);
    }

    static Stream<Method> approvalRoutes() {
        return EmailContractTestSupport.routes(Set.of(APPROVED_SIGNERS), ApprovedSigners.Builder.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optionRoutes")
    void everyHelperOptionChangesItsQuoteOrActualOutputPermission(final Method method, @TempDir final Path directory) throws Exception {
        ConfigurationAdoptionCatalogue.assertValueBuilderCategory(EMAIL_HELPER_OPTIONS, method.getDeclaringClass());
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        final Email received = received(fixture, true);
        final boolean reply = method.getDeclaringClass() == ReplyOptions.Builder.class;
        final Object builder = reply ? ReplyOptions.builder().withQuotingTemplate("<aside>%s</aside>").withUnencryptedContentAllowed(true)
                : ForwardOptions.builder().withUnencryptedContentAllowed(true);
        final Object[] arguments = method.getParameterCount() == 0 ? new Object[0]
                : new Object[] {method.getParameterTypes()[0] == String.class ? "<article>%s</article>" : false};
        assertThat(invoke(builder, method, arguments)).isSameAs(builder);
        final boolean permission;
        final Email draft;
        if (reply) {
            final ReplyOptions options = ((ReplyOptions.Builder) builder).build();
            permission = options.isUnencryptedContentAllowed();
            final String expectedQuote = method.getName().equals("withQuotingTemplate") ? "<article>%s</article>"
                    : method.getName().equals("resetQuotingTemplate") ? EmailStartingBuilder.DEFAULT_QUOTING_MARKUP : "<aside>%s</aside>";
            assertThat(options.getQuotingTemplate()).isEqualTo(expectedQuote);
            draft = mail.emailBuilder().replyingTo(received, options).from("writer@example.org").buildEmail();
            assertThat(draft.getHTMLText()).contains("Partner content");
            if (expectedQuote.startsWith("<article>")) assertThat(draft.getHTMLText()).contains("<article>");
            if (expectedQuote.startsWith("<aside>")) assertThat(draft.getHTMLText()).contains("<aside>");
        } else {
            final ForwardOptions options = ((ForwardOptions.Builder) builder).build();
            permission = options.isUnencryptedContentAllowed();
            draft = mail.emailBuilder().forwarding(received, options).from("writer@example.org").to("next@example.org").buildEmail();
        }
        assertThat(permission).isEqualTo(method.getName().contains("QuotingTemplate"));
        outputMatchesPermission(draft, permission);
        record("rule", "email.helper-option-selection");
        record("case", "email.helper-options");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversionRoutes")
    void everyConversionPermissionRouteControlsOutputWithoutChangingTheDraft(final Method method, @TempDir final Path directory) throws Exception {
        ConfigurationAdoptionCatalogue.assertApiCategory(CONVERTER_CONTENT_PERMISSION, method.getDeclaringClass(), method.getName());
        final Email received = received(new EmailContractTestSupport(directory), true);
        final Email draft = mail.emailBuilder().replyingTo(received).from("writer@example.org").fixingMessageId("<conversion-consent@example.org>").buildEmail();
        final ConfiguredEmailConverter.EmailConversion conversion = mail.converter().fromEmail(draft);
        if (method.getName().startsWith("clear")) conversion.allowUnencryptedContent();
        assertThat(invoke(conversion, method)).isSameAs(conversion);
        final boolean allowed = method.getName().equals("allowUnencryptedContent");
        assertThat(conversion.isUnencryptedContentAllowed()).isEqualTo(allowed);
        if (allowed) assertThat(conversion.toEmlBytes()).isNotEmpty();
        else assertThatThrownBy(conversion::toEmlBytes).hasMessageContaining("confidential");
        assertThat(mail.converter().fromEmail(draft).isUnencryptedContentAllowed()).isFalse();
        assertThatThrownBy(() -> mail.converter().fromEmail(draft).toMimeMessage()).hasMessageContaining("confidential");
        assertThat(draft.getPlainText()).contains("Partner content");
        record("rule", "converter.local-content-permission");
        record("case", "converter.permission-isolation");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requirementRoutes")
    void everyRequirementChoiceAssessesTheActualCapturedDocument(final Method method, @TempDir final Path directory) throws Exception {
        ConfigurationAdoptionCatalogue.assertValueBuilderCategory(PROTECTION_REQUIREMENTS, method.getDeclaringClass());
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        final Email protectedEmail = received(fixture, true);
        final ValueInspection protectedDocument = protectedEmail.getInspection().getAttachment(0);
        final ApprovedSigners approved = ApprovedSigners.builder().addSmimeCertificate(fixture.certificate).build();
        final ProtectionRequirements.Builder builder = ProtectionRequirements.builder().requireVerifiedSignatureFrom(approved).requireDecryptedContent()
                .requireAllSignaturesVerified();
        if (method.getName().equals("requireVerifiedSignatureFrom")) {
            builder.requireVerifiedSignatureFrom(ApprovedSigners.builder().addOpenPgpSigningKeyFingerprint(PGP).build());
            assertThat(builder.build().check(protectedDocument).isSatisfied()).isFalse();
        }
        if (method.getName().equals("clearVerifiedSignatureRequirement")) builder.clearAllSignaturesVerifiedRequirement();
        if (method.getName().equals("requireDecryptedContent")) builder.clearDecryptedContentRequirement();
        if (method.getName().equals("requireAllSignaturesVerified")) builder.clearAllSignaturesVerifiedRequirement();
        assertThat(invoke(builder, method, method.getParameterCount() == 0 ? new Object[0] : new Object[] {approved})).isSameAs(builder);
        final ProtectionRequirements selected = builder.build();
        assertThat(selected.getApprovedSigners()).isSameAs(method.getName().equals("clearVerifiedSignatureRequirement") ? null : approved);
        assertThat(selected.isDecryptedContentRequired()).isEqualTo(!method.getName().equals("clearDecryptedContentRequirement"));
        assertThat(selected.isAllSignaturesVerifiedRequired()).isEqualTo(!method.getName().equals("clearAllSignaturesVerifiedRequirement")
                && !method.getName().equals("clearVerifiedSignatureRequirement"));
        assertThat(selected.check(protectedDocument).isSatisfied()).isTrue();
        final ValueInspection signedOnly = received(fixture, false).getInspection().getAttachment(0);
        assertThat(selected.check(signedOnly).isSatisfied()).isEqualTo(!selected.isDecryptedContentRequired());
        assertThat(protectedEmail.getAttachments().get(0).readAllData()).isEqualTo("Required document");
        record("rule", "protection.application-requirements");
        record("case", "protection.policy-selection");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("approvalRoutes")
    void everyApprovalChoicePinsOnlyItsSelectedKeyFamily(final Method method, @TempDir final Path directory) throws Exception {
        ConfigurationAdoptionCatalogue.assertValueBuilderCategory(APPROVED_SIGNERS, method.getDeclaringClass());
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        final ValueInspection document = received(fixture, true).getInspection().getAttachment(0);
        final ApprovedSigners.Builder builder = ApprovedSigners.builder().addOpenPgpSigningKeyFingerprint(PGP);
        if (!method.getName().equals("addSmimeCertificate")) builder.addSmimeCertificate(fixture.certificate);
        final Object[] arguments = method.getParameterCount() == 0 ? new Object[0]
                : new Object[] {method.getName().equals("addSmimeCertificate") ? fixture.certificate : OTHER_PGP};
        assertThat(invoke(builder, method, arguments)).isSameAs(builder);
        final ApprovedSigners selected = builder.build();
        assertThat(selected.getSmimeSigningKeyFingerprints().isEmpty()).isEqualTo(method.getName().equals("clearSmimeCertificates"));
        assertThat(selected.getOpenPgpSigningKeyFingerprints()).containsExactlyElementsOf(method.getName().equals("clearOpenPgpSigningKeyFingerprints")
                ? List.of() : method.getName().equals("addOpenPgpSigningKeyFingerprint") ? List.of(PGP, OTHER_PGP) : List.of(PGP));
        assertThat(ProtectionRequirements.builder().requireVerifiedSignatureFrom(selected).build().check(document).isSatisfied())
                .isEqualTo(!method.getName().equals("clearSmimeCertificates"));
        record("rule", "protection.approved-signing-keys");
        record("case", "protection.key-selection");
    }

    @Test
    void optionCopiesPreserveChoicesWhileEditsControlTheNewDraftOnly(@TempDir final Path directory) throws Exception {
        final Email received = received(new EmailContractTestSupport(directory), true);
        final ReplyOptions reply = ReplyOptions.builder().withQuotingTemplate("<aside>%s</aside>").withUnencryptedContentAllowed(true).build();
        final ReplyOptions.Builder replyCopy = (ReplyOptions.Builder) invoke(reply, ReplyOptions.class.getMethod("toBuilder"));
        assertThat(replyCopy.getQuotingTemplate()).isEqualTo("<aside>%s</aside>");
        outputMatchesPermission(mail.emailBuilder().replyingTo(received, reply).from("writer@example.org").buildEmail(), true);
        outputMatchesPermission(mail.emailBuilder().replyingTo(received, replyCopy.resetUnencryptedContentAllowed().build())
                .from("writer@example.org").buildEmail(), false);
        assertThat(reply.isUnencryptedContentAllowed()).isTrue();
        final ForwardOptions forward = ForwardOptions.allowUnencryptedContent();
        final ForwardOptions.Builder forwardCopy = (ForwardOptions.Builder) invoke(forward, ForwardOptions.class.getMethod("toBuilder"));
        assertThat(forwardCopy.isUnencryptedContentAllowed()).isTrue();
        outputMatchesPermission(mail.emailBuilder().forwarding(received, forwardCopy.resetUnencryptedContentAllowed().build())
                .from("writer@example.org").to("next@example.org").buildEmail(), false);
        assertThat(forward.isUnencryptedContentAllowed()).isTrue();
        record("rule", "email.helper-option-copy");
        record("case", "email.helper-option-copy");
    }

    @Test
    void requirementAndApprovalCopiesRetainTheirIndependentApplicationChoices(@TempDir final Path directory) throws Exception {
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        final ValueInspection document = received(fixture, true).getInspection().getAttachment(0);
        final ApprovedSigners approved = ApprovedSigners.builder().addSmimeCertificate(fixture.certificate).addOpenPgpSigningKeyFingerprint(PGP).build();
        final ApprovedSigners.Builder approvalCopy = (ApprovedSigners.Builder) invoke(approved, ApprovedSigners.class.getMethod("toBuilder"));
        final ApprovedSigners removed = approvalCopy.clearSmimeCertificates().build();
        final ProtectionRequirements policy = ProtectionRequirements.builder().requireVerifiedSignatureFrom(approved).requireDecryptedContent().build();
        final ProtectionRequirements.Builder policyCopy = (ProtectionRequirements.Builder) invoke(policy, ProtectionRequirements.class.getMethod("toBuilder"));
        assertThat(policyCopy.requireVerifiedSignatureFrom(removed).build().check(document).isSatisfied()).isFalse();
        assertThat(policy.check(document).isSatisfied()).isTrue();
        assertThat(approved.getSmimeSigningKeyFingerprints()).isNotEmpty();
        record("rule", "protection.application-policy-copy");
        record("case", "protection.policy-copy");
    }

    private Email received(final EmailContractTestSupport fixture, final boolean encrypted) {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().from("partner@example.org").to("receiver@example.org")
                .fixingMessageId("<protected-configuration-source@example.org>")
                .withSubject("Partner document").withPlainText("Partner content").withHTMLText("<p>Partner content</p>")
                .withAttachment("document.txt", "Required document".getBytes(StandardCharsets.UTF_8), "text/plain")
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build());
        if (encrypted) builder.encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(fixture.certificate).build());
        final byte[] source = mail.converter().fromEmail(builder.buildEmail()).toEmlBytes();
        return mail.converter().fromEml(source).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
    }

    private void outputMatchesPermission(final Email draft, final boolean permitted) {
        final Email output = mail.emailBuilder().copying(draft).fixingMessageId("<helper-consent@example.org>").buildEmail();
        if (permitted) assertThat(mail.converter().fromEmail(output).toEmlBytes()).isNotEmpty();
        else assertThatThrownBy(() -> mail.converter().fromEmail(output).toEmlBytes()).hasMessageContaining("confidential");
    }
}
