package demo;

import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.api.mailer.config.ServerConfig;

import static demo.ResourceFolderHelper.determineResourceFolder;
import static java.util.Objects.requireNonNull;

public class CliDemoApp extends DemoAppBase {
	
	private static final String SOURCE_FOLDER = determineResourceFolder("cli-module");
	
	/**
	 * For more detailed logging open log4j2.xml and change "org.simplejavamail.internal.clisupport" to debug.
	 */
	public static void main(String[] args) {
		LOGGER.info("\n\n--- IGNORE BELOW LOGGING: it is just to get the server config instance ---");
		ServerConfig serverConfig = requireNonNull(DemoAppBase.mailerTLS.getServerConfig());
		LOGGER.info("\n--- IGNORE ABOVE LOGGING: it is just to get the server config instance ---\n");

		demoTestConnection(serverConfig);
		demoSend(serverConfig);
		demoSendAsync(serverConfig);
		demoSendUsingFileAsHTMLBody(serverConfig);
	}
	
	private static void demoTestConnection(ServerConfig serverConfig) {
		CliSupport.runCLI(new String[]{
				"connect",
				"--mailer:withSMTPServer", "smtp.gmail.com", "587", serverConfig.getUsername(), serverConfig.getPassword(),
				"--mailer:withTransportStrategy", "SMTP_TLS"
		});
	}
	
	private static void demoSend(ServerConfig serverConfig) {
		CliSupport.runCLI(new String[]{
				"send",
				"--email:forwarding", SOURCE_FOLDER + "/test/resources/test-messages/HTML mail with replyto and attachment and embedded image.msg",
				"--email:from", "Test sender", DemoAppBase.YOUR_GMAIL_ADDRESS,
				"--email:to", "Test Receiver", DemoAppBase.YOUR_GMAIL_ADDRESS,
				"--mailer:withSMTPServer", "smtp.gmail.com", "587", serverConfig.getUsername(), serverConfig.getPassword(),
				"--mailer:withTransportStrategy", "SMTP_TLS"
		});
	}
	
	private static void demoSendAsync(ServerConfig serverConfig) {
		CliSupport.runCLI(new String[]{
				"send",
				"--email:forwarding", SOURCE_FOLDER + "/test/resources/test-messages/HTML mail with replyto and attachment and embedded image.msg",
				"--email:from", "Test sender", DemoAppBase.YOUR_GMAIL_ADDRESS,
				"--email:to", "Test Receiver", DemoAppBase.YOUR_GMAIL_ADDRESS,
				"--mailer:async",
				"--mailer:withSMTPServer", "smtp.gmail.com", "587", serverConfig.getUsername(), serverConfig.getPassword(),
				"--mailer:withTransportStrategy", "SMTP_TLS"
		});
	}
	
	private static void demoSendUsingFileAsHTMLBody(ServerConfig serverConfig) {
		CliSupport.runCLI(new String[]{
				"send",
				"--email:startingBlank",
				"--email:withHTMLTextFromFile", SOURCE_FOLDER + "/test/resources/test-messages/html-body.html",
				"--email:from", "Test sender", DemoAppBase.YOUR_GMAIL_ADDRESS,
				"--email:to", "Test Receiver", DemoAppBase.YOUR_GMAIL_ADDRESS,
				"--mailer:withSMTPServer", "smtp.gmail.com", "587", serverConfig.getUsername(), serverConfig.getPassword(),
				"--mailer:withTransportStrategy", "SMTP_TLS"
		});
	}
}