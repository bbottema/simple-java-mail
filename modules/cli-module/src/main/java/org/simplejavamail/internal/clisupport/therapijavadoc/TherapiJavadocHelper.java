package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.InlineLink;
import com.github.therapi.runtimejavadoc.Link;
import com.github.therapi.runtimejavadoc.MethodJavadoc;
import com.github.therapi.runtimejavadoc.ParamJavadoc;
import com.github.therapi.runtimejavadoc.RuntimeJavadoc;
import com.github.therapi.runtimejavadoc.SeeAlsoJavadoc;
import com.github.therapi.runtimejavadoc.Value;
import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.MethodUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.clisupport.CliDataLocator;
import org.simplejavamail.internal.clisupport.CliSourceApiFingerprint;
import org.simplejavamail.internal.clisupport.serialization.CliMetadataCache;
import org.simplejavamail.internal.clisupport.serialization.SerializationUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.colorizeDescriptions;
import static org.simplejavamail.internal.util.ListUtil.getFirst;
import static org.simplejavamail.internal.util.StringUtil.padRight;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Converts runtime builder-API Javadoc into CLI option descriptions and cross-references.
 * A validated pre-generated cache avoids repeated runtime traversal during normal startup; development generation
 * persists a canonically ordered cache so supported JDKs produce the same artifact.
 */
public final class TherapiJavadocHelper {
	private static final Logger LOGGER = getLogger(TherapiJavadocHelper.class);
	private static final String REGENERATE_METADATA_PROPERTY = "simplejavamail.cli.metadata.regenerate";

	private static final Map<String, MethodJavadoc> THERAPI_CACHE = loadTherapiCache();

