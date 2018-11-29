package demo;

import org.simplejavamail.internal.clisupport.CliSupport;

public class CliShowCommandOptiondHelpDemoApp {
	public static void main(String[] args) {
		CliSupport.runCLI(new String[] {
				"send",
				"--email:forwarding--help",
		});
	}
}