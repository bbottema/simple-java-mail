package org.simplejavamail.examples.patterns;

import org.simplejavamail.api.mailer.config.ConnectionConfig;
import java.time.Duration;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;

/** Starts one local application. Each example connects its own job or workflow framework to the shared mail boundary. */
@SpringBootApplication
@EnableScheduling
public class PatternApplication {
    public static void main(String[] args) {
        SpringApplication.run(PatternApplication.class, args);
    }

    @Bean
    SimpleJavaMail mail() {
        return SimpleJavaMail.fromDefaults();
    }

    @Bean(destroyMethod = "close")
    Mailer mailer(SimpleJavaMail mail, JobStore jobs) {
        // Plain SMTP is deliberate only for this isolated synthetic fixture. Production TLS/credentials belong to deployment configuration.
        return mail.mailerBuilder()
                .withSMTPServer("smtp", 2525)
                .withTransportStrategy(TransportStrategy.SMTP)
                .withConnectionConfig(ConnectionConfig.builder().withSessionTimeout(Duration.ofMillis(5000)).build())
                // The terminal outcome also retains acceptance when a later connection cleanup fails.
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(jobs::recordMailOutcome).build())
                .buildMailer();
    }
}