	private static Map<String, MethodJavadoc> loadTherapiCache() {
		try {
			final byte[] encodedCache = Boolean.getBoolean(REGENERATE_METADATA_PROPERTY)
					? null : CliDataLocator.readTherapiDataFile();
			if (encodedCache != null) {
				try {
					final byte[] payload = CliMetadataCache.unwrap(CliMetadataCache.Kind.THERAPI_JAVADOC,
							CliSourceApiFingerprint.calculate(), encodedCache);
					return SerializationUtil.deserialize(payload);
				} catch (RuntimeException e) {
					LOGGER.warn("Could not use therapi.data; regenerating the internal cache: {}", e.getMessage());
					return new HashMap<>();
				}
			}
			return new HashMap<>();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static void persistCache() {
		try {
			// Reflection order is not specified and differs between supported JDKs. Persist a
			// canonical key order so Java 17 and newer JDKs produce byte-identical caches.
			final byte[] payload = SerializationUtil.serialize(new TreeMap<>(THERAPI_CACHE));
			CliDataLocator.persistTherapiDataFile(CliMetadataCache.wrap(CliMetadataCache.Kind.THERAPI_JAVADOC,
					CliSourceApiFingerprint.calculate(), payload));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private TherapiJavadocHelper() {
	}
	
	@Nullable
	static Method findMethodForLink(Link link) {
		if (link.getReferencedMemberName() != null) {
			final Class<?> aClass = findReferencedClass(link.getReferencedClassName());
			if (aClass != null) { // else assume the class is irrelevant to CLI
				final Set<Method> matchingMethods = MethodUtils.findMatchingMethods(aClass, aClass, link.getReferencedMemberName(), link.getParams());
				return matchingMethods.isEmpty() ? null : matchingMethods.iterator().next();
			}
		}
		return null;
	}

	@Nullable
	static Object resolveFieldForValue(Value value) {
		final Class<?> aClass = findReferencedClass(value.getReferencedClassName());
		if (aClass != null) { // else assume the class is irrelevant to CLI
			return ClassUtils.solveFieldValue(aClass, value.getReferencedMemberName());
		}
		return null;
	}

	@Nullable
	private static Class<?> findReferencedClass(String referencedClassName) {
		return ofNullable(ClassUtils.locateClass(referencedClassName, "org.simplejavamail", null))
				.orElseGet(() -> ClassUtils.locateClass(referencedClassName, null, null));
	}

	@NotNull
	static String getJavadocMainDescription(Method m, int nestingDepth) {
		return new JavadocForCliFormatter(nestingDepth)
				.format(getMethodJavadocCached(m).getComment());
	}
	
	@NotNull
	public static List<DocumentedMethodParam> getParamDescriptions(Method m) {
		List<ParamJavadoc> params = getMethodJavadocCached(m).getParams();
		if (m.getParameterTypes().length != params.size()) {
			throw new AssertionError("Number of documented parameters doesn't match with Method's actual parameters: " + m);
		}
		List<DocumentedMethodParam> paramDescriptions = new ArrayList<>();
		for (ParamJavadoc param : params) {
			paramDescriptions.add(new DocumentedMethodParam(param.getName(), new JavadocForCliFormatter().format(param.getComment())));
		}
		return paramDescriptions;
	}

	@NotNull
	public static List<String> getJavadocSeeAlsoReferences(Method m, boolean onlyIncludeClicompatibleJavadocLinks, int maxTextWidth) {
		List<String> seeAlsoReferences = new ArrayList<>();
		final JavadocForCliFormatter cliFormatter = new JavadocForCliFormatter();
		
		// javadoc links are aligned: descriptions are leftpadded to the longest javadoc link or all moved to new lines
		int longestLink = 0;
		boolean allDescriptionsOnNextLine = false;
		
		for (SeeAlsoJavadoc seeAlsoJavadoc : getMethodJavadocCached(m).getSeeAlso()) {
			switch (seeAlsoJavadoc.getSeeAlsoType()) {
				case STRING_LITERAL:
					seeAlsoReferences.add(seeAlsoJavadoc.getStringLiteral());
					break;
				case HTML_LINK:
					SeeAlsoJavadoc.HtmlLink htmlLink = seeAlsoJavadoc.getHtmlLink();
					seeAlsoReferences.add(format("%s (%s)", htmlLink.getText(), htmlLink.getLink()));
					break;
				case JAVADOC_LINK:
					final String renderedLink = cliFormatter.renderLink(new InlineLink(seeAlsoJavadoc.getLink()), false);
					if (!onlyIncludeClicompatibleJavadocLinks || renderedLink.contains("--")) { // -- -> cli compatible
						final Method linkedMethod = findMethodForLink(seeAlsoJavadoc.getLink());
						if (linkedMethod != null) {
							final List<String> fullDescription = determineCliOptionDescriptions(linkedMethod);
							final String moreInfix = fullDescription.size() > 1 ? " (...more)" : "";
							String fullSeeAlsoLine = format("[[%s]] - %s %s", renderedLink, getFirst(fullDescription), moreInfix);
							seeAlsoReferences.add(fullSeeAlsoLine);
							allDescriptionsOnNextLine |= fullSeeAlsoLine.length() > maxTextWidth;
							longestLink = Math.max(longestLink, renderedLink.length()); // keep track of padding needed lateron
						} else {
							seeAlsoReferences.add(renderedLink);
						}
					}
					break;
			}
		}
		
		// add padding for readability
		if (longestLink > 0) {
			for (int i = 0; i < seeAlsoReferences.size(); i++) {
				Matcher matcher = compile("\\[\\[(?<link>.+?)]](?<description>.*)").matcher(seeAlsoReferences.get(i));
				if (matcher.find()) {
					String newlineFixer = seeAlsoReferences.size() > 1 && allDescriptionsOnNextLine ? "\n\t" : "";
					String paddedReplacement = padRight(matcher.group("link"), longestLink) + newlineFixer + matcher.group("description");
					seeAlsoReferences.set(i, matcher.replaceFirst(paddedReplacement));
				}
			}
		}
		
		return seeAlsoReferences;
	}

	@NotNull
	private static MethodJavadoc getMethodJavadocCached(final Method m) {
		return THERAPI_CACHE.computeIfAbsent(m.toString(), methodKey -> RuntimeJavadoc.getJavadoc(m));
	}

	@NotNull
	public static List<String> determineCliOptionDescriptions(Method m) {
		String javadoc = TherapiJavadocHelper.getJavadocMainDescription(m, 0);
		// Picocli takes the first item for --help, but all items for full usage display
		List<String> basicExplanationPlusFurtherDetails = asList(javadoc.split("\n", 2));
		return colorizeDescriptions(basicExplanationPlusFurtherDetails);
	}

	/** Pairs a builder parameter name with the Javadoc text rendered for CLI help. */
	public static class DocumentedMethodParam {
		@NotNull private final String name;
		@NotNull private final String javadoc;
		
		DocumentedMethodParam(@NotNull String name, @NotNull String javadoc) {
			this.name = name;
			this.javadoc = javadoc;
		}
		@NotNull public String getName() { return name; }
		@NotNull public String getJavadoc() { return javadoc; }
	}
}
