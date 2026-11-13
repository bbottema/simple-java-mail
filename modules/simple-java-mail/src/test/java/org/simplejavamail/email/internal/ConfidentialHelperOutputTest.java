package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ReplyOptions;
import org.simplejavamail.api.email.ForwardOptions;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.converter.ConfiguredEmailConverter.EmailConversion;
import org.simplejavamail.api.converter.EmailConversionResult;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.mailer.internal.EmailGovernanceImpl;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import testutil.ConfigLoaderTestHelper;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfidentialHelperOutputTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static Email receivedFixture;

    @Test
    void aReplyQuotingActuallyDecryptedContentRequiresProtectionAtTheArtifactBoundary() throws Exception {
        final Email received = decryptedMessage();
        assertThat(received.getPlainText()).isEqualTo("confidential document");
        final Email reply = MAIL.emailBuilder().replyingTo(received).from("reader@example.org").buildEmail();
        assertThat(reply.getPlainText()).contains("confidential document");
        assertThatThrownBy(() -> MAIL.converter().fromEmail(reply).toEmlBytes())
                .isInstanceOf(MailException.class).hasMessageContaining("confidential");
    }

    @ParameterizedTest
    @EnumSource(Output.class)
    void conversionConsentAppliesToEveryTerminalWithoutChangingTheDraft(final Output output) throws Exception {
        final Email source = decryptedMessage();
        final Email reply = MAIL.emailBuilder().replyingTo(source).from("reader@example.org").buildEmail();
        final EmailConversion conversion = MAIL.converter().fromEmail(reply);
        assertThat(conversion.isUnencryptedContentAllowed()).isFalse();
        conversion.allowUnencryptedContent();
        assertThat(conversion.isUnencryptedContentAllowed()).isTrue();
        output.assertUsable(conversion);
        assertThatThrownBy(() -> MAIL.converter().fromEmail(reply).toEmlBytes()).isInstanceOf(MailException.class);
        assertThat(source.getProblems()).isEmpty();
        assertThat(reply.getProblems()).isEmpty();
    }

    @Test
    void clearingConversionConsentRestoresTheDefaultAndDiagnosticRefusalHasNoArtifact() throws Exception {
        final Email reply = MAIL.emailBuilder().replyingTo(decryptedMessage()).from("reader@example.org").buildEmail();
        final EmailConversion conversion = MAIL.converter().fromEmail(reply).allowUnencryptedContent().clearUnencryptedContentPermission();
        assertThat(conversion.isUnencryptedContentAllowed()).isFalse();
        final EmailConversionResult<byte[]> refused = conversion.toEmlBytesResult();
        assertThat(refused.hasValue()).isFalse();
        assertThat(refused.getValue()).isNull();
        assertThat(refused.getFailure()).isInstanceOf(MailException.class).hasMessageContaining("confidential");
        assertThat(refused.getProblems()).singleElement().satisfies(problem -> assertThat(problem.getCode().name()).isEqualTo("PROTECTION_FAILED"));
        assertThat(reply.getProblems()).isEmpty();
    }

    @Test
    void draftConsentSurvivesCopyAndSerializationButANewHelperUsesItsOwnOptions() throws Exception {
        final Email allowed = MAIL.emailBuilder().replyingTo(decryptedMessage(), ReplyOptions.allowUnencryptedContent())
                .from("reader@example.org").buildEmail();
        final Email copy = MAIL.emailBuilder().copying(allowed).buildEmail();
        assertThat(MAIL.converter().fromEmail(copy).toEmlBytes()).isNotEmpty();
        assertThat(MAIL.converter().fromEmail(snapshot(copy)).toEmlBytes()).isNotEmpty();
        final Email newReply = MAIL.emailBuilder().replyingTo(allowed).from("author@example.org").buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(newReply).toEmlBytes()).isInstanceOf(MailException.class);
        final Email newForward = MAIL.emailBuilder().forwarding(allowed).from("reader@example.org").to("other@example.org").buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(newForward).toEmlBytes()).isInstanceOf(MailException.class);
    }

    @Test
    void editingOneBodyDoesNotWaiveTheOtherAndExplicitClearingRemovesOnlyTheClearedComponent() throws Exception {
        final Email reply = MAIL.emailBuilder().replyingTo(decryptedMessage()).from("reader@example.org").buildEmail();
        final Email edited = MAIL.emailBuilder().copying(reply).withPlainText("Answer with a retained quotation").buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(edited).toEmlBytes()).isInstanceOf(MailException.class);
        final Email oneCleared = MAIL.emailBuilder().copying(reply).clearPlainText().buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(oneCleared).toEmlBytes()).isInstanceOf(MailException.class);
        final Email removed = MAIL.emailBuilder().copying(reply).clearPlainText().clearHTMLText().withPlainText("New answer only").buildEmail();
        assertThat(MAIL.converter().fromEmail(removed).toEmlBytes()).isNotEmpty();
    }

    @Test
    void nullableBodyRemovalMatchesNamedClearWhilePresentEmptyReplacementDoesNotWaiveReuse() throws Exception {
        final Email reply = MAIL.emailBuilder().replyingTo(decryptedMessage()).from("reader@example.org").buildEmail();
        final Email empty = MAIL.emailBuilder().copying(reply).withPlainText("").withHTMLText("").buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(empty).toEmlBytes()).isInstanceOf(MailException.class);
        final Email removed = MAIL.emailBuilder().copying(reply).withPlainText((String) null).withHTMLText((String) null)
                .withPlainText("New public answer").buildEmail();
        assertThat(MAIL.converter().fromEmail(removed).toEmlBytes()).isNotEmpty();
        assertThat(reply.getPlainText()).contains("confidential document");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Progress 100%%", "%n", "%1$.0s", "%1$10.000s", "%b", "%%s"})
    void aTemplateThatDoesNotIncludeSourceContentAddsNoHtmlReuseObligation(final String template) throws Exception {
        final Email reply = MAIL.emailBuilder().replyingTo(decryptedMessage(), ReplyOptions.builder().withQuotingTemplate(template).build())
                .clearPlainText().from("reader@example.org").buildEmail();
        assertThat(MAIL.converter().fromEmail(reply).toEmlBytes()).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"%1$s", "%1$.4s", "%%%s", "%s%<s", "%H"})
    void actualSourceArgumentsKeepTheHtmlReuseObligation(final String template) throws Exception {
        final Email reply = MAIL.emailBuilder().replyingTo(decryptedMessage(), ReplyOptions.builder().withQuotingTemplate(template).build())
                .clearPlainText().from("reader@example.org").buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(reply).toEmlBytes()).isInstanceOf(MailException.class);
    }

    @Test
    void suitableOuterEncryptionCoversAnInterpretedForwardAndAnExplicitForwardClearRemovesItsObligation() throws Exception {
        final Email received = decryptedMessage();
        final Email unprotected = MAIL.emailBuilder().forwarding(received).from("reader@example.org").to("other@example.org").buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(unprotected).toEmlBytes()).isInstanceOf(MailException.class);
        final Email protectedForward = MAIL.emailBuilder().copying(unprotected)
                .encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(resource("public-key.asc")).build()).buildEmail();
        final byte[] protectedBytes = MAIL.converter().fromEmail(protectedForward).toEmlBytes();
        final Email opened = open(protectedBytes);
        assertThat(opened.getAttachments()).singleElement().satisfies(resource -> {
            assertThat(resource.getEmail()).isNotNull();
            assertThat(resource.getEmail().getPlainText()).isEqualTo("confidential document");
        });
        final Email removed = MAIL.emailBuilder().copying(unprotected).clearEmailToForward().withPlainText("New message").buildEmail();
        assertThat(MAIL.converter().fromEmail(removed).toEmlBytes()).isNotEmpty();
    }

    @Test
    void explicitOriginalForwardUsesCapturedPayloadAndRejectsUnretainedInput() throws Exception {
        final Email received = decryptedMessage();
        final byte[] captured = received.getInspection().getOriginalMessage().readAllBytes();
        final Email edited = MAIL.emailBuilder().copying(received).withPlainText("Edited readable body").buildEmail();
        final Email forward = MAIL.emailBuilder().forwardingOriginal(edited).from("reader@example.org").to("other@example.org").buildEmail();
        assertThat(forward.getAttachments()).singleElement().satisfies(resource -> assertThat(resource.readAllBytes()).containsExactly(captured));
        final Email emitted = MAIL.converter().fromEml(MAIL.converter().fromEmail(forward).toEmlBytes()).toEmail();
        assertThat(emitted.getAttachments()).singleElement().satisfies(resource -> assertThat(resource.readAllBytes()).containsExactly(captured));
        assertThatThrownBy(() -> MAIL.emailBuilder().forwardingOriginal(MAIL.emailBuilder().startingBlank().buildEmail()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("original input");
    }

    @Test
    void aTemplateCannotManufactureConsentAndFailedEncryptionCannotBeWaived() throws Exception {
        final Email received = decryptedMessage();
        final Email reply = MAIL.emailBuilder().replyingTo(received).from("reader@example.org").buildEmail();
        final Email permissiveTemplate = MAIL.emailBuilder().replyingTo(received, ReplyOptions.allowUnencryptedContent()).buildEmail();
        final EmailGovernanceImpl governance = new EmailGovernanceImpl(null, permissiveTemplate, null, null);
        assertThatThrownBy(() -> MAIL.converter().fromEmail(reply).withEmailGovernance(governance).toEmlBytes()).isInstanceOf(MailException.class);
        final Email brokenEncryption = MAIL.emailBuilder().copying(reply)
                .encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[] {1, 2, 3}).build()).buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(brokenEncryption).allowUnencryptedContent().toEmlBytes())
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedConfidentialTemplateContentRetainsItsObligationWithoutImportingTemplateConsent(final boolean override) throws Exception {
        final Email template = MAIL.emailBuilder().replyingTo(decryptedMessage(), ReplyOptions.allowUnencryptedContent()).buildEmail();
        final Email provided = MAIL.emailBuilder().startingBlank().from("reader@example.org").to("author@example.org").buildEmail();
        final EmailGovernanceImpl governance = new EmailGovernanceImpl(null, override ? null : template, override ? template : null, null);
        assertThatThrownBy(() -> MAIL.converter().fromEmail(provided).withEmailGovernance(governance).toEmlBytes())
                .isInstanceOf(MailException.class).hasMessageContaining("confidential");
        assertThat(MAIL.converter().fromEmail(provided).withEmailGovernance(governance).allowUnencryptedContent().toEmlBytes()).isNotEmpty();
        assertThat(provided.getPlainText()).isNull();
        assertThat(provided.getHTMLText()).isNull();

        final EmailTemplateOptions.Builder exclusion = EmailTemplateOptions.builder();
        if (override) exclusion.dontApplyOverrideValueFor(EmailProperty.BODY_TEXT, EmailProperty.BODY_HTML);
        else exclusion.dontApplyDefaultValueFor(EmailProperty.BODY_TEXT, EmailProperty.BODY_HTML);
        final Email excluded = MAIL.emailBuilder().copying(provided).withTemplateOptions(exclusion.build()).buildEmail();
        assertThat(MAIL.converter().fromEmail(excluded).withEmailGovernance(governance).toEmlBytes()).isNotEmpty();
    }

    @Test
    void unselectedTemplateBodiesDoNotCreateAConfidentialityObligation() throws Exception {
        final Email template = MAIL.emailBuilder().replyingTo(decryptedMessage()).buildEmail();
        final Email provided = MAIL.emailBuilder().startingBlank().from("reader@example.org").to("author@example.org")
                .withPlainText("Public body").withHTMLText("<p>Public body</p>").buildEmail();
        assertThat(MAIL.converter().fromEmail(provided).withEmailGovernance(new EmailGovernanceImpl(null, template, null, null)).toEmlBytes()).isNotEmpty();
        final Email cleared = MAIL.emailBuilder().copying(template).clearPlainText().clearHTMLText().buildEmail();
        assertThat(MAIL.converter().fromEmail(provided).withEmailGovernance(new EmailGovernanceImpl(null, null, cleared, null)).toEmlBytes()).isNotEmpty();
    }

    @Test
    void anUnselectedTemplateResourceDoesNotManufactureAnObligationByObjectIdentity() throws Exception {
        final Email template = MAIL.emailBuilder().replyingTo(receivedImage(false)).clearPlainText().clearHTMLText().buildEmail();
        assertThat(InternalEmail.requireInternalEmail(template).getConfidentialContentState()
                .requires(ConfidentialContentState.Component.EMBEDDED_IMAGES)).isTrue();
        final AttachmentResource manuallySelected = template.getEmbeddedImages().get(0);
        // Manual composition is application-owned; matching an unselected template occurrence must not turn this into helper reuse.
        final Email provided = MAIL.emailBuilder().startingBlank().from("reader@example.org").to("author@example.org")
                .withHTMLText("<img src=\"cid:private@example.org\">").withEmbeddedImage(manuallySelected).buildEmail();
        final EmailGovernanceImpl governance = new EmailGovernanceImpl(null, template, null, null);
        assertThat(MAIL.converter().fromEmail(provided).withEmailGovernance(governance).toEmlBytes()).isNotEmpty();
    }

    @Test
    void bothRehearsalModesAndCustomSubmissionEnforceTheFinalArtifactBoundary() throws Exception {
        final Email reply = MAIL.emailBuilder().replyingTo(decryptedMessage()).from("reader@example.org").buildEmail();
        final AtomicInteger callbacks = new AtomicInteger();
        try (Mailer mailer = MAIL.mailerBuilder().withCustomMailer(new org.simplejavamail.api.mailer.CustomMailer() {
            @Override public void testConnection(final org.simplejavamail.api.mailer.config.OperationalConfig config, final jakarta.mail.Session session) { }
            @Override public void sendMessage(final org.simplejavamail.api.mailer.config.OperationalConfig config, final jakarta.mail.Session session,
                    final Email email, final jakarta.mail.internet.MimeMessage message) { callbacks.incrementAndGet(); }
        }).buildMailer()) {
            assertThatThrownBy(() -> mailer.rehearse(reply)).isInstanceOf(MailException.class);
            assertThatThrownBy(() -> mailer.rehearse(reply, false)).isInstanceOf(MailException.class);
            assertThatThrownBy(() -> mailer.sync().sendMail(reply)).isInstanceOf(MailException.class);
        }
        assertThat(callbacks).hasValue(0);
    }

    @Test
    void consentCannotTurnBasePreviewIntoASkipOfConfiguredEncryption() throws Exception {
        final Email allowed = MAIL.emailBuilder().replyingTo(decryptedMessage(), ReplyOptions.allowUnencryptedContent())
                .from("reader@example.org").buildEmail();
        final Email encrypted = MAIL.emailBuilder().copying(allowed)
                .encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(resource("public-key.asc")).build()).buildEmail();
        try (Mailer mailer = MAIL.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            assertThat(mailer.rehearse(allowed, false).getEmlBytes()).isNotEmpty();
            assertThatThrownBy(() -> mailer.rehearse(encrypted, false)).isInstanceOf(MailException.class).hasMessageContaining("secured rehearsal");
            assertThat(mailer.rehearse(encrypted).getEmlBytes()).isNotEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privateSubjectsUseOnlyAKnownPublicCounterpartAndPrivateAncestryIsOmitted(final boolean publicCounterpart) throws Exception {
        final Email authored = MAIL.emailBuilder().startingBlank().from("author@example.org").to("reader@example.org")
                .withSubject("Acquisition of Acme").fixingMessageId("<private-parent@example.org>")
                .withHeader("References", "<private-ancestor@example.org>").withPlainText("confidential document").buildEmail();
        final Email received = MAIL.converter().fromEml(ProtectedHeaderFixture.encrypted(MAIL, authored, publicCounterpart))
                .withSmimeDecryption(org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore()).toEmail();
        assertThat(received.getSubject()).isEqualTo("Acquisition of Acme");
        final Email reply = MAIL.emailBuilder().replyingTo(received, ReplyOptions.allowUnencryptedContent())
                .from("reader@example.org").buildEmail();
        assertThat(reply.getSubject()).isEqualTo(publicCounterpart ? "Re: Public wrapper" : "Re: Your message");
        final jakarta.mail.internet.MimeMessage emitted = MAIL.converter().fromEmail(reply).toMimeMessage();
        assertThat(emitted.getSubject()).isEqualTo(reply.getSubject());
        assertThat(emitted.getHeader("In-Reply-To")).isNull();
        assertThat(emitted.getHeader("References")).isNull();
        final Email forward = MAIL.emailBuilder().forwarding(received, ForwardOptions.allowUnencryptedContent()).buildEmail();
        assertThat(forward.getSubject()).isEqualTo(publicCounterpart ? "Fwd: Public wrapper" : "Fwd: Message");
    }

    @Test
    void aProtectedAuthoredViewWithoutAnIdStillDoesNotDiscloseItsPrivateReferences() throws Exception {
        final Email authored = MAIL.emailBuilder().startingBlank().from("author@example.org").to("reader@example.org")
                .withHeader("References", "<private-ancestor@example.org>").withPlainText("confidential document").buildEmail();
        final Email received = MAIL.converter().fromEml(ProtectedHeaderFixture.encrypted(MAIL, authored))
                .withSmimeDecryption(org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore()).toEmail();
        assertThat(received.getId()).isNull();
        final Email reply = MAIL.emailBuilder().replyingTo(received, ReplyOptions.allowUnencryptedContent()).from("reader@example.org").buildEmail();
        assertThat(reply.getHeaders()).doesNotContainKeys("References", "In-Reply-To");
    }

    @Test
    void editingTheIdDoesNotEraseTheIndependentPrivacyOfRetainedAncestry() throws Exception {
        final Email authored = MAIL.emailBuilder().startingBlank().from("author@example.org").to("reader@example.org")
                .fixingMessageId("<private-parent@example.org>").withHeader("References", "<private-ancestor@example.org>")
                .withPlainText("confidential document").buildEmail();
        final Email received = MAIL.converter().fromEml(ProtectedHeaderFixture.encrypted(MAIL, authored))
                .withSmimeDecryption(org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore()).toEmail();
        final Email edited = MAIL.emailBuilder().copying(received).fixingMessageId("<caller-id@example.org>").buildEmail();
        assertThat(edited.getHeaders().get("References")).containsExactly("<private-ancestor@example.org>");
        for (Email current : new Email[] {edited, snapshot(edited), MAIL.emailBuilder().copying(edited).buildEmail()}) {
            final Email reply = MAIL.emailBuilder().replyingTo(current, ReplyOptions.allowUnencryptedContent())
                    .from("reader@example.org").buildEmail();
            assertThat(reply.getHeaders()).doesNotContainKeys("References", "In-Reply-To");
        }
        final Email reprotected = MAIL.emailBuilder().copying(edited)
                .encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(resource("public-key.asc")).build()).buildEmail();
        final Email forward = MAIL.emailBuilder().forwarding(reprotected).from("reader@example.org").to("other@example.org").buildEmail();
        assertThatThrownBy(() -> MAIL.converter().fromEmail(forward).toEmlBytes()).isInstanceOf(MailException.class);

        final Email publicAncestry = MAIL.emailBuilder().copying(edited).withHeader("References", "<public-ancestor@example.org>", true).buildEmail();
        final Email publicReply = MAIL.emailBuilder().replyingTo(publicAncestry, ReplyOptions.allowUnencryptedContent()).buildEmail();
        assertThat(publicReply.getHeaders().get("References")).containsExactly("<public-ancestor@example.org> <caller-id@example.org>");
        final Email cleared = MAIL.emailBuilder().copying(edited).clearHeaders().buildEmail();
        final Email clearedReply = MAIL.emailBuilder().replyingTo(cleared, ReplyOptions.allowUnencryptedContent()).buildEmail();
        assertThat(clearedReply.getHeaders().get("References")).containsExactly("<caller-id@example.org>");
    }

    @Test
    void replyImageConfidentialityFollowsTheChosenOccurrenceRatherThanAnUnusedPrivateNeighbor() throws Exception {
        final Email received = receivedImage(false);
        final Email changed = MAIL.emailBuilder().copying(received).withSubject("Public subject").withPlainText("Public text")
                .withHTMLText("<img src=\"cid:public@example.org\">")
                .withEmbeddedImage(AttachmentResource.builder().withName("public.png").withContentId("public@example.org")
                        .withData(new byte[] {9, 8, 7}, "image/png").build()).buildEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(changed).from("reader@example.org").buildEmail();
        assertThat(reply.getEmbeddedImages()).singleElement().satisfies(resource -> assertThat(resource.readAllBytes()).containsExactly(9, 8, 7));
        assertThat(MAIL.converter().fromEmail(reply).toEmlBytes()).isNotEmpty();
        assertThat(received.getEmbeddedImages()).singleElement().satisfies(resource -> assertThat(resource.readAllBytes()).containsExactly(1, 2, 3));
    }

    @Test
    void anIndependentlyAttachedPrivateImageReusedInlineKeepsItsConfidentialityObligation() throws Exception {
        final Email received = receivedImage(true);
        assertThat(received.getAttachments()).singleElement().satisfies(resource -> assertThat(resource.getContentId()).isEqualTo("private@example.org"));
        final Email changed = MAIL.emailBuilder().copying(received).withSubject("Public subject").withPlainText("Public text")
                .withHTMLText("<img src=\"cid:private@example.org\">").buildEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(changed).from("reader@example.org").buildEmail();
        assertThat(reply.getEmbeddedImages()).singleElement().satisfies(resource -> assertThat(resource.readAllBytes()).containsExactly(1, 2, 3));
        assertThatThrownBy(() -> MAIL.converter().fromEmail(reply).toEmlBytes()).isInstanceOf(MailException.class);
        final Email cleared = MAIL.emailBuilder().copying(reply).clearEmbeddedImages().withHTMLText("<p>Public replacement</p>").buildEmail();
        assertThat(MAIL.converter().fromEmail(cleared).toEmlBytes()).isNotEmpty();
    }

    private static Email receivedImage(final boolean ordinaryAttachment) throws Exception {
        final AttachmentResource image = AttachmentResource.builder().withName("private.png").withContentId("private@example.org")
                .withData(new byte[] {1, 2, 3}, "image/png").build();
        final org.simplejavamail.api.email.EmailPopulatingBuilder source = MAIL.emailBuilder().startingBlank().from("author@example.org")
                .to("reader@example.org").withHTMLText("<p>Private image</p><img src=\"cid:private@example.org\">");
        if (ordinaryAttachment) source.withAttachment(image); else source.withEmbeddedImage(image);
        return MAIL.converter().fromEml(ProtectedHeaderFixture.encrypted(MAIL, source.buildEmail()))
                .withSmimeDecryption(org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore()).toEmail();
    }

    @Test
    void operationOptionsSnapshotTheirChoicesWithoutAffectingOtherBuilders() {
        final ReplyOptions.Builder source = ReplyOptions.builder().withQuotingTemplate("<section>%s</section>").withUnencryptedContentAllowed(true);
        final ReplyOptions first = source.build();
        source.resetQuotingTemplate().resetUnencryptedContentAllowed();
        assertThat(first.getQuotingTemplate()).isEqualTo("<section>%s</section>");
        assertThat(first.isUnencryptedContentAllowed()).isTrue();
        assertThat(source.build()).isEqualTo(ReplyOptions.defaults());
        assertThat(first.toBuilder().build()).isEqualTo(first);
        assertThat(ForwardOptions.allowUnencryptedContent().toBuilder().resetUnencryptedContentAllowed().build()).isEqualTo(ForwardOptions.defaults());
        assertThatThrownBy(() -> source.withQuotingTemplate(null)).isInstanceOf(NullPointerException.class);
        assertThat(source.getQuotingTemplate()).isEqualTo(org.simplejavamail.api.email.EmailStartingBuilder.DEFAULT_QUOTING_MARKUP);
    }

    private static synchronized Email decryptedMessage() throws Exception {
        if (receivedFixture != null) return receivedFixture;
        final byte[] publicKey = resource("public-key.asc");
        final Email outgoing = MAIL.emailBuilder().startingBlank().from("author@example.org").to("reader@example.org")
                .withSubject("Public envelope").withPlainText("confidential document")
                .fixingMessageId("<confidential-fixture-" + java.util.UUID.randomUUID() + "@example.test>")
                .encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(publicKey).build()).buildEmail();
        final byte[] encrypted = MAIL.converter().fromEmail(outgoing).toEmlBytes();
        receivedFixture = open(encrypted);
        return receivedFixture;
    }

    private static Email open(final byte[] encrypted) throws Exception {
        return MAIL.converter().fromEml(encrypted).withOpenPgpReceiveConfig(OpenPgpReceiveConfig.builder()
                .addDecryptionKeyRing(resource("private-key.asc"), "openpgpjs-fixture-passphrase").build()).toEmail();
    }

    private static Email snapshot(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) input.readObject(); }
    }

    private enum Output {
        MIME { @Override void assertUsable(final EmailConversion conversion) { assertThat(conversion.toMimeMessage()).isNotNull(); } },
        EML { @Override void assertUsable(final EmailConversion conversion) { assertThat(conversion.toEml()).contains("confidential document"); } },
        BYTES { @Override void assertUsable(final EmailConversion conversion) { assertThat(new String(conversion.toEmlBytes(), StandardCharsets.UTF_8)).contains("confidential document"); } },
        MIME_RESULT { @Override void assertUsable(final EmailConversion conversion) { assertThat(conversion.toMimeMessageResult().hasValue()).isTrue(); } },
        EML_RESULT { @Override void assertUsable(final EmailConversion conversion) { assertThat(conversion.toEmlResult().getValue()).contains("confidential document"); } },
        BYTES_RESULT { @Override void assertUsable(final EmailConversion conversion) { assertThat(conversion.toEmlBytesResult().hasValue()).isTrue(); } };
        abstract void assertUsable(EmailConversion conversion);
    }

    private static byte[] resource(final String name) throws Exception {
        try (InputStream input = ConfidentialHelperOutputTest.class.getResourceAsStream("/openpgpjs/" + name)) {
            return input.readAllBytes();
        }
    }
}
