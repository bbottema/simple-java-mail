package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.CommentFormatter;
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper;

import java.lang.reflect.Method;

public class CommentForCliFormatter extends CommentFormatter {
	@Override
	protected String renderText(CommentText text) {
		return text.getValue()
				.replaceAll("\\s*\\n\\s*", " ") // removes newlines
				.replaceAll("<br\\s*?\\/?>", "\n") // replace <br/> with newlines
				.replaceAll("<p\\s*?\\/?>", "\n\n") // replace <p> with sets of newlines
				.replaceAll("<strong>(.*?)<\\/strong>", "@|bold $1|@")
				.replaceAll("&gt;", ">")
				.replaceAll("&lt;", "<")
				.replaceAll("\\{@code (.*?)}", "@|green $1|@");
	}
	
	@Override
	protected String renderLink(InlineLink e) {
		Method m = TherapiJavadocHelper.findMethodForLink(e.getLink());
		if (m != null && BuilderApiToPicocliCommandsMapper.methodIsCliCompatible(m)) {
			String optionName = BuilderApiToPicocliCommandsMapper.determineCliOptionName(m.getDeclaringClass(), m);
			return "@|cyan " + optionName + "|@";
		} else {
			return e.getLink().toString().replaceAll("#", ".");
		}
	}
}
