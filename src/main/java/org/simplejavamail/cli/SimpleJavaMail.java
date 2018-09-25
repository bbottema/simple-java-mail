package org.simplejavamail.cli;

import com.github.therapi.runtimejavadoc.ClassJavadoc;
import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentFormatter;
import com.github.therapi.runtimejavadoc.MethodJavadoc;
import com.github.therapi.runtimejavadoc.OtherJavadoc;
import com.github.therapi.runtimejavadoc.ParamJavadoc;
import com.github.therapi.runtimejavadoc.RuntimeJavadoc;
import com.github.therapi.runtimejavadoc.SeeAlsoJavadoc;
import com.github.therapi.runtimejavadoc.ThrowsJavadoc;
import org.simplejavamail.internal.clisupport.CliSupport;

import java.io.IOException;

public class SimpleJavaMail {
	public static void main(String[] args) throws IOException {
		// FIXME load class dynamically so cli dependency can be optional
		CliSupport.runCLI(args);
//		printJavadoc(EmailPopulatingBuilder.class.getName());
	}
	
	// formatters are reusable and thread-safe
	private static final CommentFormatter formatter = new CommentFormatter();
	
	public static void printJavadoc(String fullyQualifiedClassName) throws IOException {
		ClassJavadoc classDoc = RuntimeJavadoc.getJavadoc(fullyQualifiedClassName);
		if (classDoc == null) {
			System.out.println("no documentation for " + fullyQualifiedClassName);
			return;
		}
		
		System.out.println(classDoc.getName());
		System.out.println(format(classDoc.getComment()));
		System.out.println();
		
		// @see tags
		for (SeeAlsoJavadoc see : classDoc.getSeeAlso()) {
			System.out.println("See also: " + see.getLink());
		}
		// miscellaneous and custom javadoc tags (@author, etc.)
		for (OtherJavadoc other : classDoc.getOther()) {
			System.out.println(other.getName() + ": " + format(other.getComment()));
		}
		
		System.out.println();
		System.out.println("METHODS");
		
		for (MethodJavadoc methodDoc : classDoc.getMethods()) {
			System.out.println(methodDoc.getName() + methodDoc.getParamTypes());
			System.out.println(format(methodDoc.getComment()));
//			System.out.println("-----------------------------");
//			for (CommentElement ele : methodDoc.getComment().getElements()) {
//				switch (ele.getClass().getSimpleName().toString()) {
//					case "CommentText":
//						System.out.println("CommentText: " + ((CommentText) ele).getValue());
//						break;
//					case "InlineLink":
//						InlineLink link = (InlineLink) ele;
//						System.out.println("InlineLink: " + link.getLink());
//						break;
//					case "InlineTag":
//						InlineTag tag = (InlineTag) ele;
//						System.out.println("InlineTag: " + tag.getName() + ": " + tag.getValue());
//						break;
//				}
//			}
//			System.out.println("-----------------------------");
			System.out.println("  returns " + format(methodDoc.getReturns()));
			
			for (SeeAlsoJavadoc see : methodDoc.getSeeAlso()) {
				System.out.println("  See also: " + see.getLink());
			}
			for (OtherJavadoc other : methodDoc.getOther()) {
				System.out.println("  " + other.getName() + ": " + format(other.getComment()));
			}
			for (ParamJavadoc paramDoc : methodDoc.getParams()) {
				System.out.println("  param " + paramDoc.getName() + " " + format(paramDoc.getComment()));
			}
			for (ThrowsJavadoc throwsDoc : methodDoc.getThrows()) {
				System.out.println("  throws " + throwsDoc.getName() + " " + format(throwsDoc.getComment()));
			}
			System.out.println();
		}
	}
	
	private static String format(Comment c) {
		return formatter.format(c);
	}
}
