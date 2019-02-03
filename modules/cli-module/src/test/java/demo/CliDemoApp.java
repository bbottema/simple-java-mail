package demo;

import org.simplejavamail.internal.modules.ModuleLoader;
import org.simplejavamail.api.mailer.config.ServerConfig;
import testutil.ConfigLoaderTestHelper;

import static java.util.Objects.requireNonNull;

public class CliDemoApp extends DemoAppBase {
	
	private static final String SOURCE_FOLDER = determineResourceFolder("cli-module");
	
	/**
	 * For more detailed logging open log4j2.xml and change "org.simplejavamail.internal.clisupport" to debug.
	 */
	public static void main(String[] args) {
		ConfigLoaderTestHelper.clearConfigProperties();
		
		ServerConfig serverConfig = requireNonNull(DemoAppBase.mailerTLS.getServerConfig());
		
		demoTestConnection(serverConfig);
		demoSend(serverConfig);
		demoSendAsync(serverConfig);
		demoSendUsingFileAsHTMLBody(serverConfig);
	}
	
	private static void demoTestConnection(ServerConfig serverConfig) {
		ModuleLoader.loadCliModule().runCLI(new String[]{
				"connect",
				"--mailer:withSMTPServer", "smtp.gmail.com", "587", serverConfig.getUsername(), serverConfig.getPassword(),
				"--mailer:withTransportStrategy", "SMTP_TLS"
		});
	}
	
	private static void demoSend(ServerConfig serverConfig) {
		ModuleLoader.loadCliModule().runCLI(new String[]{
				"send",
				"--email:forwarding", SOURCE_FOLDER + "/test/resources/test-messages/HTML mail with replyto and attachment and embedded image.msg",
				"--email:from", "Test sender", DemoAppBase.YOUR_GMAIL_ADDRESS,
				"--email:to", "Test Receiver", DemoAppBase.YOUR_GMAIL_ADDRESS,
				"--mailer:withSMTPServer", "smtp.gmail.com", "587", serverConfig.getUsername(), serverConfig.getPassword(),
				"--mailer:withTransportStrategy", "SMTP_TLS"
		});
	}
	
	private static void demoSendAsync(ServerConfig serverConfig) {
		ModuleLoader.loadCliModule().runCLI(new String[]{
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
		ModuleLoader.loadCliModule().runCLI(new String[]{
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