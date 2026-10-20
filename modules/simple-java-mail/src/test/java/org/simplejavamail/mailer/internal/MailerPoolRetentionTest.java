package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class MailerPoolRetentionTest {
    @Test
    void closingFreshMailersDoesNotGrowTheSharedPoolRegistries() throws Exception {
        final SimpleJavaMail factory = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        try (Mailer longLived = newMailer(factory)) {
            final Object engine = field(ModuleLoader.loadBatchModule(), "batchTransportEngine");
            final Object pool = field(engine, "smtpConnectionPool");
            final int settings = mapSize(engine, "clusterSettings");
            final int clusters = mapSize(pool, "resourceClusters");
            final int configurations = mapSize(pool, "resourceClusterConfigs");
            for (int index = 0; index < 30; index++) {
                try (Mailer transientMailer = newMailer(factory)) {
                    assertThat(transientMailer).isNotSameAs(longLived);
                    assertThat(mapSize(engine, "clusterSettings")).isEqualTo(settings + 1);
                }
                assertThat(mapSize(engine, "clusterSettings")).isEqualTo(settings);
                assertThat(mapSize(pool, "resourceClusters")).isEqualTo(clusters);
                assertThat(mapSize(pool, "resourceClusterConfigs")).isEqualTo(configurations);
                assertThat(mapSize(engine, "connectionObserverByClaim")).isZero();
            }
        }
    }

    private static Mailer newMailer(final SimpleJavaMail factory) {
        // Register actual Mailers in the cached batch module without opening any network connection.
        return factory.mailerBuilder().withSMTPServer("localhost", 25).withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).build()).buildMailer();
    }

    private static int mapSize(final Object owner, final String name) throws Exception {
        return ((Map<?, ?>) field(owner, name)).size();
    }

    private static Object field(final Object owner, final String name) throws Exception {
        // Inspect the retention roots directly instead of requiring garbage collection to run during a regression test.
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException parentField) {
                // The SMTP pool inherits its cluster registries from the generic clustered layer.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
