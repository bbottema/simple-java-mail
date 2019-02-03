package demo;

import org.simplejavamail.internal.modules.ModuleLoader;

public class CliShowCommandOptiondHelpDemoApp {
	
	/**
	 * For more detailed logging open log4j2.xml and change "org.simplejavamail.internal.clisupport" to debug.
	 */
	public static void main(String[] args) {
		ModuleLoader.loadCliModule().runCLI(new String[] {"send", "--email:forwarding--help",});
		System.out.println("\n\n\n\n\n\n---------------------");
		ModuleLoader.loadCliModule().runCLI(new String[] {"send", "--mailer:clearProxy--help",});
		System.out.println("\n\n\n\n\n\n---------------------");
		ModuleLoader.loadCliModule().runCLI(new String[] {"send", "--mailer:async--help",});
	}
}