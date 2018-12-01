package demo;

import org.simplejavamail.internal.modules.ModuleLoader;

public class CliShowCommandHelpDemoApp {
	public static void main(String[] args) {
		ModuleLoader.loadCliModule().runCLI(new String[]{
				"send",
				"--help"
		});
	}
}