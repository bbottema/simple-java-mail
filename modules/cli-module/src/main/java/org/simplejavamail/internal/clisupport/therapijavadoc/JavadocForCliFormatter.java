package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentElement;
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import com.github.therapi.runtimejavadoc.InlineTag;
import com.github.therapi.runtimejavadoc.InlineValue;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.clisupport.CliColorScheme.CODE_STYLE;
import static org.simplejavamail.internal.clisupport.CliColorScheme.EMPHASIZE_STYLE;
import static org.simplejavamail.internal.clisupport.CliColorScheme.OPTION_STYLE;
import static org.simplejavamail.internal.clisupport.CliColorScheme.STRONG_STYLE;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

public class JavadocForCliFormatter extends ContextualCommentFormatter {
	
	private final List<String> includedReferredDocumentation = new ArrayList<>();
	
	JavadocForCliFormatter() {
		super(0);
	}
	
	JavadocForCliFormatter(int nestingDepth) {
		super(nestingDepth);
	}
	
	@SuppressWarnings("StringConcatenationInLoop")
	@Override
	@NotNull
	public String format(Comment comment) {
		final Pattern PATTERN_JAVADOC_TAG = compile("\\{@\\w+");
		final Pattern PATTERN_HTML_TAG = compile("</?[A-Za-z]+>");
		final Pattern PATTERN_TODO_FIXME = compile("//\\s*?(?:TODO|FIXME)"); // https://regex101.com/r/D79BMs/1

		String result = indent() + removeStructuralHTML(super.format(comment));

		assumeTrue(!PATTERN_JAVADOC_TAG.matcher(result).find() &&
						!PATTERN_HTML_TAG.matcher(result).find() &&
						!PATTERN_TODO_FIXME.matcher(result).find(),
				"Output not properly formatted for CLI usage: \n\t" + result + "\n\t-----------");

		StringBuilder completeResult = new StringBuilder(result);
		for (String includedDocumentation : includedReferredDocumentation) {
			completeResult.append("\n\n").append(indent(1)).append(includedDocumentation);
		}
		return completeResult.toString();
	}
	
	@Override
	protected String renderText(CommentText text) {
		return text.getValue()
				.replaceAll("\\s*\\n\\s*", " ") // removes newlines
				.replaceAll("\\s*<br\\s*/?>\\s*", "\n" + indent()) // replace <br> with newlines
				.replaceAll("\\s*</?p\\s*>\\s*", "\n\n" + indent()) // replace <p> with sets of newlines
				.replaceAll("<strong>(.*?)</strong>", "@|" + STRONG_STYLE + " $1|@")
				.replaceAll("<em>(.*?)</em>", "@|" + EMPHASIZE_STYLE + " $1|@")
				.replaceAll("&gt;", ">")
				.replaceAll("&lt;", "<")
				.replaceAll("\\{@code (.*?)}", formatCode("$1"))
				.replaceAll("<code>(.*?)</code>", formatCode("$1"))
				.replaceAll("<a href=\"(.+?)\">(.+?)</a>", "$2 ($1)")
				.replaceAll("%s", "%%s");
	}

	@Override
	protected String renderCode(InlineTag e) {
		return formatCode(e.getValue().replaceAll("%s", "%%s"));
	}

	@NotNull
	private static String formatCode(String value) {
		return String.format("@|%s %s|@", CODE_STYLE, value);
	}
	
	@Override
	protected String renderLink(InlineLink link) {
		return renderLink(link, true);
	}
	
	String renderLink(InlineLink link, boolean includeReferredDocumentation) {
		final Method m = TherapiJavadocHelper.findMethodForLink(link.getLink());
		
		if (m != null) {
			final Class<?> apiNode = m.getDeclaringClass();
			final boolean isCliCompatible = BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(m).isCompatible();
			String result = (isCliCompatible)
					? String.format("@|%s %s|@", OPTION_STYLE, BuilderApiToPicocliCommandsMapper.determineCliOptionName(apiNode, m))
					: formatMethodReference("java-only method @|italic,faint ", m, "|@");
			if (includeReferredDocumentation) {
				result += (checkIncludeReferredDocumentation(link, m, isCliCompatible) ? " (see below)" : "");
			}
			return result;
		} else {
			return String.format("@|%s %s|@", CODE_STYLE, link.getLink().getLabel()
					.replace('#', '.')
					.replaceAll("^\\.(.*)", "$1")
					.trim());
		}
	}
	
