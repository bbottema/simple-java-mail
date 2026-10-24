package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConflictPolicy;
import org.simplejavamail.api.mailer.config.SocksProxyConfig;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The injected factory and default Mailer use the same advanced connection policy as ordinary Java construction. */
class SimpleJavaMailSpringConnectionConflictPolicyTest {
    @Test
    void absentSpringPolicyRetainsTheStrictMailerDefault() {
        try (AnnotationConfigApplicationContext context = context(Map.of())) {
            context.refresh();
            assertThat(context.getBean(SimpleJavaMailConfig.class).get(ConfigKeys.CONNECTION_CONFLICT_POLICY)).isNull();
            assertThat(context.getBean(Mailer.class).getOperationalConfig().getConnectionConflictPolicy())
                    .isSameAs(ConnectionConflictPolicy.REJECT_CONFLICTS);
            final MailerRegularBuilder<?> builder = conflictingProxyBuilder(context.getBean(SimpleJavaMail.class));
            assertThat(builder.getConnectionConflictPolicy()).isNull();
            assertThatThrownBy(builder::buildMailer).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("mail.smtp.socks.host");
        }
    }

    @Test
    void springOverridePolicyFlowsToBothDefaultMailerAndFreshBuilders() throws Exception {
        try (AnnotationConfigApplicationContext context = context(Map.of(
                "simplejavamail.connection.conflictpolicy", "${mail.conflict.policy}",
                "mail.conflict.policy", "ALLOW_EXPLICIT_OVERRIDES"))) {
            context.refresh();
            assertThat(context.getBean(Mailer.class).getOperationalConfig().getConnectionConflictPolicy())
                    .isSameAs(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
            final MailerRegularBuilder<?> builder = conflictingProxyBuilder(context.getBean(SimpleJavaMail.class));
            // The shared classpath fixture supplies credentials; this case exercises anonymous proxy routing.
            builder.withProxyConfig(builder.getProxyConfig().toBuilder().clearUsername().clearPassword().build());
            assertThat(builder.getConnectionConflictPolicy()).isNull();
            try (Mailer mailer = builder.buildMailer()) {
                assertThat(mailer.getOperationalConfig().getConnectionConflictPolicy())
                        .isSameAs(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
                assertThat(mailer.getProxyConfig().getRemoteProxyHost()).isEqualTo("typed-proxy.example.test");
                assertThat(mailer.getProxyConfig().requiresAuthentication()).isFalse();
                assertThat(mailer.getSession().getProperty("mail.smtp.socks.host")).isEqualTo("typed-proxy.example.test");
            }
        }
    }

    @Test
    void lockedSpringPolicyCannotBeRelaxedByAFreshJavaBuilder() {
        try (AnnotationConfigApplicationContext context = context(Map.of(
                "simplejavamail.locked.connection.conflictpolicy", "REJECT_CONFLICTS"))) {
            context.refresh();
            final MailerRegularBuilder<?> builder = context.getBean(SimpleJavaMail.class).mailerBuilder()
                    .withConnectionConflictPolicy(ConnectionConflictPolicy.ALLOW_EXPLICIT_OVERRIDES);
            assertThatThrownBy(builder::buildMailer).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("simplejavamail.locked.connection.conflictpolicy");
        }
    }

    private static MailerRegularBuilder<?> conflictingProxyBuilder(final SimpleJavaMail mail) {
        return mail.mailerBuilder()
                .withProperty("mail.smtp.socks.host", "raw-proxy.example.test")
                .withProxyConfig(SocksProxyConfig.builder().withHost("typed-proxy.example.test").withPort(1080).build());
    }

    private static AnnotationConfigApplicationContext context(final Map<String, ?> properties) {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("application", Map.of(
                "simplejavamail.smtp.host", "localhost")));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("deployment", new LinkedHashMap<>(properties)));
        context.register(SimpleJavaMailSpringSupport.class);
        return context;
    }
}
