module org.simplejavamail.otel {
    requires transitive org.simplejavamail.core;
    requires transitive io.opentelemetry.api;
    requires io.opentelemetry.context;
    requires static org.jetbrains.annotations;
    exports org.simplejavamail.otel;
    exports org.simplejavamail.internal.telemetry.otel to org.simplejavamail;
}
