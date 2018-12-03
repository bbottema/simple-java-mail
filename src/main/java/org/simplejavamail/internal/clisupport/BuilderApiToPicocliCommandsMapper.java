package org.simplejavamail.internal.clisupport;

import org.bbottema.javareflection.BeanUtils;
import org.bbottema.javareflection.BeanUtils.Visibility;
import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.MethodUtils;
import org.bbottema.javareflection.model.LookupMode;
import org.bbottema.javareflection.model.MethodModifier;
import org.bbottema.javareflection.valueconverter.ValueConversionHelper;
import org.simplejavamail.internal.clisupport.model.Cli;
import org.simplejavamail.internal.clisupport.model.CliCommandType;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper.DocumentedMethodParam;
import org.simplejavamail.internal.clisupport.valueinterpreters.EmlFilePathToMimeMessageFunction;
import org.simplejavamail.internal.clisupport.valueinterpreters.MsgFilePathToMimeMessageFunction;
import org.simplejavamail.internal.clisupport.valueinterpreters.StringToTransportStrategyFunction;
import org.simplejavamail.internal.util.StringUtil;
import org.simplejavamail.internal.util.StringUtil.StringFormatter;
import org.simplejavamail.mailer.config.TransportStrategy;
import org.slf4j.Logger;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.internet.MimeMessage;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.EnumSet.allOf;
import static java.util.EnumSet.of;
import static java.util.regex.Pattern.compile;
import static org.bbottema.javareflection.TypeUtils.containsAnnotation;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.slf4j.LoggerFactory.getLogger;

public final class BuilderApiToPicocliCommandsMapper {

	private static final Logger LOGGER = getLogger(BuilderApiToPicocliCommandsMapper.class);
	
	/**
	 * These help generate the picocli labels for the support types. Results in something like:<br/>
	 * <code>--someOption(=NUM)</code>
	 */
	private static final Map<Class<?>, String> TYPE_LABELS = new HashMap<Class<?>, String>() {{
		put(boolean.class, "BOOL");
		put(Boolean.class, "BOOL");
		put(String.class, "TEXT");
		put(Object.class, "TEXT");
		put(TransportStrategy.class, "NAME");
		put(int.class, "NUM");
		put(Integer.class, "NUM");
		put(MimeMessage.class, "FILE");
		put(DataSource.class, "FILE");
		put(byte[].class, "FILE");
		put(InputStream.class, "FILE");
	}};
	
	static {
		ValueConversionHelper.registerValueConverter(new EmlFilePathToMimeMessageFunction());
		ValueConversionHelper.registerValueConverter(new MsgFilePathToMimeMessageFunction());
		ValueConversionHelper.registerValueConverter(new StringToTransportStrategyFunction());
	}
	
	private BuilderApiToPicocliCommandsMapper() {
	}
	
	@Nonnull
	static List<CliDeclaredOptionSpec> generateOptionsFromBuilderApi(Class<?>[] relevantBuilderRootApi) {
		final List<CliDeclaredOptionSpec> cliOptions = new ArrayList<>();
		final Set<Class<?>> processedApiNodes = new HashSet<>();
		for (Class<?> apiRoot : relevantBuilderRootApi) {
			generateOptionsFromBuilderApiChain(apiRoot, processedApiNodes, cliOptions);
		}
		Collections.sort(cliOptions);
		return cliOptions;
	}
	
	private static void generateOptionsFromBuilderApiChain(Class<?> apiNode, Set<Class<?>> processedApiNodes, List<CliDeclaredOptionSpec> cliOptionsFoundSoFar) {
		Class<?> apiNodeChainClass = apiNode;
		while (apiNodeChainClass.getPackage().getName().contains("org.simplejavamail")) {
			generateOptionsFromBuilderApi(apiNodeChainClass, processedApiNodes, cliOptionsFoundSoFar);
			apiNodeChainClass = apiNodeChainClass.getSuperclass();
		}
	}
	
	/**
	 * Produces all the --option Picocli-based params for specific API class. <br/>
	 * Recursive for returned API class (since builders can return different builders.
	 */
	private static void generateOptionsFromBuilderApi(Class<?> apiNode, Set<Class<?>> processedApiNodes, List<CliDeclaredOptionSpec> cliOptionsFoundSoFar) {
		if (processedApiNodes.contains(apiNode)) {
			return;
		}
		
		processedApiNodes.add(apiNode);
		
		for (Method m : ClassUtils.collectMethods(apiNode, apiNode, of(MethodModifier.PUBLIC))) {
			if (methodIsCliCompatible(m)) {
				final String optionName = determineCliOptionName(apiNode, m);
				LOGGER.debug("option {} found for {}.{}({})", optionName, apiNode.getSimpleName(), m.getName(), m.getParameterTypes());
				
				// assertion check
				for (CliDeclaredOptionSpec knownOption : cliOptionsFoundSoFar) {
					if (knownOption.getName().equals(optionName)) {
						String msg = "@CliOptionNameOverride needed one of the following two methods:\n\t%s\n\t%s\n\t----------";
						throw new AssertionError(format(msg, knownOption.getSourceMethod(), m));
					}
				}
				
				cliOptionsFoundSoFar.add(new CliDeclaredOptionSpec(
						optionName,
						TherapiJavadocHelper.determineCliOptionDescriptions(m),
						getArgumentsForCliOption(m),
						apiNode.getAnnotation(Cli.BuilderApiNode.class).builderApiType(),
						determineApplicableRootCommands(apiNode, m),
						m));
				Class<?> potentialNestedApiNode = m.getReturnType();
				if (potentialNestedApiNode.isAnnotationPresent(Cli.BuilderApiNode.class)) {
					generateOptionsFromBuilderApiChain(potentialNestedApiNode, processedApiNodes, cliOptionsFoundSoFar);
				}
			} else {
				LOGGER.debug("Method not CLI compatible: {}.{}({})", apiNode.getSimpleName(), m.getName(), Arrays.toString(m.getParameterTypes()));
			}
		}
	}

