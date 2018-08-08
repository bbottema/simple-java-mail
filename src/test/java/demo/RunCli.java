package demo;

import org.simplejavamail.internal.clisupport.CliSupport;

public class RunCli {
	public static void main(String[] args) {
		CliSupport.runCLI(args.length > 0 ? args : new String[]{
				"send",
				"--email:replyingTo",
				"src/test/resources/test-messages/HTML mail with replyto and attachment and embedded image.msg",
				"true",
				"moomoo",
		});
	}
}