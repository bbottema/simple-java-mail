package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentElement;
import com.github.therapi.runtimejavadoc.CommentFormatter;

import javax.annotation.Nonnull;
import java.util.List;

import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.util.StringUtil.nStrings;

public class ContextualCommentFormatter extends CommentFormatter {
	final String NESTED_DESCRIPTION_INDENT_STR = "  ";
	
	final int nestingDepth;
	
	private Comment currentComment;
	
	ContextualCommentFormatter(int nestingDepth) {
		this.nestingDepth = nestingDepth;
	}
	
	@Override
	public String format(Comment comment) {
		currentComment = comment;
		return super.format(comment);
	}
	
	@Nonnull
	String indent() {
		return indent(0);
	}
	
	@Nonnull
	String indent(int depthModifier) {
		return nStrings(nestingDepth + depthModifier, NESTED_DESCRIPTION_INDENT_STR);
	}
	
	CommentElement getPreviousElement(CommentElement e) {
		final List<CommentElement> elements = currentComment.getElements();
		int currentElementIndex = elements.indexOf(e);
		assumeTrue(currentElementIndex >= 0, "CommentElement instance not found in Comment structure.");
		return currentElementIndex == 0 ? null : currentComment.getElements().get(currentElementIndex - 1);
	}
}