	public static boolean methodIsCliCompatible(Method m) {
		if (!m.getDeclaringClass().isAnnotationPresent(Cli.BuilderApiNode.class) ||
				m.isAnnotationPresent(Cli.ExcludeApi.class) ||
				BeanUtils.isBeanMethod(m, m.getDeclaringClass(), allOf(Visibility.class)) ||
				MethodUtils.methodHasCollectionParameter(m)) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Class<String>[] stringParameters = new Class[m.getParameterTypes().length];
		Arrays.fill(stringParameters, String.class);
		return MethodUtils.isMethodCompatible(m, allOf(LookupMode.class), stringParameters);
	}
	
	@Nonnull
	private static Collection<CliCommandType> determineApplicableRootCommands(Class<?> apiNode, Method m) {
		return allOf(CliCommandType.class);
	}
	
	@Nonnull
	public static List<String> colorizeDescriptions(List<String> descriptions) {
		List<String> colorizedDescriptions = new ArrayList<>();
		for (String description : descriptions) {
			colorizedDescriptions.add(colorizeOptionsInText(description, CliColorScheme.OPTION_STYLE));
		}
		return colorizedDescriptions;
	}
	
	@Nonnull
	public static String colorizeOptionsInText(String text, String ansiStyles) {
		final StringFormatter TOKEN_REPLACER = StringFormatter.formatterForPattern("@|" + ansiStyles + " %s|@");
		final String optionRegex = "(?:--(?:help|version)|-(?:h|v)|(?:--?\\w+:\\w+))(?!\\w)"; // https://regex101.com/r/SOs17K/4
		return StringUtil.replaceNestedTokens(text, 0, "@|", "|@", optionRegex, TOKEN_REPLACER);
	}
	
	@Nonnull
	public static String determineCliOptionName(Class<?> apiNode, Method m) {
		String methodName = m.isAnnotationPresent(Cli.OptionNameOverride.class)
				? m.getAnnotation(Cli.OptionNameOverride.class).value()
				: m.getName();

		final String cliCommandPrefix = apiNode.getAnnotation(Cli.BuilderApiNode.class).builderApiType().getParamPrefix();
		assumeTrue(!cliCommandPrefix.isEmpty(), "Option prefix missing from API class");
		return format("--%s:%s", cliCommandPrefix, methodName);
	}
	
	@Nonnull
	public static List<CliDeclaredOptionValue> getArgumentsForCliOption(Method m) {
		final Annotation[][] annotations = m.getParameterAnnotations();
		final Class<?>[] declaredParameters = m.getParameterTypes();
		final List<DocumentedMethodParam> documentedParameters = TherapiJavadocHelper.getParamDescriptions(m);

		final List<CliDeclaredOptionValue> cliParams = new ArrayList<>();
		
		for (int i = 0; i < declaredParameters.length; i++) {
			final Class<?> p = declaredParameters[i];
			final DocumentedMethodParam dP = documentedParameters.get(i);
			final boolean required = !containsAnnotation(asList(annotations[i]), Nullable.class);
			final String javadocDescription = extractJavadocDescription(dP.getJavadoc());
			final String[] javadocExamples = extractJavadocExamples(dP.getJavadoc());
			cliParams.add(new CliDeclaredOptionValue(p, dP.getName(), determineTypeLabel(p), javadocDescription, required, javadocExamples));
		}
		
		return cliParams;
	}
	
	@Nonnull
	static String extractJavadocDescription(String javadoc) {
		return javadoc.substring(0, determineJavadocLengthUntilExamples(javadoc, false));
	}
	
	@Nonnull
	static String[] extractJavadocExamples(String javadoc) {
		final int javadocLengthIncludingExamples = determineJavadocLengthUntilExamples(javadoc, true);
		if (javadocLengthIncludingExamples != javadoc.length()) {
			return javadoc.substring(javadocLengthIncludingExamples)
					.replaceAll("(?m)^\\s*-\\s*", "") // trim leading whitespace
					.replaceAll("(?m)\\s*$", "") // trim trailing whitespace
					.split("\\r?\\n"); // split on trimmed newlines
		}
		return new String[0];
	}
	
	private static int determineJavadocLengthUntilExamples(String javadoc, boolean includeExamplesTextLength) {
		final Pattern PATTERN_EXAMPLES_MARKER = compile("(?i)(?s).*(?<examples> examples?:\\s*)"); // https://regex101.com/r/UMMmlV/3
		final Matcher matcher = PATTERN_EXAMPLES_MARKER.matcher(javadoc);
		return (matcher.find())
				? matcher.end() - (!includeExamplesTextLength ? matcher.group("examples").length() : 0)
				: javadoc.length();
	}

	@Nonnull
	private static String determineTypeLabel(Class<?> type) {
		return checkNonEmptyArgument(TYPE_LABELS.get(type), "Missing type label for type " + type);
	}
}
