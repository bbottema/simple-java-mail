package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.CommentFormatter;
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

public class CommentForCliFormatter extends CommentFormatter {
	
	private static final Pattern PATTERN_JAVADOC_TAG = compile("\\{@\\w+");
	private static final Pattern PATTERN_HTML_TAG = compile("</?[A-Za-z]+>");
	
	@Override
	protected String renderText(CommentText text) {
		return checkOutput(text.getValue()
				.replaceAll("\\s*\\n\\s*", " ") // removes newlines
				.replaceAll("<br\\s*?/?>", "\n") // replace <br/> with newlines
				.replaceAll("<p\\s*?/?>", "\n\n") // replace <p> with sets of newlines
				.replaceAll("<strong>(.*?)</strong>", "@|bold $1|@")
				.replaceAll("&gt;", ">")
				.replaceAll("&lt;", "<")
				.replaceAll("\\{@code (.*?)}", "@|green $1|@")
				.replaceAll("<code>(.*?)</code>", "@|green $1|@")
				.replaceAll("<a href=\"(.+?)\">(.+?)</a>", "$1 (see $2)")
				.replaceAll("%s", "%%s"));
	}
	
//	@Override
//	protected String renderCode(InlineTag e) {
//		return checkOutput(String.format("@|green %s|@", e.getValue()));
//	}
	
	private String checkOutput(String output) {
//		assumeTrue(!PATTERN_JAVADOC_TAG.matcher(output).find() && !PATTERN_HTML_TAG.matcher(output).find(),
//				"Output not properly formatted for CLI usage: \n\t" + output + "\n\t-----------");
		return output;
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
}