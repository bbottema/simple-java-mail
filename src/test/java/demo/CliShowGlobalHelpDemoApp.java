package demo;

import org.simplejavamail.internal.clisupport.CliSupport;

public class CliShowGlobalHelpDemoApp {
	public static void main(String[] args) {
		CliSupport.runCLI(new String[] {
				"--help"
		});
	}
}