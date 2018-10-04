package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentFormatter;
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import com.github.therapi.runtimejavadoc.InlineTag;
import com.github.therapi.runtimejavadoc.InlineValue;
import org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

public class CommentForCliFormatter extends CommentFormatter {
	
	private static final Pattern PATTERN_JAVADOC_TAG = compile("\\{@\\w+");
	private static final Pattern PATTERN_HTML_TAG = compile("</?[A-Za-z]+>");
	
	@Override
	public String format(Comment comment) {
		final String result = super.format(comment)
				.replaceAll("(?s)<li>(.*?)</li>", "$1\n")
				.replaceAll("(?s)<ul>(.*?)</ul>", "$1")
				.replaceAll("(?s)<ol>(.*?)</ol>", "$1");
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
		if (m != null && BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(m)) {
			String optionName = BuilderApiToPicocliCommandsMapper.determineCliOptionName(m.getDeclaringClass(), m);
			return "@|cyan " + optionName + "|@";
		} else {
			return e.getLink().toString().replaceAll("#", ".");
		}
	}
	
	@Override
	protected String renderValue(InlineValue e) {
		Object obj = TherapiJavadocHelper.resolveFieldForValue(e.getValue());
		return obj != null ? obj.toString() : e.getValue().toString().replaceAll("#", ".");
	}
	
	@Override
	protected String renderUnrecognizedTag(InlineTag e) {
		throw new RuntimeException(String.format("Found unsupported tag: %s=%s", e.getName(), e.getValue()));
	}
}