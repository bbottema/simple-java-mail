/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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