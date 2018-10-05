package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentElement;
import com.github.therapi.runtimejavadoc.CommentFormatter;
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import com.github.therapi.runtimejavadoc.InlineTag;
import com.github.therapi.runtimejavadoc.InlineValue;
import org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

public class CommentForCliFormatter extends CommentFormatter {
	
	private static final Pattern PATTERN_JAVADOC_TAG = compile("\\{@\\w+");
	private static final Pattern PATTERN_HTML_TAG = compile("</?[A-Za-z]+>");
	private static final Pattern PATTERN_DELEGATES_TO = compile("(?i)Delegates to:?");
	private static final Pattern PATTERN_ALIAS_FOR = compile("(?i)Alias for:?");
	private final Pattern WORD_PATTERN = compile("\\w");

	private Comment currentComment;

	@Override
	public String format(Comment comment) {
		currentComment = comment;

		final String result = removeStructuralHTML(super.format(comment));
		assumeTrue(!PATTERN_JAVADOC_TAG.matcher(result).find() && !PATTERN_HTML_TAG.matcher(result).find(),
				"Output not properly formatted for CLI usage: \n\t" + result + "\n\t-----------");
		return result;
	}

	@Override
	protected String renderText(CommentText text) {
		return text.getValue()
				.replaceAll("\\s*\\n\\s*", " ") // removes newlines
				.replaceAll("<br\\s*?/?>", "\n") // replace <br/> with newlines
				.replaceAll("<p\\s*?/?>", "\n\n") // replace <p> with sets of newlines
				.replaceAll("<strong>(.*?)</strong>", "@|bold $1|@")
				.replaceAll("<em>(.*?)</em>", "@|italic $1|@")
				.replaceAll("&gt;", ">")
				.replaceAll("&lt;", "<")
				.replaceAll("\\{@code (.*?)}", "@|green $1|@")
				.replaceAll("<code>(.*?)</code>", "@|green $1|@")
				.replaceAll("<a href=\"(.+?)\">(.+?)</a>", "$1 (see $2)")
				.replaceAll("%s", "%%s");
	}
	
	@Override
	protected String renderCode(InlineTag e) {
		return String.format("@|green %s|@", e.getValue());
	}
	
	@Override
	protected String renderLink(InlineLink e) {
		Method m = TherapiJavadocHelper.findMethodForLink(e.getLink(), false);
		if (m != null) {
			if (BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(m)) {
				String optionName = BuilderApiToPicocliCommandsMapper.determineCliOptionName(m.getDeclaringClass(), m);
				return "@|cyan " + optionName + "|@";
			}
			if (previousElementImpliesLinkedJavadocShouldBeIncluded(e)) {
				return TherapiJavadocHelper.getJavadoc(m);
			}
		}
		return '"' + e.getLink().getReferencedMemberName() + '"';
	}

	private boolean previousElementImpliesLinkedJavadocShouldBeIncluded(CommentElement e) {
		final CommentElement previousElement = getPreviousElement(e);
		if (previousElement instanceof InlineLink) {
			return previousElementImpliesLinkedJavadocShouldBeIncluded(previousElement);
		} else if (previousElement instanceof CommentText) {
			final String trimmedToPlainText = removeStructuralHTML(((CommentText) previousElement).getValue()).trim();
			return PATTERN_DELEGATES_TO.matcher(trimmedToPlainText).find() ||
					PATTERN_ALIAS_FOR.matcher(trimmedToPlainText).find() ||
					(!WORD_PATTERN.matcher(trimmedToPlainText).find() && previousElementImpliesLinkedJavadocShouldBeIncluded(previousElement));
		}
		return false;
	}

	private CommentElement getPreviousElement(CommentElement e) {
		final List<CommentElement> elements = currentComment.getElements();
		int currentElementIndex = elements.indexOf(e);
		assumeTrue(currentElementIndex >= 0, "CommentElement instance not found in Comment structure.");
		return currentElementIndex == 0 ? null : currentComment.getElements().get(currentElementIndex - 1);
	}

	@Override
	protected String renderValue(InlineValue e) {
		Object obj = TherapiJavadocHelper.resolveFieldForValue(e.getValue());
		if (obj != null) {
			return obj.toString();
		}
		throw new RuntimeException("{@value} cannot be resolved");
	}
	
	@Override
	protected String renderUnrecognizedTag(InlineTag e) {
		throw new RuntimeException(String.format("Found unsupported tag: %s=%s", e.getName(), e.getValue()));
	}

	private String removeStructuralHTML(@Nonnull String textWithHtml) {
		return textWithHtml
				.replaceAll("(?s)<li>(.*?)</li>", "$1\n")
				.replaceAll("(?s)<ul>(.*?)</ul>", "$1")
				.replaceAll("(?s)<ol>(.*?)</ol>", "$1");
	}
}