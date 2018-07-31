package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.annotation.CliSupported;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

final class BuildToCliOptionsMapper {
	
	private BuildToCliOptionsMapper() {
	}
	
	 static Collection<CliOptionFlag> generateOptionsNetwork(Class<?>[] relevantBuilderRootApi, Map<Class<?>, Collection<CliOptionFlag>> optionsMappedForClass) {
		List<CliOptionFlag> cliOptionFlags = new ArrayList<>();
		for (Class<?> apiRoot : relevantBuilderRootApi) {
			cliOptionFlags.addAll(generateOptionsNetwork(apiRoot, optionsMappedForClass));
		}
		return cliOptionFlags;
	}
	
	private static Collection<CliOptionFlag> generateOptionsNetwork(Class<?> apiNode, Map<Class<?>, Collection<CliOptionFlag>> optionsLists) {
		List<CliOptionFlag> cliOptions = new ArrayList<>();
		
		for (Method m : apiNode.getMethods()) {
			if (m.isAnnotationPresent(CliSupported.class)) {
				CliOptionFlag cliOption = new CliOptionFlag(
						determineCliOptionFlagName(apiNode, m),
						m.getAnnotation(CliSupported.class).helpLabel(),
						getArgumentsForCliParam(m));
				cliOptions.add(cliOption);
				
				if (m.getReturnType().isAnnotationPresent(CliSupported.class)) {
					if (!optionsLists.containsKey(m.getReturnType())) {
						optionsLists.put(m.getReturnType(), new ArrayList<CliOptionFlag>());
						optionsLists.get(m.getReturnType()).addAll(generateOptionsNetwork(m.getReturnType(), optionsLists));
					}
					cliOption.setValidNextOptions(optionsLists.get(m.getReturnType()));
				}
			}
		}
		
		return cliOptions;
	}
	
	@Nonnull
	private static String determineCliOptionFlagName(Class<?> apiNode, Method m) {
		String cliParamPrefix = apiNode.getAnnotation(CliSupported.class).paramPrefix();
		return (!cliParamPrefix.isEmpty() ? cliParamPrefix + ":" : "") + m.getName();
	}
	
	@Nonnull
	private static List<CliParam> getArgumentsForCliParam(Method m) {
		List<CliParam> cliParams = new ArrayList<>();
		for (Class<?> p : m.getParameterTypes()) {
			if (p.isAnnotationPresent(CliSupported.CliParam.class)) {
				CliSupported.CliParam cliParamAnnotation = p.getAnnotation(CliSupported.CliParam.class);
				cliParams.add(new CliParam(p, determineCliParamName(cliParamAnnotation, p), cliParamAnnotation.example()));
			}
		}
		return cliParams;
	}
	
	@Nonnull
	private static String determineCliParamName(CliSupported.CliParam cliParamAnnotation, Class<?> cliArgument) {
		return !cliParamAnnotation.name().isEmpty() ? cliParamAnnotation.name() : cliArgument.getSimpleName();
	}
}
