package org.hazlewood.connor.bottema.emailaddress;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MY DRAGONS WILL EAT YOUR DRAGONS
 * <p/>
 * Regular expressions based on given list of {@link EmailAddressCriteria}. Used in both validation ({@link EmailAddressValidator}) and email data extraction
 * ({@link EmailAddressParser}).
 */
final class Dragons {

	/**
	 * Java regex pattern for 2822 &quot;mailbox&quot; token; Not necessarily useful, but available in case.
	 */
	public final Pattern MAILBOX_PATTERN;
	/**
	 * Java regex pattern for 2822 &quot;addr-spec&quot; token; Not necessarily useful, but available in case.
	 */
	public final Pattern ADDR_SPEC_PATTERN;
	/**
	 * Java regex pattern for 2822 &quot;mailbox-list&quot; token; Not necessarily useful, but available in case.
	 */
	public final Pattern MAILBOX_LIST_PATTERN;
	//    public static final Pattern ADDRESS_LIST_PATTERN = Pattern.compile(addressList);
	/**
	 * Java regex pattern for 2822 &quot;address&quot; token; Not necessarily useful, but available in case.
	 */
	public final Pattern ADDRESS_PATTERN;
	/**
	 * Java regex pattern for 2822 &quot;comment&quot; token; Not necessarily useful, but available in case.
	 */
	public final Pattern COMMENT_PATTERN;

	public final Pattern QUOTED_STRING_WO_CFWS_PATTERN;
	public final Pattern RETURN_PATH_PATTERN;
	public final Pattern GROUP_PREFIX_PATTERN;

	public final Pattern ESCAPED_QUOTE_PATTERN;
	public final Pattern ESCAPED_BSLASH_PATTERN;

	/**
	 * Very simply cache to avoid recreating dragons all the time.
	 */
	private static final Map<EnumSet<EmailAddressCriteria>, Dragons> cache = new HashMap<EnumSet<EmailAddressCriteria>, Dragons>();

	/**
	 * @return Dragons based on criteria, cached if the criteria have been used before
	 */
	protected static Dragons fromCriteria(EnumSet<EmailAddressCriteria> criteria) {
		if (!cache.containsKey(criteria)) {
			cache.put(criteria, new Dragons(criteria));
		}
		return cache.get(criteria);
	}

