package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;

import java.time.Duration;
import java.util.Map;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Named editing transitions through the real group APIs; equivalent field adapters share a short mapping sweep. */
class ConnectionChoiceSequenceTest {
    enum Member {
        CONNECT(Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, "mail.smtp.connectiontimeout"),
        READ(Property.DEFAULT_READ_TIMEOUT_MILLIS, "mail.smtp.timeout"),
        WRITE(Property.DEFAULT_WRITE_TIMEOUT_MILLIS, "mail.smtp.writetimeout"),
        LOCAL_ADDRESS(Property.SMTP_LOCAL_ADDRESS, "mail.smtp.localaddress"),
        LOCAL_PORT(Property.SMTP_LOCAL_PORT, "mail.smtp.localport"),
        EHLO(Property.SMTP_CLIENT_HOSTNAME, "mail.smtp.localhost"),
        IDENTITY(Property.DEFAULT_VERIFY_SERVER_IDENTITY, "mail.smtp.ssl.checkserveridentity");
        final Property property;
        final String raw;
        Member(final Property property, final String raw) { this.property = property; this.raw = raw; }
        Object value(final int state) {
            if (timeout() || this == LOCAL_PORT) { return state == 1 ? 123 : state == 2 ? 456 : 60000; }
            if (this == IDENTITY) { return state != 2; }
            if (this == LOCAL_ADDRESS) { return state == 1 ? "192.0.2.1" : "192.0.2.2"; }
            return state == 1 ? "first.example.org" : "second.example.org";
        }
        void set(final MailerRegularBuilderImpl builder, final int state) {
            if (this == IDENTITY) {
                builder.withTlsConfig(TlsConfig.builder().verifyingServerIdentity((Boolean) value(state)).build());
            } else {
                final ConnectionConfig.Builder choices = ConnectionConfig.builder();
                switch (this) {
                    case CONNECT: choices.withConnectTimeout(Duration.ofMillis((Integer) value(state))); break;
                    case READ: choices.withReadTimeout(Duration.ofMillis((Integer) value(state))); break;
                    case WRITE: choices.withWriteTimeout(Duration.ofMillis((Integer) value(state))); break;
                    case LOCAL_ADDRESS: choices.withLocalBindAddress((String) value(state)); break;
                    case LOCAL_PORT: choices.withLocalBindPort((Integer) value(state)); break;
                    case EHLO: choices.withSmtpClientHostname((String) value(state)); break;
                    default: throw new AssertionError(this);
                }
                builder.withConnectionConfig(choices.build());
            }
        }
        void restore(final MailerRegularBuilderImpl builder) {
            if (this == IDENTITY) { builder.useConfiguredTlsConfig(); } else { builder.useConfiguredConnectionConfig(); }
        }
        void resetOrClear(final MailerRegularBuilderImpl builder) {
            if (this == IDENTITY) { builder.withTlsConfig(TlsConfig.builder().resetVerifyingServerIdentity().build()); }
            else {
                final ConnectionConfig.Builder choices = ConnectionConfig.builder();
                switch (this) {
                    case CONNECT: choices.resetConnectTimeout(); break;
                    case READ: choices.resetReadTimeout(); break;
                    case WRITE: choices.resetWriteTimeout(); break;
                    case LOCAL_ADDRESS: choices.clearLocalBindAddress(); break;
                    case LOCAL_PORT: choices.clearLocalBindPort(); break;
                    case EHLO: choices.clearSmtpClientHostname(); break;
                    default: throw new AssertionError(this);
                }
                builder.withConnectionConfig(choices.build());
            }
        }
        boolean timeout() { return this == READ || this == CONNECT || this == WRITE; }
    }

