package org.simplejavamail.springsupport;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables mail-send telemetry through the existing Simple Java Mail Spring integration when no activation property is configured.
 * <p>
 * Use this annotation on an application configuration class with either Spring Boot's SJM integration or
 * {@code @Import(SimpleJavaMailSpringSupport.class)}. It does not import the base integration or replace application-provided beans.
 * Explicit {@code simplejavamail.telemetry.enabled} properties override this annotation, including {@code false}; locked configuration
 * remains authoritative. The resulting context-local snapshot supplies the activation choice to every Mailer created from its factory.
 * <p>
 * A {@link org.simplejavamail.api.mailer.MailSendTelemetry} bean independently selects the integration used by the default Mailer.
 * Without that bean, enabled Mailers use the application's global OpenTelemetry instance. The optional telemetry module must be available
 * at runtime, and applications retain responsibility for initializing and closing their SDK.
 *
 * @see SimpleJavaMailSpringSupport
 * @see org.simplejavamail.api.mailer.config.MailSendTelemetryConfig.Builder#withEnabled(boolean)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(SjmTelemetryActivation.class)
public @interface EnableSjmTelemetry {
}
