package demo;

import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;

public class RunCli {
	public static void main(String[] args) {
		long startMs = System.currentTimeMillis();
		
		for (CliDeclaredOptionSpec declaredOption : CliSupport.DECLARED_OPTIONS) {
			CliSupport.runCLI(args.length > 0 ? args : new String[]{ "send", declaredOption.getName() + "--help" });
			System.out.print("\n\n\n\n");
		}
		
		System.out.println(((System.currentTimeMillis() - startMs) / 1000d) + "ms");
		/*
		CliSupport.runCLI(args.length > 0 ? args : new String[]{
				"send",
//				"--help",
				"--mailer:withTransportStrategy--help", "src/test/resources/test-messages/HTML mail with replyto and attachment and embedded image.msg",
				"--email:replyingToSenderWithDefaultQuoteMarkup", "src/test/resources/test-messages/HTML mail with replyto and attachment and embedded image.msg",
				"--email:from", "Test sender", "bob@mob.com",
				"--email:to", "Test Receiver", "b.bottema@gmail.com",
//				"--mailer:withSMTPServer", "smtp.gmail.com", "587", "b.bottema@gmail.com", "edcmogewhggrrfto",
//				"--mailer:withTransportStrategy", "SMTP_TLS",
//				"--mailer:clearProxy"
		});*/
	}
}