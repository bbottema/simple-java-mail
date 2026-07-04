package org.simplejavamail.springsupport;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.config.ConfigLoader;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.config.ConfigLoader.Property.EXTRA_PROPERTIES;

public abstract class SimpleJavaMailSpringSupportTest {

    protected void performConfigAssertions() {
        assertThat(getProperty(ConfigLoader.Property.DEFAULT_CC_NAME)).isEqualTo("CC Default"); // from normal simplejavamail.properties
        assertThat(getProperty(ConfigLoader.Property.DEFAULT_BCC_NAME)).isEqualTo("BCC Spring"); // from Spring application.properties
        assertThat(getProperty(ConfigLoader.Property.DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING)).isEqualTo("7bit"); // from normal simplejavamail.properties
        assertThat(getProperty(ConfigLoader.Property.DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING)).isEqualTo("binary"); // from Spring application.properties
        assertThat(getProperty(ConfigLoader.Property.DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING)).isEqualTo("base64"); // from normal simplejavamail.properties
        assertThat(getProperty(ConfigLoader.Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY)).isEqualTo("FAILURE,DELAY"); // from normal simplejavamail.properties
        assertThat(getProperty(ConfigLoader.Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION)).isEqualTo("HEADERS_ONLY"); // from Spring application.properties
        assertThat(getProperty(ConfigLoader.Property.JAVAXMAIL_DEBUG_OUTPUT)).isEqualTo("STDERR"); // from Spring application.properties
        assertThat(getProperty(ConfigLoader.Property.DKIM_SELECTOR)).isEqualTo(null); // not set in any properties

        Map<String, String> loaded = ConfigLoader.getProperty(EXTRA_PROPERTIES);
        Map<String, String> expected = new HashMap<>();
        expected.put("one", "1"); // from normal simplejavamail.properties
        expected.put("two", "two"); // overridden from Spring application.properties
        expected.put("three", "three"); // from Spring application.properties only
        assertThat(loaded).containsExactlyInAnyOrderEntriesOf(expected);
    }

    private static @Nullable String getProperty(ConfigLoader.Property property) {
        return ConfigLoader.valueOrPropertyAsString(null, property, null);
    }
}
