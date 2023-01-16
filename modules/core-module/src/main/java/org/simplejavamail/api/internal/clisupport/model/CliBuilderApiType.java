package org.simplejavamail.api.internal.clisupport.model;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static java.lang.String.format;

public enum CliBuilderApiType {
    EMAIL("email"), MAILER("mailer");
	
	@NotNull
    private final String paramPrefix;
    
    CliBuilderApiType(@NotNull String paramPrefix) {
        this.paramPrefix = paramPrefix;
    }
	
	public static Collection<CliBuilderApiType> findForCliSynopsis(String synopsis) {
		Set<CliBuilderApiType> foundForSynopsis = new HashSet<>();
		for (CliBuilderApiType builderApiType : values()) {
			if (synopsis.matches(format(".*--%s:.*", builderApiType.paramPrefix))) {
				foundForSynopsis.add(builderApiType);
			}
		}
		return foundForSynopsis;
	}
	
	@NotNull
	public String getParamPrefix() {
		return paramPrefix;
	}
}
