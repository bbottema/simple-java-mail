package demo;

import org.simplejavamail.internal.modules.ModuleLoader;

public class CliShowCommandOptiondHelpDemoApp {
	public static void main(String[] args) {
		ModuleLoader.loadCliModule().runCLI(new String[] {
				"send",
				"--email:forwarding--help",
		});
	}
}