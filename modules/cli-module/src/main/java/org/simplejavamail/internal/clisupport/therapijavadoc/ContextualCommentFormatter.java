package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentElement;
import com.github.therapi.runtimejavadoc.CommentFormatter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.List;

import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.util.StringUtil.nStrings;

abstract class ContextualCommentFormatter extends CommentFormatter {
	
	final int currentNestingDepth;
	
	private Comment currentComment;
	
	ContextualCommentFormatter(int currentNestingDepth) {
		this.currentNestingDepth = currentNestingDepth;
	}
	
	@Override
	public String format(Comment comment) {
		currentComment = comment;
		return super.format(comment);
	}
	
	@NotNull
	String indent() {
		return indent(0);
	}
	
	@NotNull
	String indent(int depthModifier) {
		return nStrings(currentNestingDepth + depthModifier, "  ");
	}
	
	@Nullable
	CommentElement getPreviousElement(CommentElement e) {
		final List<CommentElement> elements = currentComment.getElements();
		int currentElementIndex = elements.indexOf(e);
		assumeTrue(currentElementIndex >= 0, "CommentElement instance not found in Comment structure.");
		return currentElementIndex == 0 ? null : currentComment.getElements().get(currentElementIndex - 1);
	}
}