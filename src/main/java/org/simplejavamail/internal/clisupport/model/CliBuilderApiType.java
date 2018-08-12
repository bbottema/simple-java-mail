package org.simplejavamail.internal.clisupport.model;

public enum CliBuilderApiType {
    EMAIL("email"), MAILER("mailer");
    
    private final String paramPrefix;
    
    CliBuilderApiType(String paramPrefix) {
        this.paramPrefix = paramPrefix;
    }
	
	public String getParamPrefix() {
		return paramPrefix;
	}
}
