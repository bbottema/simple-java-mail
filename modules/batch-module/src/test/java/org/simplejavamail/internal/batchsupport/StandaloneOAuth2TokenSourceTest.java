package org.simplejavamail.internal.batchsupport;

import jakarta.mail.Session;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.batch.BatchTransportExecutor.OAUTH2_TOKEN_PROPERTY;
import static org.simplejavamail.batch.BatchTransportExecutor.OAUTH2_TOKEN_PROVIDER_PROPERTY;

class StandaloneOAuth2TokenSourceTest {
    @Test
    void inheritedFixedTokenBehindAnOpaqueDirectEntryKeepsItsStorageAndVisibility() {
        final Properties defaults = new Properties();
        defaults.setProperty(OAUTH2_TOKEN_PROPERTY, "inherited-fixed");
        final Properties properties = new Properties(defaults);
        final Object opaque = new Object();
        properties.put(OAUTH2_TOKEN_PROPERTY, opaque);
        final Supplier<String> selected = StandaloneOAuth2TokenSource.capture(Session.getInstance(properties));

        assertThat(selected.get()).isEqualTo("inherited-fixed");
        assertThat(properties.get(OAUTH2_TOKEN_PROPERTY)).isSameAs(opaque);
        assertThat(defaults.getProperty(OAUTH2_TOKEN_PROPERTY)).isEqualTo("inherited-fixed");
    }

    @Test
    void callbackObjectsInDefaultsRemainUnavailableAndUninvoked() {
        final AtomicInteger calls = new AtomicInteger();
        final Properties defaults = new Properties();
        defaults.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, (Supplier<String>) () -> { calls.incrementAndGet(); return "unused"; });
        final Properties properties = new Properties(defaults);

        assertThat(StandaloneOAuth2TokenSource.capture(Session.getInstance(properties))).isNull();
        assertThat(calls).hasValue(0);
        assertThat(properties).isEmpty();
    }

    @Test
    void unsupportedCallbackShapeIsStillDeferredToCredentialConsumptionWithoutRenderingIt() {
        final AtomicInteger renderCalls = new AtomicInteger();
        final Object unsupported = new Object() {
            @Override public String toString() { renderCalls.incrementAndGet(); return "private-application-value"; }
        };
        final Properties properties = new Properties();
        properties.put(OAUTH2_TOKEN_PROVIDER_PROPERTY, unsupported);

        final Supplier<String> selected = StandaloneOAuth2TokenSource.capture(Session.getInstance(properties));

        assertThat(renderCalls).hasValue(0);
        assertThatThrownBy(selected::get).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a Supplier")
                .hasMessageNotContaining("private-application-value");
        assertThat(renderCalls).hasValue(0);
    }
}
