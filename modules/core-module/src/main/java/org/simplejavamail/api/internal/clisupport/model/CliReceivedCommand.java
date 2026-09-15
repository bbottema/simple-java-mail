package org.simplejavamail.api.internal.clisupport.model;


import java.util.List;

/** Ordered builder options and invocation-only choices for one parsed CLI command. */
public class CliReceivedCommand {
	private final CliCommandType matchedCommand;
	private final List<CliReceivedOptionData> receivedOptions;
	private final boolean authenticationRequested;
	
	public CliReceivedCommand(CliCommandType matchedCommand, List<CliReceivedOptionData> receivedOptions,
			boolean authenticationRequested) {
		this.matchedCommand = matchedCommand;
		this.receivedOptions = receivedOptions;
		this.authenticationRequested = authenticationRequested;
	}
	
	public CliCommandType getMatchedCommand() {
		return matchedCommand;
	}
	
	public List<CliReceivedOptionData> getReceivedOptions() {
		return receivedOptions;
	}

	/** Whether this probe should test configured SMTP credentials; not part of the reusable Mailer profile. */
	public boolean isAuthenticationRequested() {
		return authenticationRequested;
	}
}
