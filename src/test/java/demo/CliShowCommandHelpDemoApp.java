package demo;

import org.simplejavamail.internal.clisupport.CliSupport;

public class CliShowCommandHelpDemoApp {
	public static void main(String[] args) {
		CliSupport.runCLI(new String[]{
				"send",
				"--help"
		});
	}
}