package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredFailoverTest {

    @Test
    void propertyDefaultsAgreeWithTheJavaPolicyBuilder() {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.failover.mailer", "secondary");
        assertThat(ConfiguredFailover.policy(ConfigLoader.builder().withProperties(properties).load()))
                .isEqualTo(FailoverPolicy.builder().build());
    }

    @Test
    void propertyMaximumFollowsTheChosenInitialIntervalWithoutBackoff() {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.failover.mailer", "secondary");
        properties.setProperty("simplejavamail.failover.primary.recheckinterval", "PT7S");
        final FailoverPolicy policy = ConfiguredFailover.policy(ConfigLoader.builder().withProperties(properties).load());
        assertThat(policy.getPrimaryRecheckInterval()).isEqualTo(Duration.ofSeconds(7));
        assertThat(policy.getPrimaryMaximumRecheckInterval()).isEqualTo(Duration.ofSeconds(7));
        assertThat(policy).isEqualTo(FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofSeconds(7)).build());
    }
}
