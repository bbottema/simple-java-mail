package testutil;

import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.util.function.Consumer;

/** Keeps fixture builder choices available when a test customizes a supplied configuration group. */
public final class MailerTestConfig {
    private MailerTestConfig() { }

    /** Evaluates a fixture builder once so updates can copy its existing group choices. */
    public static <T extends MailerGenericBuilder<?>> T updateMailer(final T builder, final Consumer<T> update) {
        update.accept(builder);
        return builder;
    }
}
