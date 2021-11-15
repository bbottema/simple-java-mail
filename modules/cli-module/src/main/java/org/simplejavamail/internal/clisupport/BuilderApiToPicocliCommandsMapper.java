package org.simplejavamail.internal.clisupport;

import com.google.code.regexp.Pattern;
import com.google.code.regexp.Matcher;
import org.bbottema.javareflection.BeanUtils;
import org.bbottema.javareflection.BeanUtils.Visibility;
import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.MethodUtils;
import org.bbottema.javareflection.model.LookupMode;
import org.bbottema.javareflection.model.MethodModifier;
import org.bbottema.javareflection.valueconverter.ValueConversionHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper.DocumentedMethodParam;
import org.simplejavamail.internal.clisupport.valueinterpreters.EmlFilePathToMimeMessageFunction;
import org.simplejavamail.internal.clisupport.valueinterpreters.MsgFilePathToMimeMessageFunction;
import org.simplejavamail.internal.clisupport.valueinterpreters.PemFilePathToX509CertificateFunction;
import org.simplejavamail.internal.clisupport.valueinterpreters.StringToCalendarMethodFunction;
import org.simplejavamail.internal.clisupport.valueinterpreters.StringToFileFunction;
import org.simplejavamail.internal.clisupport.valueinterpreters.StringToLoadBalancingStrategyFunction;
import org.simplejavamail.internal.clisupport.valueinterpreters.StringToTransportStrategyFunction;
import org.simplejavamail.internal.util.StringUtil;
import org.simplejavamail.internal.util.StringUtil.StringFormatter;
import org.slf4j.Logger;

import javax.activation.DataSource;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.google.code.regexp.Pattern.compile;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.EnumSet.allOf;
import static java.util.EnumSet.of;
import static org.bbottema.javareflection.TypeUtils.containsAnnotation;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.slf4j.LoggerFactory.getLogger;

public final class BuilderApiToPicocliCommandsMapper {

	private static final Logger LOGGER = getLogger(BuilderApiToPicocliCommandsMapper.class);
	
	/**
	 * These help generate the picocli labels for the support types. Results in something like:<br>
	 * <code>--someOption(=NUM)</code>
	 */
	@SuppressWarnings("serial")
	private static final Map<Class<?>, String> TYPE_LABELS = new HashMap<Class<?>, String>() {{
		put(boolean.class, "BOOL");
		put(Boolean.class, "BOOL");
		put(String.class, "TEXT");
		put(Object.class, "TEXT");
		put(TransportStrategy.class, "NAME");
		put(CalendarMethod.class, "RFC-2446 VEVENT METHOD");
		put(int.class, "NUM");
		put(Integer.class, "NUM");
		put(MimeMessage.class, "EML FILE");
		put(DataSource.class, "FILE");
		put(byte[].class, "FILE");
		put(InputStream.class, "FILE");
		put(File.class, "FILE");
		put(X509Certificate.class, "PEM FILE");
		put(UUID.class, "UUID");
		put(LoadBalancingStrategy.class, "NAME");
		put(Date.class, "yyyy-[M]M-[d]d[ HH:mm]");
	}};
	
	static {
		ValueConversionHelper.registerValueConverter(new StringToFileFunction());
		ValueConversionHelper.registerValueConverter(new EmlFilePathToMimeMessageFunction());
		ValueConversionHelper.registerValueConverter(new MsgFilePathToMimeMessageFunction());
		ValueConversionHelper.registerValueConverter(new PemFilePathToX509CertificateFunction());
		ValueConversionHelper.registerValueConverter(new StringToTransportStrategyFunction());
		ValueConversionHelper.registerValueConverter(new StringToLoadBalancingStrategyFunction());
		ValueConversionHelper.registerValueConverter(new StringToCalendarMethodFunction());
	}
	
	private BuilderApiToPicocliCommandsMapper() {
	}
	
	@NotNull
	static List<CliDeclaredOptionSpec> generateOptionsFromBuilderApi(@SuppressWarnings("SameParameterValue") Class<?>[] relevantBuilderRootApi) {
		final Set<CliDeclaredOptionSpec> cliOptions = new TreeSet<>();
		final Set<Class<?>> processedApiNodes = new HashSet<>();
		for (Class<?> apiRoot : relevantBuilderRootApi) {
			generateOptionsFromBuilderApiChain(apiRoot, processedApiNodes, cliOptions);
		}
		return new ArrayList<>(cliOptions);
	}
	
