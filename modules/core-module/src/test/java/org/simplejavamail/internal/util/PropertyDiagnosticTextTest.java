package org.simplejavamail.internal.util;

import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyDiagnosticTextTest {
    @Test
    void opaqueEntriesAreInertAndDoNotHideOrdinaryEscapingOrSecretRedaction() {
        final AtomicInteger renders = new AtomicInteger();
        final AtomicInteger hashes = new AtomicInteger();
        final Object opaque = new Object() {
            @Override public String toString() { renders.incrementAndGet(); throw new AssertionError("Opaque object rendered"); }
            @Override public int hashCode() { hashes.incrementAndGet(); return 7; }
        };
        final Properties defaults = new Properties();
        defaults.setProperty("mail.smtp.host", "relay.example.org");
        defaults.setProperty("mail.smtp.port", "25");
        defaults.setProperty("mail.smtp.auth", "true");
        final Properties properties = new Properties(defaults);
        properties.put(opaque, "secret-under-opaque-key");
        properties.put("vendor.runtime", opaque);
        properties.put("vendor.credentials", opaque);
        properties.setProperty("vendor.token", "hidden-token");
        properties.setProperty("vendor.line\r\nname", "ordinary\r\nvalue");
        properties.put("vendor.count", 12);
        properties.put("vendor.enabled", true);
        properties.put("mail.smtp.host", opaque);
        properties.put("mail.smtp.port", 2525);
        properties.put("mail.smtp.auth", false);
        final int originalHashes = hashes.get();
        final String rendered = PropertyDiagnosticText.render(properties);
        assertThat(rendered).contains("vendor.runtime", opaque.getClass().getName(), "<redacted>", "vendor.count=12", "vendor.enabled=true",
                "vendor.line\\r\\nname=ordinary\\r\\nvalue", "mail.smtp.port=2525", "mail.smtp.auth=false")
                .doesNotContain("secret-under-opaque-key", "hidden-token", "relay.example.org", "\r", "\n");
        assertThat(renders).hasValue(0);
        assertThat(hashes).hasValue(originalHashes);
    }
}
