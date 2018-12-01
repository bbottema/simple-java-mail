package demo;

import org.simplejavamail.internal.modules.ModuleLoader;

public class CliListAllSupportedOptionsDemoApp {
	public static void main(String[] args) {
		long startMs = System.currentTimeMillis();
		ModuleLoader.loadCliModule().listUsagesForAllOptions();
		System.out.println(((System.currentTimeMillis() - startMs) / 1000d) + "ms");
	}
}