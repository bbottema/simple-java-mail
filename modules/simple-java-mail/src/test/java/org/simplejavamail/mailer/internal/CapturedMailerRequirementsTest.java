package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.config.ConfigLoader;

import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CapturedMailerRequirementsTest {
    @Test
    void changingABuilderCannotRepairItsPreviouslyCapturedConflictingChoice() {
        final var config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.defaults.poolsize", "2")).load();
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(config).withThreadPoolSize(3);
        final MailerConfigurationRequirements captured = builder.captureConfigurationRequirements();
        builder.withThreadPoolSize(2);

        assertThatThrownBy(captured::validate).hasMessageContaining("locked.defaults.poolsize");
        assertThatCode(() -> builder.captureConfigurationRequirements().validate()).doesNotThrowAnyException();
    }

    @Test
    void changingABuilderCannotInvalidatePreviouslyCapturedServerChoices() {
        final var config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.smtp.host", "relay.example.org")).load();
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(config);
        final MailerConfigurationRequirements captured = builder.captureConfigurationRequirements();
        builder.withSMTPServerHost("other.example.org");

        assertThatCode(captured::validate).doesNotThrowAnyException();
        assertThatThrownBy(() -> builder.captureConfigurationRequirements().validate()).hasMessageContaining("locked.smtp.host");
    }

    @Test
    void clearingARawPropertyAfterCaptureDoesNotEraseItsLockConflict() {
        final var config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.extraproperties.application.setting", "required")).load();
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(config).withProperty("application.setting", "different");
        final MailerConfigurationRequirements captured = builder.captureConfigurationRequirements();
        builder.withProperty("application.setting", "required");

        assertThatThrownBy(captured::validate).hasMessageContaining("locked.extraproperties.application.setting");
        assertThatCode(() -> builder.captureConfigurationRequirements().validate()).doesNotThrowAnyException();
    }

    @Test
    void resettingAnExecutorAfterCaptureDoesNotChangeItsBorrowedOwnershipConstraint() {
        final var config = ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.defaults.poolsize", "1")).load();
        final MailerRegularBuilderImpl builder = new MailerRegularBuilderImpl(config)
                .withExecutorService(mock(ExecutorService.class));
        final MailerConfigurationRequirements captured = builder.captureConfigurationRequirements();
        builder.resetExecutorService();

        assertThatThrownBy(captured::validate).hasMessageContaining("withExecutorService(...)")
                .hasMessageContaining("factory locks worker or queue settings");
        assertThatCode(() -> builder.captureConfigurationRequirements().validate()).doesNotThrowAnyException();
    }
}