	private boolean checkIncludeReferredDocumentation(InlineLink e, Method methodDelegate, boolean methodDelegateIsCliCompatible) {
		if (previousElementImpliesLinkedJavadocShouldBeIncluded(e)) {
			final Class<?> apiNode = methodDelegate.getDeclaringClass();
			final String inclusionHeader;
			if (methodDelegateIsCliCompatible) {
				inclusionHeader = String.format("@|" + STRONG_STYLE + " -> %s %s|@:%n",
						BuilderApiToPicocliCommandsMapper.determineCliOptionName(apiNode, methodDelegate),
						formatCliOptionValues(BuilderApiToPicocliCommandsMapper.getArgumentsForCliOption(methodDelegate)));
			} else {
				inclusionHeader = formatMethodReference("@|" + STRONG_STYLE + " -> ", methodDelegate, "|@:%n");
			}
			includedReferredDocumentation.add(inclusionHeader + TherapiJavadocHelper.getJavadocMainDescription(methodDelegate, currentNestingDepth + 1));
			return true;
		}
		return false;
	}
	
	private String formatCliOptionValues(List<CliDeclaredOptionValue> argumentsForCliOption) {
		StringBuilder result = new StringBuilder();
		for (CliDeclaredOptionValue v : argumentsForCliOption) {
			result
					.append(v.getName())
					.append("(=")
					.append(v.getHelpLabel())
					.append(") ");
		}
		return result.toString().trim();
		
	}
	
	@NotNull
	private String formatMethodReference(@NotNull String prefix, @NotNull Method m, @NotNull String postfix) {
		return String.format("%s%s(%s)%s", prefix, m.getName(), describeMethodParameterTypes(m), postfix);
	}
	
	@NotNull
	private static String describeMethodParameterTypes(Method deferredMethod) {
		final StringBuilder result = new StringBuilder();
		for (Class<?> parameterType : deferredMethod.getParameterTypes()) {
			result.append((result.length() == 0) ? "" : ", ").append(parameterType.getSimpleName());
		}
		return result.toString();
	}

	private boolean previousElementImpliesLinkedJavadocShouldBeIncluded(CommentElement e) {
		final CommentElement previousElement = getPreviousElement(e);
		if (previousElement instanceof InlineLink) {
			return previousElementImpliesLinkedJavadocShouldBeIncluded(previousElement);
		} else if (previousElement instanceof CommentText) {
			final Pattern WORD_PATTERN = compile("\\w");
			final Pattern PATTERN_ALIAS_FOR = compile("(?i)Alias for:?");
			final Pattern PATTERN_DELEGATES_TO = compile("(?i)(?:delegates|delegating) to:?");
			
			final String trimmedToPlainText = removeStructuralHTML(((CommentText) previousElement).getValue()).trim();
			return PATTERN_DELEGATES_TO.matcher(trimmedToPlainText).find() ||
					PATTERN_ALIAS_FOR.matcher(trimmedToPlainText).find() ||
					(!WORD_PATTERN.matcher(trimmedToPlainText).find() && previousElementImpliesLinkedJavadocShouldBeIncluded(previousElement));
		}
		return false;
	}
	
	@Override
	@NotNull
	protected String renderValue(InlineValue e) {
		Object obj = TherapiJavadocHelper.resolveFieldForValue(e.getValue());
		if (obj != null) {
			return String.format("@|%s %s|@", CODE_STYLE, obj);
		}
		throw new AssertionError("{@value} cannot be resolved");
	}
	
	@Override
	protected String renderUnrecognizedTag(InlineTag e) {
		throw new AssertionError(String.format("Found unsupported tag: %s=%s", e.getName(), e.getValue()));
	}
	
	@NotNull
	private String removeStructuralHTML(@NotNull String textWithHtml) {
		return textWithHtml
				.replaceAll("<li>", "\n  - " + indent())
				.replaceAll("</li>", "")
				.replaceAll("</?[ou]l>", "");
	}
}