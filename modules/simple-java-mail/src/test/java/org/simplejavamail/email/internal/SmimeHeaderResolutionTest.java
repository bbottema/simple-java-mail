package org.simplejavamail.email.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmimeHeaderResolutionTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @ParameterizedTest
    @ValueSource(strings = {"X-Route", "x-route"})
    void innerHeadersReplaceWholeOuterListsWithoutChangingEitherSource(final String innerName) throws Exception {
        final Email inner = mail.emailBuilder().startingBlank()
                .withHeaders(Map.of(innerName, List.of("inner-one", "inner-two"), "X-Inner", List.of("inner-only"))).buildEmail();
        final InternalEmailPopulatingBuilder outer = outerWithSignedContent(inner);
        final Map<String, Collection<String>> outerBefore = new LinkedHashMap<>(outer.getHeaders());
        final Email merged = outer.buildEmail();

        assertThat(merged.getHeaders()).hasSize(3).containsEntry("X-Route", List.of("inner-one", "inner-two"))
                .containsEntry("X-Outer", List.of("outer-only")).containsEntry("X-Inner", List.of("inner-only"));
        assertThat(outer.getHeaders()).isEqualTo(outerBefore);
        assertThat(inner.getHeaders()).hasSize(2).containsEntry(innerName, List.of("inner-one", "inner-two"));
        assertThat(mail.emailBuilder().copying(merged).buildEmail().getHeaders()).isEqualTo(merged.getHeaders());
        assertThat(roundTrip(merged).getHeaders()).isEqualTo(merged.getHeaders());
        outer.withHeader("X-Route", "later");
        assertThat(merged.getHeaders().get("X-Route")).containsExactly("inner-one", "inner-two");
    }

    @Test
    void disablingSignedContentMergeKeepsOuterHeaders() {
        final Email inner = mail.emailBuilder().startingBlank().withHeader("x-route", "inner").buildEmail();
        final Email outer = outerWithSignedContent(inner).notMergingSingleSMIMESignedAttachment().buildEmail();
        assertThat(outer.getHeaders()).hasSize(2).containsEntry("X-Route", List.of("outer-one", "outer-two"));
        final Email merged = mail.emailBuilder().copying(outer).clearSMIMESignedAttachmentMergingBehavior().buildEmail();
        assertThat(merged.getHeaders()).hasSize(2).containsEntry("X-Route", List.of("inner"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void templateGovernanceStillAppliesAfterTheSignedContentMerge(final boolean ignoreOverrides) throws Exception {
        final Email inner = mail.emailBuilder().startingBlank().withHeader("x-route", "inner").buildEmail();
        final Email merged = outerWithSignedContent(inner)
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringOverrides(ignoreOverrides).build()).buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(mail.emailBuilder().startingBlank().withHeader("X-ROUTE", "default").buildEmail())
                .withEmailOverrides(mail.emailBuilder().startingBlank().withHeaders(Map.of("x-ROUTE", List.of("override-one", "override-two"))).buildEmail())
                .buildMailer()) {
            final Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(merged);
            assertThat(completed.getHeaders().get("X-ROUTE"))
                    .containsExactlyElementsOf(ignoreOverrides ? List.of("inner") : List.of("override-one", "override-two"));
            assertThat(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(completed).getHeaders()).isEqualTo(completed.getHeaders());
            assertThat(merged.getHeaders().get("X-Route")).containsExactly("inner");
        }
    }

    enum LegacySource { SUBMITTED, DEFAULT, OVERRIDE }

    @ParameterizedTest
    @EnumSource(LegacySource.class)
    void legacyCaseCollisionsRetainLastMatchingEntryPrecedence(final LegacySource source) throws Exception {
        final Email legacy = legacyEmailWithCaseCollisions();
        final Email empty = mail.emailBuilder().startingBlank().buildEmail();
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(source == LegacySource.DEFAULT ? legacy : empty)
                .withEmailOverrides(source == LegacySource.OVERRIDE ? legacy : empty).buildMailer()) {
            final Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(
                    source == LegacySource.SUBMITTED ? legacy : empty);
            assertThat(completed.getHeaders()).hasSize(1).containsEntry("X-Route", List.of("last-one", "last-two"));
            assertThat(legacy.getHeaders()).hasSize(2).containsEntry("X-Route", List.of("first"))
                    .containsEntry("x-route", List.of("last-one", "last-two"));
        }
    }

    private InternalEmailPopulatingBuilder outerWithSignedContent(final Email inner) {
        final InternalEmailPopulatingBuilder outer = (InternalEmailPopulatingBuilder) mail.emailBuilder().startingBlank();
        outer.withHeaders(Map.of("X-Route", List.of("outer-one", "outer-two"), "X-Outer", List.of("outer-only")));
        outer.withSmimeSignedEmail(inner);
        return outer;
    }

    private Email legacyEmailWithCaseCollisions() throws Exception {
        final Email email = mail.emailBuilder().startingBlank().buildEmail();
        final Map<String, Collection<String>> headers = new LinkedHashMap<>();
        headers.put("X-Route", List.of("first"));
        headers.put("x-route", List.of("last-one", "last-two"));
        // Released snapshots could contain both spellings after flattening an outer/inner S/MIME pair.
        final Field field = Email.class.getDeclaredField("headers");
        field.setAccessible(true);
        field.set(email, headers);
        return roundTrip(email);
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(email);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }
}