	private static void generateOptionsFromBuilderApiChain(Class<?> apiNode, Set<Class<?>> processedApiNodes, Set<CliDeclaredOptionSpec> cliOptionsFoundSoFar) {
		Class<?> apiNodeChainClass = apiNode;
		while (apiNodeChainClass != null && apiNodeChainClass.getPackage().getName().contains("org.simplejavamail")) {
			for (Class<?> apiInterface : apiNodeChainClass.getInterfaces()) {
				generateOptionsFromBuilderApi(apiInterface, processedApiNodes, cliOptionsFoundSoFar);
			}
			generateOptionsFromBuilderApi(apiNodeChainClass, processedApiNodes, cliOptionsFoundSoFar);
			apiNodeChainClass = apiNodeChainClass.getSuperclass();
		}
	}
	
	/**
	 * Produces all the --option Picocli-based params for specific API class. <br>
	 * Recursive for returned API class (since builders can return different builders.
	 */
	private static void generateOptionsFromBuilderApi(Class<?> apiNode, Set<Class<?>> processedApiNodes, Set<CliDeclaredOptionSpec> cliOptionsFoundSoFar) {
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
						final boolean methodIsActuallyTheSame = knownOption.getSourceMethod().equals(m);
						if (!methodIsActuallyTheSame) {
							String msg = "@CliOptionNameOverride needed one of the following two methods:%n\t%s%n\t%s%n\t----------";
							throw new AssertionError(format(msg, knownOption.getSourceMethod(), m));
						}
					}
				}
				
				cliOptionsFoundSoFar.add(new CliDeclaredOptionSpec(
						optionName,
						TherapiJavadocHelper.determineCliOptionDescriptions(m),
						getArgumentsForCliOption(m),
						apiNode.getAnnotation(Cli.BuilderApiNode.class).builderApiType(),
						m));
				Class<?> potentialNestedApiNode = m.getReturnType();
				if (potentialNestedApiNode.isAnnotationPresent(Cli.BuilderApiNode.class)) {
					generateOptionsFromBuilderApiChain(potentialNestedApiNode, processedApiNodes, cliOptionsFoundSoFar);
				}
			} else {
				final String reason = (m.isAnnotationPresent(Cli.ExcludeApi.class))
						? "Method excluded for CLI: {}.{}({})"
						: "Method not CLI compatible: {}.{}({})";
				LOGGER.debug(reason, apiNode.getSimpleName(), m.getName(), Arrays.toString(m.getParameterTypes()));
			}
		}
	}

	public static boolean methodIsCliCompatible(Method m) {
		if (!m.getDeclaringClass().isAnnotationPresent(Cli.BuilderApiNode.class) ||
				m.isAnnotationPresent(Cli.ExcludeApi.class) ||
				BeanUtils.isBeanMethod(m, m.getDeclaringClass(), allOf(Visibility.class), true) ||
				MethodUtils.methodHasCollectionParameter(m)) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Class<String>[] stringParameters = new Class[m.getParameterTypes().length];
		Arrays.fill(stringParameters, String.class);
		return MethodUtils.isMethodCompatible(m, allOf(LookupMode.class), stringParameters);
	}
	
	@NotNull
	public static List<String> colorizeDescriptions(List<String> descriptions) {
		List<String> colorizedDescriptions = new ArrayList<>();
		for (String description : descriptions) {
			colorizedDescriptions.add(colorizeOptionsInText(description, CliColorScheme.OPTION_STYLE));
		}
		return colorizedDescriptions;
	}
	
	@NotNull
	public static String colorizeOptionsInText(String text, String ansiStyles) {
		final StringFormatter TOKEN_REPLACER = StringFormatter.formatterForPattern("@|" + ansiStyles + " %s|@");
		final String optionRegex = "(?:--(?:help|version)|-(?:h|v)|(?:--?\\w+:\\w+))(?!\\w)"; // https://regex101.com/r/SOs17K/4
		return StringUtil.replaceNestedTokens(text, 0, "@|", "|@", optionRegex, TOKEN_REPLACER);
	}
	
	@NotNull
	public static String determineCliOptionName(Class<?> apiNode, Method m) {
		String methodName = m.isAnnotationPresent(Cli.OptionNameOverride.class)
				? m.getAnnotation(Cli.OptionNameOverride.class).value()
				: m.getName();

		final String cliCommandPrefix = apiNode.getAnnotation(Cli.BuilderApiNode.class).builderApiType().getParamPrefix();
		assumeTrue(!cliCommandPrefix.isEmpty(), "Option prefix missing from API class");
		return format("--%s:%s", cliCommandPrefix, methodName);
	}
	
	@NotNull
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
			cliParams.add(new CliDeclaredOptionValue(dP.getName(), determineTypeLabel(p), javadocDescription, required, javadocExamples));
		}
		
		return cliParams;
	}
	
	@NotNull
	static String extractJavadocDescription(String javadoc) {
		return javadoc.substring(0, determineJavadocLengthUntilExamples(javadoc, false));
	}
	
	@NotNull
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

	@NotNull
	private static String determineTypeLabel(Class<?> type) {
		return checkNonEmptyArgument(TYPE_LABELS.get(type), "Missing type label for type " + type);
	}
}
