package demo;

import org.simplejavamail.internal.modules.ModuleLoader;

public class CliShowGlobalHelpDemoApp {
	public static void main(String[] args) {
		ModuleLoader.loadCliModule().runCLI(new String[]{
				"--help"
		});
	}
}