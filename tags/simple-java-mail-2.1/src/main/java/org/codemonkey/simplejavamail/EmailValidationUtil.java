/*
 * Copyright 2008 Les Hazlewood Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and limitations under the
 * License.
 */
package org.codemonkey.simplejavamail;

import java.util.regex.Pattern;

/**
 * Validates an email address according to <a href="http://www.ietf.org/rfc/rfc2822.txt">RFC 2822</a>, using regular expressions.
 * <p>
 * From the original author: <br />
 * <blockquote> If you use this code, please keep the author information in tact and reference my site at <a
 * href="http://www.leshazlewood.com">leshazlewood.com</a>. Thanks! </blockquote>
 * <p>
 * Code sanitized by Benny Bottema (kept validation 100% in tact).
 * 
 * @author Les Hazlewood, Benny Bottema
 * @see EmailAddressValidationCriteria
 */
public final class EmailValidationUtil {

	/**
	 * Private constructor; this is a utility class with static methods only, not designed for extension.
	 */
	private EmailValidationUtil() {
		//
	}

	/**
	 * Validates an e-mail with default validation flags that remains <code>true</code> to RFC 2822. This means allowing both domain
	 * literals and quoted identifiers.
	 * 
	 * @param email A complete email address.
	 * @return Whether the e-mail address is compliant with RFC 2822.
	 * @see EmailAddressValidationCriteria#EmailAddressValidationCriteria(boolean, boolean)
	 */
	public static boolean isValid(final String email) {
		return isValid(email, new EmailAddressValidationCriteria(true, true));
	}

	/**
	 * Validates an e-mail with given validation flags.
	 * 
	 * @param email A complete email address.
	 * @param emailAddressValidationCriteria A set of flags that restrict or relax RFC 2822 compliance.
	 * @return Whether the e-mail address is compliant with RFC 2822, configured using the passed in {@link EmailAddressValidationCriteria}.
	 * @see EmailAddressValidationCriteria#EmailAddressValidationCriteria(boolean, boolean)
	 */
	public static boolean isValid(final String email, final EmailAddressValidationCriteria emailAddressValidationCriteria) {
		return buildValidEmailPattern(emailAddressValidationCriteria).matcher(email).matches();
	}

	protected static Pattern buildValidEmailPattern(EmailAddressValidationCriteria parameterObject) {
		// RFC 2822 2.2.2 Structured Header Field Bodies
		final String wsp = "[ \\t]"; // space or tab
		final String fwsp = wsp + "*";
		// RFC 2822 3.2.1 Primitive tokens
		final String dquote = "\\\"";
		// ASCII Control characters excluding white space:
		final String noWsCtl = "\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F";
		// all ASCII characters except CR and LF:
		final String asciiText = "[\\x01-\\x09\\x0B\\x0C\\x0E-\\x7F]";
		// RFC 2822 3.2.2 Quoted characters:
		// single backslash followed by a text char
		final String quotedPair = "(\\\\" + asciiText + ")";
		// RFC 2822 3.2.4 Atom:
		final String atext = "[a-zA-Z0-9\\!\\#\\$\\%\\&\\'\\*\\+\\-\\/\\=\\?\\^\\_\\`\\{\\|\\}\\~]";
		final String atom = fwsp + atext + "+" + fwsp;
		final String dotAtomText = atext + "+" + "(" + "\\." + atext + "+)*";
		final String dotAtom = fwsp + "(" + dotAtomText + ")" + fwsp;
		// RFC 2822 3.2.5 Quoted strings:
		// noWsCtl and the rest of ASCII except the doublequote and backslash characters:
		final String qtext = "[" + noWsCtl + "\\x21\\x23-\\x5B\\x5D-\\x7E]";
		final String qcontent = "(" + qtext + "|" + quotedPair + ")";
		final String quotedString = dquote + "(" + fwsp + qcontent + ")*" + fwsp + dquote;
		// RFC 2822 3.2.6 Miscellaneous tokens
		final String word = "((" + atom + ")|(" + quotedString + "))";
		final String phrase = word + "+"; // one or more words.
		// RFC 1035 tokens for domain names:
		final String letter = "[a-zA-Z]";
		final String letDig = "[a-zA-Z0-9]";
		final String letDigHyp = "[a-zA-Z0-9-]";
		final String rfcLabel = letDig + "(" + letDigHyp + "{0,61}" + letDig + ")?";
		final String rfc1035DomainName = rfcLabel + "(\\." + rfcLabel + ")*\\." + letter + "{2,6}";
		// RFC 2822 3.4 Address specification
		// domain text - non white space controls and the rest of ASCII chars not including [, ], or \:
		final String dtext = "[" + noWsCtl + "\\x21-\\x5A\\x5E-\\x7E]";
		final String dcontent = dtext + "|" + quotedPair;
		final String domainLiteral = "\\[" + "(" + fwsp + dcontent + "+)*" + fwsp + "\\]";
		final String rfc2822Domain = "(" + dotAtom + "|" + domainLiteral + ")";
		final String domain = parameterObject.isAllowDomainLiterals() ? rfc2822Domain : rfc1035DomainName;
		final String localPart = "((" + dotAtom + ")|(" + quotedString + "))";
		final String addrSpec = localPart + "@" + domain;
		final String angleAddr = "<" + addrSpec + ">";
		final String nameAddr = "(" + phrase + ")?" + fwsp + angleAddr;
		final String mailbox = nameAddr + "|" + addrSpec;
		// now compile a pattern for efficient re-use:
		// if we're allowing quoted identifiers or not:
		final String patternString = parameterObject.isAllowQuotedIdentifiers() ? mailbox : addrSpec;
		return Pattern.compile(patternString);
	}
}