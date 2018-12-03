package org.simplejavamail.internal.clisupport.model;


import java.util.List;

public class CliReceivedOptionData {
	private final CliDeclaredOptionSpec declaredOptionSpec;
	private final List<Object> providedOptionValues;
	
	public CliReceivedOptionData(CliDeclaredOptionSpec declaredOptionSpec, List<Object> providedOptionValues) {
		this.declaredOptionSpec = declaredOptionSpec;
		this.providedOptionValues = providedOptionValues;
	}
	
	public CliBuilderApiType determineTargetBuilderApi() {
		Class<?> apiNode = declaredOptionSpec.getSourceMethod().getDeclaringClass();
		return apiNode.getAnnotation(Cli.BuilderApiNode.class).builderApiType();
	}
	
	public CliDeclaredOptionSpec getDeclaredOptionSpec() {
		return declaredOptionSpec;
	}
	
	public List<Object> getProvidedOptionValues() {
		return providedOptionValues;
	}
}
