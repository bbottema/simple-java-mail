package demo;

import org.simplejavamail.internal.modules.ModuleLoader;

public class CliShowCommandHelpDemoApp {
	public static void main(String[] args) {
		System.out.println("--------------- SEND -------------------");
		ModuleLoader.loadCliModule().runCLI(new String[]{"send", "--help"});
		System.out.println("\n\n\n\n\n\n-------------- CONNECT ------------------");
		ModuleLoader.loadCliModule().runCLI(new String[]{"connect", "--help"});
		System.out.println("\n\n\n\n\n\n------------- VALIDATE ------------------");
		ModuleLoader.loadCliModule().runCLI(new String[]{"validate", "--help"});
	}
}