package org.simplejavamail.internal.clisupport.model;


import java.util.List;

public class CliReceivedCommand {
	private final CliCommandType matchedCommand;
	private final List<CliReceivedOptionData> receivedOptions;
	
	public CliReceivedCommand(CliCommandType matchedCommand, List<CliReceivedOptionData> receivedOptions) {
		this.matchedCommand = matchedCommand;
		this.receivedOptions = receivedOptions;
	}
	
	public CliCommandType getMatchedCommand() {
		return matchedCommand;
	}
	
	public List<CliReceivedOptionData> getReceivedOptions() {
		return receivedOptions;
	}
}
