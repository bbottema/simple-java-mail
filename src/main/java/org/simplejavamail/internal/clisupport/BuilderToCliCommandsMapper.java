package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.annotation.CliCommand;
import org.simplejavamail.internal.clisupport.annotation.CliParam;
import org.simplejavamail.internal.clisupport.annotation.CliSupported;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static java.lang.String.format;

final class BuilderToCliCommandsMapper {
	
	private BuilderToCliCommandsMapper() {
	}
	
	 static Collection<CliCommandData> generateCommandsAndSubcommands(Class<?>[] relevantBuilderRootApi, Map<Class<?>, Collection<CliCommandData>> optionsMappedForClass) {
		List<CliCommandData> cliCommands = new ArrayList<>();
		for (Class<?> apiRoot : relevantBuilderRootApi) {
			cliCommands.addAll(generateCommandsAndSubcommands(apiRoot, optionsMappedForClass));
		}
		return cliCommands;
	}
	
	private static Collection<CliCommandData> generateCommandsAndSubcommands(Class<?> apiNode, Map<Class<?>, Collection<CliCommandData>> commandList) {
		List<CliCommandData> cliCommands = new ArrayList<>();
		
		for (Method m : apiNode.getMethods()) {
			if (m.isAnnotationPresent(CliCommand.class)) {
				CliCommandData cliCommand = new CliCommandData(
						determineCliCommandName(apiNode, m),
						getArgumentsForCliParam(m));
				cliCommands.add(cliCommand);
				
				if (m.getReturnType().isAnnotationPresent(CliSupported.class)) {
					if (!commandList.containsKey(m.getReturnType())) {
						commandList.put(m.getReturnType(), new ArrayList<CliCommandData>());
						commandList.get(m.getReturnType()).addAll(generateCommandsAndSubcommands(m.getReturnType(), commandList));
					}
					cliCommand.setSubCommands(commandList.get(m.getReturnType()));
				}
			}
		}
		
		return cliCommands;
	}
	
	@Nonnull
	private static String determineCliCommandName(Class<?> apiNode, Method m) {
		String cliParamPrefix = apiNode.getAnnotation(CliSupported.class).paramPrefix();
		return (!cliParamPrefix.isEmpty() ? cliParamPrefix + ":" : "") + m.getName();
	}
	
	@Nonnull
	private static List<CliParamData> getArgumentsForCliParam(Method m) {
		final List<CliParamData> cliParams = new ArrayList<>();
		final Annotation[][] annotations = m.getParameterAnnotations();
		final Class<?>[] parameterTypes = m.getParameterTypes();
		for (int i = 0; i < parameterTypes.length; i++) {
			Class<?> p = parameterTypes[i];
			CliParam pa = findCliParamAnnotation(annotations[i], m);
			cliParams.add(new CliParamData(p, determineCliParamName(pa, p), pa.helpLabel(), pa.description(), pa.example()));
		}
		return cliParams;
	}
	
	private static CliParam findCliParamAnnotation(Annotation[] a, Method m) {
		for (Annotation annotation : a) {
			if (annotation instanceof CliParam) {
				return (CliParam) annotation;
			}
		}
		throw new AssertionError(format("CliCommand for method %s missing @CliParam annotation for method param", m.getName()));
	}
	
	@Nonnull
	private static String determineCliParamName(CliParam cliParamAnnotation, Class<?> cliParamType) {
		return !cliParamAnnotation.name().isEmpty() ? cliParamAnnotation.name() : cliParamType.getSimpleName();
	}
}
