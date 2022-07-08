/*
 * Copyright © 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import com.github.therapi.runtimejavadoc.InlineTag;
import com.github.therapi.runtimejavadoc.InlineValue;

/**
 * This is a (cleaned up) copy of the CommentFormatter from therapi < 0.12.0.
 */
public abstract class CommentFormatter {

    public String format(Comment comment) {
        if (comment == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (CommentElement e : comment) {
            if (e instanceof CommentText) {
                sb.append(renderText((CommentText) e));
            } else if (e instanceof InlineLink) {
                sb.append(renderLink((InlineLink) e));
            } else if (e instanceof InlineValue) {
                sb.append(renderValue((InlineValue) e));
            } else if (e instanceof InlineTag) {
                if (((InlineTag) e).getName().equals("code")) {
                    sb.append(renderCode((InlineTag) e));
                } else if (((InlineTag) e).getName().equals("literal")) {
                    sb.append(SimpleHTMLEscapeUtil.escapeHTML(((InlineTag) e).getValue()));
                } else {
                    sb.append(renderUnrecognizedTag((InlineTag) e));
                }
            } else {
                sb.append(e.toString());
            }
        }

        return sb.toString();
    }

    protected abstract String renderText(CommentText text);
    
    protected abstract String renderLink(InlineLink e);
    
    protected abstract String renderValue(InlineValue e);

    protected abstract String renderCode(InlineTag e);

    protected abstract String renderUnrecognizedTag(InlineTag e);
}