	/**
	 * Hatch dragons...
	 */
	private Dragons(EnumSet<EmailAddressCriteria> criteria) {
		// RFC 2822 2.2.2 Structured Header Field Bodies
		final String crlf = "\\r\\n";
		final String wsp = "[ \\t]"; //space or tab
		final String fwsp = "(?:" + wsp + "*" + crlf + ")?" + wsp + "+";

		//RFC 2822 3.2.1 Primitive tokens
		final String dquote = "\\\"";
		//ASCII Control characters excluding white space:
		final String noWsCtl = "\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F";
		//all ASCII characters except CR and LF:
		final String asciiText = "[\\x01-\\x09\\x0B\\x0C\\x0E-\\x7F]";

		// RFC 2822 3.2.2 Quoted characters:
		//single backslash followed by a text char
		final String quotedPair = "(?:\\\\" + asciiText + ")";

		// RFC 2822 3.2.3 CFWS specification
		// note: nesting should be permitted but is not by these rules given code limitations:

		// rewritten to be shorter:
		//final String ctext = "[" + noWsCtl + "\\x21-\\x27\\x2A-\\x5B\\x5D-\\x7E]";
		final String ctext = "[" + noWsCtl + "\\!-\\'\\*-\\[\\]-\\~]";
		final String ccontent = ctext + "|" + quotedPair; // + "|" + comment;
		final String comment = "\\((?:(?:" + fwsp + ")?" + ccontent + ")*(?:" + fwsp + ")?\\)";
		final String cfws = "(?:(?:" + fwsp + ")?" + comment + ")*(?:(?:(?:" + fwsp + ")?" + comment + ")|(?:" + fwsp + "))";

		//RFC 2822 3.2.4 Atom:

		final String atext = "[a-zA-Z0-9\\!\\#-\\'\\*\\+\\-\\/\\=\\?\\^-\\`\\{-\\~" + (criteria.contains(EmailAddressCriteria.ALLOW_DOT_IN_A_TEXT) ? "\\." : "") + (criteria
				.contains(EmailAddressCriteria.ALLOW_SQUARE_BRACKETS_IN_A_TEXT) ? "\\[\\]" : "") + "]";
		// regular atext is same as atext but has no . or [ or ] allowed, no matter the class prefs, to prevent
		// long recursions on e.g. "a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p.q.r.s.t"
		final String regularAtext = "[a-zA-Z0-9\\!\\#-\\'\\*\\+\\-\\/\\=\\?\\^-\\`\\{-\\~]";

		final String atom = "(?:" + cfws + ")?" + atext + "+" + "(?:" + cfws + ")?";
		final String dotAtomText = regularAtext + "+" + "(?:" + "\\." + regularAtext + "+)*";
//		final String dotAtom = "(?:" + cfws + ")?" + dotAtomText + "(?:" + cfws + ")?";
		final String capDotAtomNoCFWS = "(?:" + cfws + ")?(" + dotAtomText + ")(?:" + cfws + ")?";
		final String capDotAtomTrailingCFWS = "(?:" + cfws + ")?(" + dotAtomText + ")(" + cfws + ")?";

		//RFC 2822 3.2.5 Quoted strings:
		//noWsCtl and the rest of ASCII except the doublequote and backslash characters:

		final String qtext = "[" + noWsCtl + "\\!\\#-\\[\\]-\\~]";
		final String localPartqtext = "[" + noWsCtl + (criteria.contains(EmailAddressCriteria.ALLOW_PARENS_IN_LOCALPART) ? "\\!\\#-\\[\\]-\\~]" : "\\!\\#-\\'\\*-\\[\\]-\\~]");

		final String qcontent = "(?:" + qtext + "|" + quotedPair + ")";
		final String localPartqcontent = "(?>" + localPartqtext + "|" + quotedPair + ")";
		final String quotedStringWOCFWS = dquote + "(?>(?:" + fwsp + ")?" + qcontent + ")*(?:" + fwsp + ")?" + dquote;
		final String quotedString = "(?:" + cfws + ")?" + quotedStringWOCFWS + "(?:" + cfws + ")?";
		final String localPartQuotedString =
				"(?:" + cfws + ")?(" + dquote + "(?:(?:" + fwsp + ")?" + localPartqcontent + ")*(?:" + fwsp + ")?" + dquote + ")(?:" + cfws + ")?";

		//RFC 2822 3.2.6 Miscellaneous tokens
		final String word = "(?:(?:" + atom + ")|(?:" + quotedString + "))";
		// by 2822: phrase = 1*word / obs-phrase
		// implemented here as: phrase = word (FWS word)*
		// so that aaaa can't be four words, which can cause tons of recursive backtracking
		//final String phrase = "(?:" + word + "+?)"; //one or more words
		final String phrase = word + "(?:(?:" + fwsp + ")" + word + ")*";

		//RFC 1035 tokens for domain names:
		final String letter = "[a-zA-Z]";
		final String letDig = "[a-zA-Z0-9]";
		final String letDigHyp = "[a-zA-Z0-9-]";
		final String rfcLabel = letDig + "(?:" + letDigHyp + "{0,61}" + letDig + ")?";
		final String rfc1035DomainName = rfcLabel + "(?:\\." + rfcLabel + ")*\\." + letter + "{2,6}";

		//RFC 2822 3.4 Address specification
		//domain text - non white space controls and the rest of ASCII chars not
		// including [, ], or \:
		// rewritten to save space:
		//final String dtext = "[" + noWsCtl + "\\x21-\\x5A\\x5E-\\x7E]";
		final String dtext = "[" + noWsCtl + "\\!-Z\\^-\\~]";

		final String dcontent = dtext + "|" + quotedPair;
		final String capDomainLiteralNoCFWS =
				"(?:" + cfws + ")?" + "(\\[" + "(?:(?:" + fwsp + ")?(?:" + dcontent + ")+)*(?:" + fwsp + ")?\\])" + "(?:" + cfws + ")?";
		final String capDomainLiteralTrailingCFWS =
				"(?:" + cfws + ")?" + "(\\[" + "(?:(?:" + fwsp + ")?(?:" + dcontent + ")+)*(?:" + fwsp + ")?\\])" + "(" + cfws + ")?";
		final String rfc2822Domain = "(?:" + capDotAtomNoCFWS + "|" + capDomainLiteralNoCFWS + ")";
		final String capCFWSRfc2822Domain = "(?:" + capDotAtomTrailingCFWS + "|" + capDomainLiteralTrailingCFWS + ")";

		// Les chose to implement the more-strict 1035 instead of just relying on "dot-atom"
		// as would be implied by 2822 without the domain-literal token. The issue is that 2822
		// allows CFWS around the local part and the domain,
		// strange though that may be (and you "SHOULD NOT" put it around the @). This version
		// allows the cfws before and after.
		// final String domain =
		//    ALLOW_DOMAIN_LITERALS ? rfc2822Domain : rfc1035DomainName;
		final String domain = criteria.contains(EmailAddressCriteria.ALLOW_DOMAIN_LITERALS) ? rfc2822Domain : "(?:" + cfws + ")?(" + rfc1035DomainName + ")(?:" + cfws + ")?";
		final String capCFWSDomain = criteria.contains(EmailAddressCriteria.ALLOW_DOMAIN_LITERALS) ?
				capCFWSRfc2822Domain :
				"(?:" + cfws + ")?(" + rfc1035DomainName + ")(" + cfws + ")?";
		final String localPart = "(" + capDotAtomNoCFWS + "|" + localPartQuotedString + ")";
		// uniqueAddrSpec exists so we can have a duplicate tree that has a capturing group
		// instead of a non-capturing group for the trailing CFWS after the domain token
		// that we wouldn't want if it was inside
		// an angleAddr. The matching should be otherwise identical.
		final String addrSpec = localPart + "@" + domain;
		final String uniqueAddrSpec = localPart + "@" + capCFWSDomain;
		final String angleAddr = "(?:" + cfws + ")?<" + addrSpec + ">(" + cfws + ")?";
		// uses a reluctant quantifier to skip ahead and make sure there's actually an
		// address in there somewhere... hmmm, maybe regex in java doesn't optimize that
		// case by skipping over it at the start by default? Doesn't seem to solve the
		// issue of recursion on long strings like [A-Za-z], but issue was solved by
		// changing phrase definition (see above):
		final String nameAddr = "(" + phrase + ")??(" + angleAddr + ")";
		final String mailbox = (criteria.contains(EmailAddressCriteria.ALLOW_QUOTED_IDENTIFIERS) ? "(" + nameAddr + ")|" : "") + "(" + uniqueAddrSpec + ")";

		final String returnPath = "(?:(?:" + cfws + ")?<((?:" + cfws + ")?|" + addrSpec + ")>(?:" + cfws + ")?)";

		final String mailboxList = "(?:(?:" + mailbox + ")(?:,(?:" + mailbox + "))*)";
		final String groupPostfix = "(?:" + cfws + "|(?:" + mailboxList + ")" + ")?;(?:" + cfws + ")?";
		final String groupPrefix = phrase + ":";
		final String group = groupPrefix + groupPostfix;
		final String address = "(?:(?:" + mailbox + ")|(?:" + group + "))";
		// this string is too long, so must do it FSM style in isValidAddressList:
		// private static final String addressList = address + "(?:," + address + ")*";

		// That wasn't so hard...

		// Group IDs:

		// Capturing groups (works with (), ()?, but not ()* if it has nested
		// groups). Many of these are provided for your convenience. As few as one
		// or as many as four would be needed to reconstruct the address.

		// If ALLOW_QUOTED_IDENTIFIERS and ALLOW_DOMAIN_LITERALS:
		// 1: name-addr (inlc angle-addr only)
		//  2: phrase (personal name) of said name-addr (1)
		//  3: angle-addr of said name-addr (1)
		//  4: local-part of said angle-addr (3)
		//  5: non-cfws dot-atom local-part of said angle-addr (3)
		//  6: non-cfws quoted-string local-part of said-angle-addr(3)
		//  7: non-cfws dot-atom domain-part of said angle-addr (3)
		//  8: non-cfws domain-literal domain-part of said angle-addr (3)
		//  9: any CFWS that follows said angle-address (3)
		// 10: addr-spec only
		//  11: local-part of said addr-spec (10)
		//  12: non-cfws dot-atom local-part of said addr-spec (10)
		//  13: non-cfws quoted-string local-part of said addr-spec (10)
		//  14: non-cfws dot-atom domain-part of said addr-spec (10)
		//  15: non-cfws domain-literal domain-part of said addr-spec (10)
		//  16: any CFWS that follows (14) or (15)
		// if name-addr: addr w/o CFWS is part (5|6) + "@" + (7|8), personal name is part (2|9)
		// if addr-spec: addr w/o CFWS is part (12|13) + "@" + (14|15), personal name is part (16)

		// If ALLOW_QUOTED_IDENTIFIERS and !ALLOW_DOMAIN_LITERALS:
		// 1: name-addr (inlc angle-addr only)
		//  2: phrase (personal name) of said name-addr (1)
		//  3: angle-addr of said name-addr (1)
		//  4: local-part of said angle-addr (3)
		//  5: non-cfws dot-atom local-part of said angle-addr (3)
		//  6: non-cfws quoted-string local-part of said-angle-addr(3)
		//  7: non-cfws domain-part (always dot-atom) of said angle-addr (3)
		//  8: any CFWS that follows said angle-address (3)
		// 9: addr-spec only
		//  10: local-part of said addr-spec (9)
		//  11: non-cfws dot-atom local-part of said addr-spec (9)
		//  12: non-cfws quoted-string local-part of said addr-spec (9)
		//  13: non-cfws domain-part of said addr-spec (9)
		//  14: any CFWS that follows (12) or (13)
		// if name-addr: addr w/o CFWS is part (5|6) + "@" + 7, personal name is part (2|8)
		// if addr-spec: addr w/o CFWS is part (11|12) + "@" + 13, personal name is part (14)

		// If !ALLOW_QUOTED_IDENTIFIERS and !ALLOW_DOMAIN_LITERALS:
		// 1: addr-spec only
		//  2: local-part of said addr-spec (1)
		//   3: non-cfws dot-atom local-part of said addr-spec (1)
		//   4: non-cfws quoted-string local-part of said addr-spec (1)
		//   5: non-cfws domain-part (always dot-atom) of said addr-spec (1)
		//   6: any CFWS that follows (5)
		// addr w/o CFWS is part (3|4) + "@" + 5, personal name is (6)

		// If !ALLOW_QUOTED_IDENTIFIERS and ALLOW_DOMAIN_LITERALS:
		// 1: addr-spec only
		//  2: local-part of said addr-spec (1)
		//   3: non-cfws dot-atom local-part of said addr-spec (1)
		//   4: non-cfws quoted-string local-part of said addr-spec (1)
		//   5: non-cfws dot-atom domain-part of said addr-spec (1)
		//   6: non-cfws domain-literal domain-part of said addr-spec (1)
		//   7: any CFWS that follows (5) or (6)
		// addr w/o CFWS is part (3|4) + "@" + (5|6), personal name is part (7)

		// For RETURN_PATH_PATTERN, there is one matching group at the head of the
		// group ID tree that matches the content inside the angle brackets (including
		// CFWS, etc.: Group 1.

		// optimize: could pre-make matchers as well and use reset(s) on them?

		//compile patterns for efficient re-use:

		/**
		 * Java regex pattern for 2822 &quot;mailbox&quot; token; Not necessarily useful, but available in case.
		 */
		MAILBOX_PATTERN = Pattern.compile(mailbox);
		/**
		 * Java regex pattern for 2822 &quot;addr-spec&quot; token; Not necessarily useful, but available in case.
		 */
		ADDR_SPEC_PATTERN = Pattern.compile(addrSpec);
		/**
		 * Java regex pattern for 2822 &quot;mailbox-list&quot; token; Not necessarily useful, but available in case.
		 */
		MAILBOX_LIST_PATTERN = Pattern.compile(mailboxList);
		//    public static final Pattern ADDRESS_LIST_PATTERN = Pattern.compile(addressList);
		/**
		 * Java regex pattern for 2822 &quot;address&quot; token; Not necessarily useful, but available in case.
		 */
		ADDRESS_PATTERN = Pattern.compile(address);
		/**
		 * Java regex pattern for 2822 &quot;comment&quot; token; Not necessarily useful, but available in case.
		 */
		COMMENT_PATTERN = Pattern.compile(comment);

		QUOTED_STRING_WO_CFWS_PATTERN = Pattern.compile(quotedStringWOCFWS);
		RETURN_PATH_PATTERN = Pattern.compile(returnPath);
		GROUP_PREFIX_PATTERN = Pattern.compile(groupPrefix);

		// confused yet? Try this:
		ESCAPED_QUOTE_PATTERN = Pattern.compile("\\\\\"");
		ESCAPED_BSLASH_PATTERN = Pattern.compile("\\\\\\\\");
	}


/* The current regex string for mailbox token, just for fun:
(((?:(?:(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*
(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?[a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+(?:(?:(?:(?:[
\t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[
\t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*
(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)|(?:(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)
?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:
(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[
\t]+)))?\"(?:(?:(?:[ \t]*\r\n)?[ \t]+)?(?:[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!\#-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F])))*(?:(?:[ \t]*\r\n)?[ \t]+)?\"(?:
(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[
\t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?))(?:(?:(?:[ \t]*\r\n)?[ \t]+)(?:(?:(?:(?:(?:(?:[ \t]*\r\n)?[
\t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:
(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[
\t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?[a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)
?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:
(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[
\t]+)))?)|(?:(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)
?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?\"(?:(?:(?:[
\t]*\r\n)?[ \t]+)?(?:[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!\#-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F])))*(?:(?:[ \t]*\r\n)?[ \t]+)?\"(?:(?:(?:(?:[ \t]*\r\n)?[
\t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:
(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[
\t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)))*)??((?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)
?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:
(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[
\t]+)))?<((?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*
(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?([a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+(?:\
.[a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+)*)(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)
?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:
(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[
\t]+)))?|(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*
(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?(\"(?:(?:(?:[ \t]*\r\n)?[ \t]+)?
(?:[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!\#-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F])))*(?:(?:[ \t]*\r\n)?[ \t]+)?\")(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:
(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[
\t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[
\t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)@(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)
?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?([a-zA-Z0-9]
(?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\.[a-zA-Z]{2,6})(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)
?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\(
(?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[
\t]*\r\n)?[ \t]+)))?>((?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)
?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?))|(((?:(?:(?:
(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[
\t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))
*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?([a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+(?:\.[a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+)*)
* (?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[
* \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
* (?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[\t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?|(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[
* \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\
* ((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[
* \t]*\r\n)?[ \t]+)))?(\"(?:(?:(?:[ \t]*\r\n)?[ \t]+)?(?:[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!\#-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F])))*(?:(?:[
* \t]*\r\n)?[ \t]+)?\")(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
* (?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)
* ?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)@(?:(?:
* (?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[
* \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|
* (?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\
* .[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\.[a-zA-Z]{2,6})((?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)
* ?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:
* (?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[
* \t]*\r\n)?[ \t]+)))?)

*/
}
