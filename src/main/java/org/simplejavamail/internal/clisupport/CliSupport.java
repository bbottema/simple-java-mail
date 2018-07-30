package org.simplejavamail.internal.clisupport;

import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.simplejavamail.mailer.MailerFromSessionBuilder;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CliSupport {
	
	private static final Class<?>[] RELEVANT_BUILDER_ROOT_API = {
			EmailBuilder.EmailBuilderInstance.class,
			MailerBuilder.MailerRegularBuilder.class,
			MailerFromSessionBuilder.class
	};
	
	public static void runCLI(String[] args) {
		Collection<CliOptionFlag> parameterMap = generateOptionsNetwork(new HashMap<Class<?>, Collection<CliOptionFlag>>());
		CliParamPrinter.printCliParams(parameterMap, System.out);
	}
	
	private static Collection<CliOptionFlag> generateOptionsNetwork(Map<Class<?>, Collection<CliOptionFlag>> optionFollowupCombinations) {
		ArrayList<CliOptionFlag> cliOptionFlags = new ArrayList<>();
		for (Class<?> apiRoot : RELEVANT_BUILDER_ROOT_API) {
			cliOptionFlags.addAll(generateOptionsNetwork(apiRoot, optionFollowupCombinations));
		}
		return cliOptionFlags;
	}
	
	private static Collection<CliOptionFlag> generateOptionsNetwork(Class<?> apiNode, Map<Class<?>, Collection<CliOptionFlag>> optionsLists) {
		List<CliOptionFlag> cliOptions = new ArrayList<>();
		
		for (Method m : apiNode.getMethods()) {
			if (m.isAnnotationPresent(CliSupported.class)) {
				CliOptionFlag cliOption = new CliOptionFlag(determineCliOptionFlagName(apiNode, m), getArgumentsForCliParam(m));
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
