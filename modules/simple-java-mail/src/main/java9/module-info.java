module org.simplejavamail {
	uses org.simplejavamail.api.mailer.spi.MailTransportAdapter;

	requires static com.github.spotbugs.annotations;
	requires static org.jetbrains.annotations;
	requires static org.simplejavamail.smime;
	requires static org.simplejavamail.openpgp;

	requires com.pivovarit.function;

	requires transitive com.sanctionco.jmail;
	requires transitive jakarta.activation;
	requires transitive jakarta.mail;
	requires transitive org.simplejavamail.core;
	requires transitive org.slf4j;

	exports org.simplejavamail.api;
	exports org.simplejavamail.converter;
	exports org.simplejavamail.converter.internal;
	exports org.simplejavamail.converter.internal.mimemessage;
	exports org.simplejavamail.email.internal;
	exports org.simplejavamail.internal.moduleloader;
	exports org.simplejavamail.mailer;
	exports org.simplejavamail.mailer.internal;
	exports org.simplejavamail.mailer.internal.util;
	exports org.simplejavamail.recipient;
}
