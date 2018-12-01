package org.simplejavamail.internal.clisupport;

public interface CLIModule {
	void runCLI(String[] args);
	
	void listUsagesForAllOptions();
}
