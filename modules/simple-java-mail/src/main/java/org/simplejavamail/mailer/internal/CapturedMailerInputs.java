package org.simplejavamail.mailer.internal;

import com.sanctionco.jmail.EmailValidator;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.mailer.internal.ratelimit.FactorySendingLimits;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** One build's complete value snapshot. Borrowed identities are retained; no builder or owned runtime resource is captured. */
@Getter(AccessLevel.PACKAGE)
@Builder(access = AccessLevel.PACKAGE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
final class CapturedMailerInputs {
    private final CapturedConnectionInputs connection;
    private final MailerOperationalSettings operational;
    private final MailerConfigurationRequirements requirements;
    private final FactoryMailerRuntime.Definition definition;
    private final FactorySendingLimits sendingLimits;
    @Nullable private final EmailValidator emailValidator;
    @Nullable private final Email emailDefaults;
    @Nullable private final Email emailOverrides;
    @Nullable private final Integer maximumEmailSize;
    @Nullable private final MailSendHandlers handlers;
    @Nullable private final Function<UUID, MailSendHandlers> handlersFactory;
    private final Supplier<UUID> idGenerator;
    private final boolean applyDebugEnabled;
    private final boolean applyDebugOutput;
    private final boolean regularMailer;
    private final boolean opportunisticTLS;
    @Nullable private final TransportStrategy ownedTransportStrategy;
    @Nullable private final String password;
    private final boolean ignoredSmtpSettings;
    @Nullable private final FactoryMailerRuntime.Definition permittedFailoverDefinition;
    @Nullable private final CapturedMailerInputs namedChild;
    @Nullable private final MailerImpl borrowedFailover;
}
