package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.FailoverPolicy;
import org.simplejavamail.config.ConfigLoader;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FailoverBuilderInspectionTest {

    @Test
    void inspectionRetainsTheSuppliedPairAndReplacementAndClearAreIndependentOfBuiltMailers() throws Exception {
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final FailoverPolicy firstPolicy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofSeconds(5)).build();
        final FailoverPolicy secondPolicy = FailoverPolicy.builder().withPrimaryRecheckInterval(Duration.ofSeconds(10)).build();
        final MailerRegularBuilder<?> builder = factory.mailerBuilder().withTransportModeLoggingOnly(true);
        assertThat(builder.getFailoverMailer()).isNull();
        assertThat(builder.getFailoverPolicy()).isNull();

        try (Mailer first = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
             Mailer second = factory.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            builder.withFailoverMailer(first, firstPolicy);
            assertThat(builder.getFailoverMailer()).isSameAs(first);
            assertThat(builder.getFailoverPolicy()).isSameAs(firstPolicy);
            try (Mailer built = builder.buildMailer()) {
                builder.withFailoverMailer(second, secondPolicy);
                assertThat(builder.getFailoverMailer()).isSameAs(second);
                assertThat(builder.getFailoverPolicy()).isSameAs(secondPolicy);
                assertThat(built.getOperationalConfig().getFailoverPolicy()).isSameAs(firstPolicy);
                assertThat(builder.clearFailoverMailer()).isSameAs(builder);
                assertThat(builder.getFailoverMailer()).isNull();
                assertThat(builder.getFailoverPolicy()).isNull();
                assertThat(first.getOperationalConfig().getExecutorService().isShutdown()).isFalse();
                assertThat(second.getOperationalConfig().getExecutorService().isShutdown()).isFalse();
            }
        }
    }

    @Test
    void inspectingNamedConfigurationDoesNotConstructOrValidateAnUnfinishedDestination() throws Exception {
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.failover.mailer", "secondary",
                "simplejavamail.failover.primary.recheckinterval", "PT7S",
                "simplejavamail.mailers.secondary.smtp.port", "587")).load());
        final MailerRegularBuilder<?> builder = factory.mailerBuilder();
        assertThat(builder.getFailoverMailer()).isNull();
        assertThat(builder.getFailoverPolicy().getPrimaryRecheckInterval()).isEqualTo(Duration.ofSeconds(7));
        assertThat(builder.getHost()).isNull();
    }
}
