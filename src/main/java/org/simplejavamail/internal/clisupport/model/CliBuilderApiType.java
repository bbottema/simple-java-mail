package org.simplejavamail.internal.clisupport.model;

import javax.annotation.Nonnull;

public enum CliBuilderApiType {
    EMAIL("email"), MAILER("mailer");
    
    @Nonnull
    private final String paramPrefix;
    
    CliBuilderApiType(@Nonnull String paramPrefix) {
        this.paramPrefix = paramPrefix;
    }
	
	@Nonnull
	public String getParamPrefix() {
		return paramPrefix;
	}
}
