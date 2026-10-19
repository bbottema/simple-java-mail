package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class NoBatchWorkerConfigurationTest {
    @Test
    void keepAliveChoicesAreIndependentFromTheWorkerCountWithoutPooling() throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer fromProperties = factory(Map.of("simplejavamail.defaults.poolsize.keepalivetime", "7"))
                    .mailerBuilder().withSMTPServer("localhost", 25).buildMailer();
                 Mailer explicit = factory(Map.of()).mailerBuilder().withSMTPServer("localhost", 25)
                         .withThreadPoolKeepAliveTime(Duration.ofMillis(11)).buildMailer()) {
                final ThreadPoolExecutor inheritedExecutor = (ThreadPoolExecutor) fromProperties.getOperationalConfig().getExecutorService();
                final ThreadPoolExecutor explicitExecutor = (ThreadPoolExecutor) explicit.getOperationalConfig().getExecutorService();
                assertThat(inheritedExecutor.getMaximumPoolSize()).isOne();
                assertThat(inheritedExecutor.getKeepAliveTime(TimeUnit.MILLISECONDS)).isEqualTo(7);
                assertThat(inheritedExecutor.allowsCoreThreadTimeOut()).isTrue();
                assertThat(explicitExecutor.getMaximumPoolSize()).isOne();
                assertThat(explicitExecutor.getKeepAliveTime(TimeUnit.MILLISECONDS)).isEqualTo(11);
            }
        }
    }

    @Test
    void explicitWorkerCountIsHonoredWithoutPooling() throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("localhost", 25)
                    .withThreadPoolSize(3).buildMailer()) {
                final ThreadPoolExecutor executor = (ThreadPoolExecutor) mailer.getOperationalConfig().getExecutorService();
                assertThat(executor.getMaximumPoolSize()).isEqualTo(3);
                assertThat(mailer.getAsyncQueueSnapshot().orElseThrow().getWorkerLimit()).isEqualTo(3);
            }
        }
    }

    @Test
    void capturedWorkerCountIsHonoredWithoutPooling() throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer mailer = factory(Map.of("simplejavamail.defaults.poolsize", "2")).mailerBuilder()
                    .withSMTPServer("localhost", 25).buildMailer()) {
                assertThat(mailer.getAsyncQueueSnapshot().orElseThrow().getWorkerLimit()).isEqualTo(2);
            }
        }
    }

    @Test
    void absentWorkerChoiceKeepsTheSingleWorkerFallbackWithoutPooling() throws Exception {
        try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
            modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
            try (Mailer mailer = factory(Map.of()).mailerBuilder().withSMTPServer("localhost", 25).buildMailer()) {
                assertThat(mailer.getAsyncQueueSnapshot().orElseThrow().getWorkerLimit()).isOne();
            }
        }
    }

    private static SimpleJavaMail factory(final Map<String, String> properties) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(properties).load());
    }
}
