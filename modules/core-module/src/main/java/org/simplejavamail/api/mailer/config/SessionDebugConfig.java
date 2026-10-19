package org.simplejavamail.api.mailer.config;

import lombok.Value;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.MailerGenericBuilder;

import java.io.PrintStream;

import static java.util.Objects.requireNonNull;

/**
 * Immutable supplied choices for Jakarta Mail debugging. Unset choices inherit the Mailer's captured configuration;
 * this value never reads configuration sources. A printer is borrowed and is never closed by Simple Java Mail.
 * Built Mailers expose a resolved value through {@link OperationalConfig#getDebugConfig()}.
 */
@Value
public class SessionDebugConfig {
    @Nullable Boolean enabled;
    @Nullable SessionDebugOutput output;
    @Nullable @ToString.Exclude PrintStream printer;

    private SessionDebugConfig(@Nullable final Boolean enabled, @Nullable final SessionDebugOutput output,
            @Nullable final PrintStream printer) {
        this.enabled = enabled;
        this.output = output;
        this.printer = printer;
    }

    /** @return A builder with no supplied choices, so each choice can inherit captured configuration. */
    @NotNull public static Builder builder() { return new Builder(); }

    /** @return An independent builder retaining these choices and the identity of any borrowed printer. */
    @NotNull public Builder toBuilder() { return new Builder(this); }

    /** @see Builder#withEnabled(boolean) */
    @Nullable public Boolean getEnabled() { return enabled; }

    /** @see Builder#withOutput(SessionDebugOutput) */
    @Nullable public SessionDebugOutput getOutput() { return output; }

    /** @see Builder#withPrinter(PrintStream) */
    @Nullable public PrintStream getPrinter() { return printer; }

    /** Collects debug choices without configuring a Session or touching a stream. */
    public static final class Builder {
        private Boolean enabled;
        private SessionDebugOutput output;
        private PrintStream printer;

        private Builder() { }
        private Builder(final SessionDebugConfig config) {
            enabled = config.enabled;
            output = config.output;
            printer = config.printer;
        }

        /**
         * Replaces the logging choice. Selecting an output never enables logging by itself.
         * {@link #resetEnabled()} selects the library default; {@link #useConfiguredEnabled()} restores inheritance.
         * @param enabled Whether Jakarta Mail debug logging is enabled.
         */
        @NotNull public Builder withEnabled(final boolean enabled) { this.enabled = enabled; return this; }
        /** @see #withEnabled(boolean) */
        @Nullable public Boolean getEnabled() { return enabled; }
        /** @see #withEnabled(boolean) */
        @NotNull public Builder resetEnabled() { return withEnabled(MailerGenericBuilder.DEFAULT_JAVAXMAIL_DEBUG); }
        /** @see #withEnabled(boolean) */
        @NotNull public Builder useConfiguredEnabled() { enabled = null; return this; }

        /**
         * Selects a built-in destination and removes any supplied printer without closing it.
         * {@link #resetOutput()} selects standard output; {@link #useConfiguredOutput()} restores inheritance.
         * @param output Destination for Jakarta Mail debug output.
         */
        @NotNull public Builder withOutput(@NotNull final SessionDebugOutput output) {
            this.output = requireNonNull(output, "output");
            printer = null;
            return this;
        }
        /** @see #withOutput(SessionDebugOutput) */
        @Nullable public SessionDebugOutput getOutput() { return output; }
        /** @see #withOutput(SessionDebugOutput) */
        @NotNull public Builder resetOutput() { return withOutput(MailerGenericBuilder.DEFAULT_JAVAXMAIL_DEBUG_OUTPUT); }
        /** @see #withOutput(SessionDebugOutput) */
        @NotNull public Builder useConfiguredOutput() { output = null; printer = null; return this; }

        /**
         * Selects a borrowed stream and removes any built-in destination. The stream is not closed or written during configuration.
         * {@link #clearPrinter()} removes only a custom stream; an unset target then inherits captured configuration.
         * @param printer Application-owned debug stream.
         */
        @NotNull public Builder withPrinter(@NotNull final PrintStream printer) {
            this.printer = requireNonNull(printer, "printer");
            output = null;
            return this;
        }
        /** @see #withPrinter(PrintStream) */
        @Nullable public PrintStream getPrinter() { return printer; }
        /** @see #withPrinter(PrintStream) */
        @NotNull public Builder clearPrinter() { printer = null; return this; }

        /** @return An immutable snapshot; borrowed streams retain their identity. */
        @NotNull public SessionDebugConfig build() { return new SessionDebugConfig(enabled, output, printer); }
    }
}
