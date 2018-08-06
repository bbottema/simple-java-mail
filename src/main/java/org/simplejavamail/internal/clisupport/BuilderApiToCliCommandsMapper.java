package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.annotation.CliCommand;
import org.simplejavamail.internal.clisupport.annotation.CliCommandDelegate;
import org.simplejavamail.internal.clisupport.annotation.CliParam;
import org.simplejavamail.internal.clisupport.annotation.CliSupported;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static java.lang.String.format;
import static java.util.Arrays.asList;

final class BuilderApiToCliCommandsMapper {
	
	private BuilderApiToCliCommandsMapper() {
	}
	
	 static TreeSet<CliCommandData> generateCommandsAndSubcommands(Class<?>[] relevantBuilderRootApi, Map<Class<?>, Collection<CliCommandData>> optionsMappedForClass) {
		TreeSet<CliCommandData> cliCommands = new TreeSet<>();
		for (Class<?> apiRoot : relevantBuilderRootApi) {
			cliCommands.addAll(generateCommandsAndSubcommands(apiRoot, optionsMappedForClass));
		}
		return cliCommands;
	}
	
	private static Collection<CliCommandData> generateCommandsAndSubcommands(Class<?> apiNode, Map<Class<?>, Collection<CliCommandData>> commandList) {
		List<CliCommandData> cliCommands = new ArrayList<>();
		
		for (Method m : apiNode.getMethods()) {
			if (m.isAnnotationPresent(CliCommand.class)) {
				
				Collection<CliCommandData> subCommands = new ArrayList<>();
				if (m.getReturnType().isAnnotationPresent(CliSupported.class)) {
					if (!commandList.containsKey(m.getReturnType())) {
						commandList.put(m.getReturnType(), new ArrayList<CliCommandData>());
						commandList.get(m.getReturnType()).addAll(generateCommandsAndSubcommands(m.getReturnType(), commandList));
					}
					subCommands.addAll(commandList.get(m.getReturnType()));
				}
				
				cliCommands.add(new CliCommandData(
						determineCliCommandName(apiNode, m),
						determineCliCommandDescriptions(m),
						getArgumentsForCliParam(m), determineApplicableRootCommands(apiNode, m),
						subCommands));
			}
		}
		
		return cliCommands;
	}
	
	private static Collection<CliSupported.RootCommand> determineApplicableRootCommands(Class<?> apiNode, Method m) {
		CliSupported cliSupportedApiNode = apiNode.getAnnotation(CliSupported.class);
		CliCommand cliCommand = m.getAnnotation(CliCommand.class);
		return cliCommand.applicableRootCommands().length > 0
				? asList(cliCommand.applicableRootCommands())
				: asList(cliSupportedApiNode.applicableRootCommands());
	}
	
	@Nonnull
	private static List<String> determineCliCommandDescriptions(Method m) {
		List<String> declaredDescriptions = new ArrayList<>(asList(m.getAnnotation(CliCommand.class).description()));
		
		if (m.isAnnotationPresent(CliCommandDelegate.class)) {
			declaredDescriptions.add("@|underline INCLUDED DOCUMENTATION|@:");
			declaredDescriptions.addAll(determineCliCommandDescriptions(findDeferredMethod(m.getAnnotation(CliCommandDelegate.class))));
		}
		
		return declaredDescriptions;
	}
	
	private static Method findDeferredMethod(CliCommandDelegate cliCommandDelegate) {
		try {
			return cliCommandDelegate.delegateClass().getMethod(cliCommandDelegate.delegateMethod(), cliCommandDelegate.delegateParameters());
		} catch (NoSuchMethodException e) {
			throw new AssertionError("@CliCommandDelegate configured incorrectly, method not found for: " + cliCommandDelegate);
		}
	}
	
	@Nonnull
	private static String determineCliCommandName(Class<?> apiNode, Method m) {
		String cliCommandPrefix = apiNode.getAnnotation(CliSupported.class).paramPrefix();
		String cliCommandNameOverride = m.getAnnotation(CliCommand.class).nameOverride();
		String effectiveCommandName = cliCommandNameOverride.isEmpty() ? m.getName() : cliCommandNameOverride;
		return (!cliCommandPrefix.isEmpty() ? cliCommandPrefix + ":" : "") + effectiveCommandName;
	}
	
	@Nonnull
	private static List<CliParamData> getArgumentsForCliParam(Method m) {
		final List<CliParamData> cliParams = new ArrayList<>();
		final Annotation[][] annotations = m.getParameterAnnotations();
		final Class<?>[] parameterTypes = m.getParameterTypes();
		for (int i = 0; i < parameterTypes.length; i++) {
			Class<?> p = parameterTypes[i];
			CliParam pa = findCliParamAnnotation(annotations[i], CliParam.class, m.getName());
			cliParams.add(new CliParamData(p, determineCliParamName(pa, p), pa.helpLabel(), pa.description(), pa.required(), pa.example()));
		}
		return cliParams;
	}
	
	@SuppressWarnings({"unchecked", "SameParameterValue"})
	private static <T extends Annotation> T findCliParamAnnotation(@Nonnull Annotation[] a, @Nonnull Class<T> annotationToFind, @Nonnull String methodName) {
		for (Annotation annotation : a) {
			if (annotationToFind.isAssignableFrom(annotation.getClass())) {
				return (T) annotation;
			}
		}
		throw new AssertionError(format("CliCommand for method %s missing @CliParam annotation for method param", methodName));
	}
	
	@Nonnull
	private static String determineCliParamName(CliParam cliParamAnnotation, Class<?> cliParamType) {
		return !cliParamAnnotation.name().isEmpty() ? cliParamAnnotation.name() : cliParamType.getSimpleName();
	}
}
