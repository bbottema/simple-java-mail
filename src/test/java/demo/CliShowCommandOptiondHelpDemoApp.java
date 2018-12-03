package demo;

import org.simplejavamail.internal.modules.ModuleLoader;

public class CliShowCommandOptiondHelpDemoApp {
	public static void main(String[] args) {
		ModuleLoader.loadCliModule().runCLI(new String[] {"send", "--email:forwarding--help",});
		System.out.println("\n\n\n\n\n\n---------------------");
		ModuleLoader.loadCliModule().runCLI(new String[] {"send", "--mailer:clearProxy--help",});
		System.out.println("\n\n\n\n\n\n---------------------");
		ModuleLoader.loadCliModule().runCLI(new String[] {"send", "--mailer:async--help",});
	}
}