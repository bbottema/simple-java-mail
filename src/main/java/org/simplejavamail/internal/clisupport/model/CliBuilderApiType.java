package org.simplejavamail.internal.clisupport.model;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static java.lang.String.format;

public enum CliBuilderApiType {
    EMAIL("email"), MAILER("mailer");
    
    @Nonnull
    private final String paramPrefix;
    
    CliBuilderApiType(@Nonnull String paramPrefix) {
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
	
	@Nonnull
	public String getParamPrefix() {
		return paramPrefix;
	}
}