    enum Edit { TYPED_A, TYPED_B, RESET_OR_CLEAR, RESTORE, RAW_A, RAW_B, RAW_REMOVE, REPLACE_GROUP }
    enum History {
        EMPTY(), SET(Edit.TYPED_A), REPLACE(Edit.TYPED_A, Edit.TYPED_B), RESET(Edit.TYPED_A, Edit.RESET_OR_CLEAR),
        RESTORE(Edit.TYPED_B, Edit.RESTORE), RAW(Edit.RAW_A), RAW_REPLACE(Edit.RAW_A, Edit.RAW_B),
        RAW_REMOVE(Edit.RAW_A, Edit.RAW_REMOVE), GROUP_REPLACE(Edit.TYPED_A, Edit.REPLACE_GROUP),
        TYPED_THEN_RAW(Edit.TYPED_A, Edit.RAW_B), RAW_THEN_TYPED(Edit.RAW_B, Edit.TYPED_A),
        REPEATED_THEN_RESTORE(Edit.TYPED_A, Edit.TYPED_B, Edit.RESTORE),
        RESET_THEN_RESTORE(Edit.TYPED_A, Edit.RAW_B, Edit.RESET_OR_CLEAR, Edit.RESTORE),
        REMOVE_THEN_RESTORE(Edit.RAW_A, Edit.TYPED_A, Edit.RAW_REMOVE, Edit.RESTORE),
        GROUP_AND_RAW_REPLACEMENT(Edit.TYPED_B, Edit.RAW_B, Edit.REPLACE_GROUP, Edit.RAW_A);
        final Edit[] edits;
        History(final Edit... edits) { this.edits = edits; }
    }

    @Test
    void historyCatalogueCoversEveryEditingCategoryAndLengthsZeroThroughFour() {
        final Set<Edit> edits = EnumSet.noneOf(Edit.class);
        final Set<Integer> lengths = new LinkedHashSet<>();
        for (History history : History.values()) {
            lengths.add(history.edits.length);
            Collections.addAll(edits, history.edits);
        }
        assertThat(edits).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Edit.class));
        assertThat(lengths).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
    }

    @ParameterizedTest
    @EnumSource(Member.class)
    void representativeHistoriesPreserveFinalChoiceStatesForEveryAdapter(final Member member) {
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.extraproperties." + member.raw, member.value(1).toString())).load();
        for (History history : History.values()) {
            final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(config);
            int typed = 0;
            int raw = -1;
            for (Edit edit : history.edits) {
                switch (edit) {
                    case TYPED_A: member.set(builder, 1); typed = 1; break;
                    case TYPED_B: member.set(builder, 2); typed = 2; break;
                    case RESET_OR_CLEAR: member.resetOrClear(builder); typed = 3; break;
                    case RESTORE: member.restore(builder); typed = 0; break;
                    case RAW_A: builder.withProperty(member.raw, member.value(1)); raw = 1; break;
                    case RAW_B: builder.withProperty(member.raw, member.value(2)); raw = 2; break;
                    case RAW_REMOVE: builder.withProperty(member.raw, null); raw = 0; break;
                    case REPLACE_GROUP:
                        if (member == Member.IDENTITY) { builder.withTlsConfig(TlsConfig.builder().build()); }
                        else { builder.withConnectionConfig(ConnectionConfig.builder().build()); }
                        typed = 0;
                        break;
                    default: throw new AssertionError(edit);
                }
            }
            final CapturedConnectionInputs inputs = builder.captureConnectionInputs(null);
            assertThat(inputs.getTypedChoices().containsKey(member.property)).as("%s %s", member, history).isEqualTo(typed != 0);
            final Object expectedTyped = typed == 0 || typed == 3 && !member.timeout() && member != Member.IDENTITY ? null : member.value(typed);
            assertThat(inputs.getTypedChoices().get(member.property)).isEqualTo(expectedTyped);
            assertThat(inputs.getRawChoices().containsKey(member.raw)).isEqualTo(raw >= 0);
            assertThat(inputs.getRawChoices().get(member.raw)).isEqualTo(raw <= 0 ? null : member.value(raw).toString());
            assertThat(inputs.copyAdditionalProperties().getProperty(member.raw)).isEqualTo(raw == 0 ? null : member.value(raw < 0 ? 1 : raw).toString());
        }
    }
}
