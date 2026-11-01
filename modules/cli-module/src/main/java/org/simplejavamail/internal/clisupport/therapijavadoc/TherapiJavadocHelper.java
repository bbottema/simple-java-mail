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
import org.simplejavamail.internal.clisupport.CliMetadataException;
import org.simplejavamail.internal.clisupport.CliSourceApiFingerprint;
import org.simplejavamail.internal.clisupport.serialization.CliMetadataCache;
import org.simplejavamail.internal.clisupport.serialization.JavadocCacheSerialization;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.colorizeDescriptions;
import static org.simplejavamail.internal.util.ListUtil.getFirst;
import static org.simplejavamail.internal.util.StringUtil.padRight;

/**
 * Converts runtime builder-API Javadoc into CLI option descriptions and cross-references.
 * Normal requests read required, immutable packaged metadata. Only the explicit Maven generation scope reads source Javadoc;
 * its canonically ordered output is independent of runtime request state.
 */
public final class TherapiJavadocHelper {
    private static volatile Map<String, MethodJavadoc> THERAPI_CACHE;
    private static final ThreadLocal<Map<String, MethodJavadoc>> GENERATION_CACHE = new ThreadLocal<>();

    static Map<String, MethodJavadoc> loadTherapiCache() {
        try {
            return loadTherapiCache(CliDataLocator.readTherapiDataFile());
        } catch (IOException e) {
            throw new CliMetadataException("therapi.data", e);
        } catch (CliMetadataException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CliMetadataException("therapi.data", e);
        }
    }

    static Map<String, MethodJavadoc> loadTherapiCache(@Nullable final byte[] encodedCache) {
        try {
            if (encodedCache == null) throw new IllegalStateException("Required resource is missing.");
            final byte[] payload = CliMetadataCache.unwrap(CliMetadataCache.Kind.THERAPI_JAVADOC,
                    CliSourceApiFingerprint.calculate(), encodedCache);
            return Collections.unmodifiableMap(JavadocCacheSerialization.deserialize(payload));
        } catch (RuntimeException e) {
            throw new CliMetadataException("therapi.data", e);
        }
    }

    /** An explicit build gets fresh source-document lookup; ordinary requests cannot enable it through a property. */
    public static GenerationScope beginGeneration() {
        if (GENERATION_CACHE.get() != null) throw new IllegalStateException("A metadata generation scope is already active.");
        final GenerationScope scope = new GenerationScope();
        GENERATION_CACHE.set(scope.documents);
        return scope;
    }

    public static final class GenerationScope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Map<String, MethodJavadoc> documents = new LinkedHashMap<>();
        private boolean closed;

        private GenerationScope() {
        }

        public byte[] serializedJavadoc() {
            requireActive();
            return serializeCache(documents);
        }

        private void requireActive() {
            if (closed || Thread.currentThread() != owner || GENERATION_CACHE.get() != documents) {
                throw new IllegalStateException("Metadata generation must use its active build thread.");
            }
        }

        @Override public void close() {
            if (!closed) {
                requireActive();
                GENERATION_CACHE.remove();
                closed = true;
            }
        }
    }

    private static Map<String, MethodJavadoc> runtimeDocuments() {
        Map<String, MethodJavadoc> current = THERAPI_CACHE;
        if (current == null) {
            synchronized (TherapiJavadocHelper.class) {
                current = THERAPI_CACHE;
                if (current == null) THERAPI_CACHE = current = loadTherapiCache();
            }
        }
        return current;
    }

	static byte[] serializeCache(final Map<String, MethodJavadoc> cache) {
		// Reflection order differs across JDKs; retain canonical ordering inside the integrity envelope.
		final byte[] payload = JavadocCacheSerialization.serialize(new TreeMap<>(cache));
		return CliMetadataCache.wrap(CliMetadataCache.Kind.THERAPI_JAVADOC,
				CliSourceApiFingerprint.calculate(), payload);
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
		final Map<String, MethodJavadoc> generation = GENERATION_CACHE.get();
        if (generation != null) return generation.computeIfAbsent(m.toString(), methodKey -> RuntimeJavadoc.getJavadoc(m));
        final MethodJavadoc document = runtimeDocuments().get(m.toString());
        if (document == null) {
            throw new CliMetadataException("therapi.data", new IllegalStateException("Required method documentation is missing: " + m));
        }
        return document;
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
