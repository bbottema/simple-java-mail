module org.simplejavamail.core {
	requires static com.github.spotbugs.annotations;
	requires static org.jetbrains.annotations;

	requires org.slf4j;

	requires transitive com.sanctionco.jmail;
	requires transitive jakarta.activation;
	requires transitive jakarta.mail;

	exports org.simplejavamail;
	exports org.simplejavamail.api.email;
	exports org.simplejavamail.api.email.config;
	exports org.simplejavamail.api.internal.authenticatedsockssupport.common;
	exports org.simplejavamail.api.internal.authenticatedsockssupport.socks5server;
	exports org.simplejavamail.api.internal.batchsupport;
	exports org.simplejavamail.api.internal.clisupport.model;
	exports org.simplejavamail.api.internal.general;
	exports org.simplejavamail.api.internal.outlooksupport.model;
	exports org.simplejavamail.api.internal.smimesupport.builder;
	exports org.simplejavamail.api.internal.smimesupport.model;
	exports org.simplejavamail.api.mailer;
	exports org.simplejavamail.api.mailer.config;
	exports org.simplejavamail.api.outlook;
	exports org.simplejavamail.config;
	exports org.simplejavamail.internal.config;
	exports org.simplejavamail.internal.modules;
	exports org.simplejavamail.internal.util;
	exports org.simplejavamail.internal.util.concurrent;
}
