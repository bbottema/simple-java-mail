package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.ProxyConfig;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** One build's detached declarations and provider-visible Session baseline. Borrowed resources retain identity. */
@Getter(AccessLevel.PACKAGE)
final class CapturedConnectionInputs {
    private final SimpleJavaMailConfig config;
    @Nullable private final Session targetSession;
    @Nullable private final Session suppliedSession;
    @Nullable private final TransportStrategy strategy;
    private final ConnectionConflictPolicy policy;
    @Getter(AccessLevel.NONE) private final Properties additionalProperties;
    private final Map<String, Object> rawChoices;
    private final Map<Property, Object> typedChoices;
    private final Map<String, Object> defaults;
    private final Map<String, Object> physicalChoices;
    private final Map<String, Object> requiredOverrides;
    private final ProxyConfig proxyFallback;
    private final boolean proxyCustomized;
    private final boolean smtpActive;
    @Nullable private final CustomMailer customMailer;
    @Nullable private final OAuth2AccessTokenProvider tokenProvider;

    @Builder(access = AccessLevel.PACKAGE)
    private CapturedConnectionInputs(final SimpleJavaMailConfig config, @Nullable final Session targetSession,
            @Nullable final TransportStrategy strategy, final ConnectionConflictPolicy policy, final Properties additionalProperties,
            final Map<String, Object> rawChoices, final Map<Property, Object> typedChoices, final Map<String, Object> defaults,
            final Map<String, Object> physicalChoices, final Map<String, Object> requiredOverrides, final ProxyConfig proxyFallback,
            final boolean proxyCustomized, @Nullable final CustomMailer customMailer, @Nullable final OAuth2AccessTokenProvider tokenProvider) {
        this.config = config;
        this.targetSession = targetSession;
        this.suppliedSession = targetSession == null ? null : snapshot(targetSession);
        this.strategy = strategy;
        this.policy = policy;
        this.additionalProperties = SessionPropertyObservation.snapshot(additionalProperties);
        this.rawChoices = immutable(rawChoices);
        this.typedChoices = immutable(typedChoices);
        this.defaults = immutable(defaults);
        this.physicalChoices = immutable(physicalChoices);
        this.requiredOverrides = immutable(requiredOverrides);
        this.proxyFallback = proxyFallback;
        this.proxyCustomized = proxyCustomized;
        this.customMailer = customMailer;
        this.smtpActive = customMailer == null;
        this.tokenProvider = tokenProvider;
    }

    Properties copyAdditionalProperties() { return SessionPropertyObservation.snapshot(additionalProperties); }

    static <K, V> Map<K, V> immutable(final Map<K, V> source) {
        // Null values are deliberate removals, so Map.copyOf is not suitable here.
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Session snapshot(final Session source) {
        final Session snapshot = Session.getInstance(SessionPropertyObservation.snapshot(source.getProperties()));
        snapshot.setDebug(source.getDebug());
        snapshot.setDebugOut(source.getDebugOut());
        return snapshot;
    }
}
