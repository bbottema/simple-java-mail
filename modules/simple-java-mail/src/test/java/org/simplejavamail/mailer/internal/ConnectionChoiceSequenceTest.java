package org.simplejavamail.mailer.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.internal.configresolution.ConfigurationContractModel;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Exhausts bounded editing histories using the real public group APIs, before importing or resolving any defaults. */
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

    @ParameterizedTest
    @EnumSource(Member.class)
    void everyEditingSequenceThroughFourOperationsPreservesFinalChoiceStates(final Member member) {
        final var config = ConfigLoader.builder().withMap(Map.of("simplejavamail.extraproperties." + member.raw, member.value(1).toString())).load();
        // Actions: typed A, typed B, reset/clear, restore, raw A, raw B, raw removal, empty-group replacement.
        for (int length = 0; length <= 4; length++) {
            for (int code = 0; code < (int) Math.pow(8, length); code++) {
                final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(config);
                int typed = 0;
                int raw = -1;
                for (int action : ConfigurationContractModel.digits(code, 8, length)) {
                    switch (action) {
                        case 0: member.set(builder, 1); typed = 1; break;
                        case 1: member.set(builder, 2); typed = 2; break;
                        case 2: member.resetOrClear(builder); typed = 3; break;
                        case 3: member.restore(builder); typed = 0; break;
                        case 4: builder.withProperty(member.raw, member.value(1)); raw = 1; break;
                        case 5: builder.withProperty(member.raw, member.value(2)); raw = 2; break;
                        case 6: builder.withProperty(member.raw, null); raw = 0; break;
                        case 7:
                            if (member == Member.IDENTITY) { builder.withTlsConfig(TlsConfig.builder().build()); }
                            else { builder.withConnectionConfig(ConnectionConfig.builder().build()); }
                            typed = 0;
                            break;
                        default: throw new AssertionError(action);
                    }
                }
                final CapturedConnectionInputs inputs = builder.captureConnectionInputs(null);
                assertThat(inputs.getTypedChoices().containsKey(member.property)).as("%s length=%s case=%s", member, length, code).isEqualTo(typed != 0);
                final Object expectedTyped = typed == 0 || typed == 3 && !member.timeout() && member != Member.IDENTITY ? null : member.value(typed);
                assertThat(inputs.getTypedChoices().get(member.property)).isEqualTo(expectedTyped);
                assertThat(inputs.getRawChoices().containsKey(member.raw)).isEqualTo(raw >= 0);
                assertThat(inputs.getRawChoices().get(member.raw)).isEqualTo(raw <= 0 ? null : member.value(raw).toString());
                assertThat(inputs.copyAdditionalProperties().getProperty(member.raw)).isEqualTo(raw == 0 ? null : member.value(raw < 0 ? 1 : raw).toString());
            }
        }
    }
}
