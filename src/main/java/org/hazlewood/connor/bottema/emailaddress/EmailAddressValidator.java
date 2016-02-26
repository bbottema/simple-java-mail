/*
 * RFC2822 email address parsing and extraction, some header verification.
 * <p/>
 * Validates an email address according to <a href="http://www.ietf.org/rfc/rfc2822.txt">RFC 2822</a>, using regular expressions.
 *
 * @author Les Hazlewood, Casey Connor, Benny Bottema
 */
package org.hazlewood.connor.bottema.emailaddress;

/*
 * Original code Copyright 2008 Les Hazlewood
 * Original code Copyright 2013-2016 Les Hazlewood, Boxbe, Inc., Casey Connor
 * Original code Copyright 2016 Benny Bottema
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

import java.util.EnumSet;

/**
 * A utility class to parse, clean up, and extract email addresses from messages per RFC2822 syntax. Designed to integrate with Javamail (this class will
 * require that you have a javamail mail.jar in your classpath), but you could easily change the existing methods around to not use Javamail at all. For
 * example, if you're changing the code, see the difference between getInternetAddress and getDomain: the latter doesn't depend on any javamail code. This is
 * all a by-product of what this class was written for, so feel free to modify it to suit your needs.
 * <p/>
 * For real-world addresses, this class is roughly 3-4 times slower than parsing with InternetAddress (although recent versions of this class might be faster),
 * but it can handle a whole lot more. Because of sensible design tradeoffs made in javamail, if InternetAddress has trouble parsing, it might throw an
 * exception, but often it will silently leave the entire original string in the result of ia.getAddress(). This class can be trusted to only provide
 * authenticated results.
 * <p/>
 * This class has been successfully used on many billion real-world addresses, live in production environments, but it's not perfect yet.
 * <p/>
 * Comments/Questions/Corrections welcome: https://github.com/bbottema/email-rfc2822-validator/issues
 * <p/>
 * <hr /> Historie:
 * <p/>
 * Started with code by Les Hazlewood: <a href="http://www.leshazlewood.com">leshazlewood.com</a>.
 * <p/>
 * Modified/added (Casey Connor): removed some functions, added support for CFWS token, corrected FWSP token, added some boolean flags, added getInternetAddress
 * and extractHeaderAddresses and other methods, some optimization.
 * <p/>
 * Modified/added (Benny Bottema): modularized the code and separated configuration, validation and extraction functions. <hr />
 * <p/>
 * Where Mr. Hazlewood's version was more for ensuring certain forms that were passed in during registrations, etc, this handles more types of verifying as well
 * a few forms of extracting the data in predictable, cleaned-up chunks.
 * <p/>
 * Note: CFWS means the "comment folded whitespace" token from 2822, in other words, whitespace and comment text that is enclosed in ()'s.
 * <p/>
 * <b>Limitations</b>: doesn't support nested CFWS (comments within (other) comments), doesn't support mailbox groups except when flat-extracting addresses from
 * headers or when doing verification, doesn't support any of the obs-* tokens. Also: the getInternetAddress and extractHeaderAddresses methods return
 * InternetAddress objects; if the personal name has any quotes or \'s in it at all, the InternetAddress object will always escape the name entirely and put it
 * in quotes, so multiple-token personal names with those characters somewhere in them will always be munged into one big escaped string. This is not really a
 * big deal at all, but I mention it anyway. (And you could get around it by a simple modification to those methods to not use InternetAddress objects.) See the
 * docs of those methods for more info.
 * <p/>
 * Note: Unlike InternetAddress, this class will preserve any RFC-2047-encoding of international characters. Thus doing my_internetaddress.getPersonal() will
 * return the 2047-encoded string, ready for use in an RFC-822-compliant message, whereas the common InternetAddress constructor (when used outside the context
 * of EmailAddressValidator) would return the decoded version of the text, if any was needed. If you need the decoded form, you can do something like this
 * (where ia is the InternetAddress object returned from an EmailAddressValidator method):
 * <p/>
 * ia.setPersonal(javax.mail.internet.MimeUtility.decodeText(ia.getPersonal()));
 * <p/>
 * ...subsequent calls to ia.getPersonal() will then return the decoded text.
 * <p/>
 * Note: This class does not do any header-length-checking. There are no such limitations on the email address grammar in 2822, though email headers in general
 * do have length restrictions. So if the return path is 40000 unfolded characters long, but otherwise valid under 2822, this class will pass it.
 * <p/>
 * Examples of passing (2822-valid) addresses, believe it or not:
 * <p/>
 * <tt>bob @example.com</tt> <BR><tt>&quot;bob&quot;  @  example.com</tt> <BR><tt>bob (comment) (other comment) @example.com (personal name)</tt>
 * <BR><tt>&quot;&lt;bob \&quot; (here) &quot; &lt; (hi there) &quot;bob(the man)smith&quot; (hi) @ (there) example.com (hello) &gt; (again)</tt>
 * <p/>
 * (none of which are permitted by javamail's InternetAddress parsing, incidentally)
 * <p/>
 * By using getInternetAddress(), you can retrieve an InternetAddress object that, when toString()'ed, would reveal that the parser had converted the above
 * into:
 * <p/>
 * <tt>&lt;bob@example.com&gt;</tt> <BR><tt>&lt;bob@example.com&gt;</tt> <BR><tt>&quot;personal name&quot; &lt;bob@example.com&gt;</tt> <BR><tt>&quot;&lt;bob
 * \&quot; (here)&quot; &lt;&quot;bob(the man)smith&quot;@example.com&gt;</tt> <P>(respectively) <P>If parsing headers, however, you'll probably be calling
 * extractHeaderAddresses().
 * <p/>
 * A future improvement may be to use this class to extract info from corrupted addresses, but for now, it does not permit them.
 * <p/>
 * <b>Some of the configuration booleans allow a bit of tweaking already. The source code can be compiled with these booleans in various states. They are
 * configured to what is probably the most commonly-useful state.</b>
 *
 * @author Les Hazlewood, Casey Connor, Benny Bottema
 * @version 1.13 (just regex validation engine)
 */
public final class EmailAddressValidator {

	/**
	 * Private constructor; this is a utility class with static methods only, not designed for extension.
	 */
	private EmailAddressValidator() {
		//
	}

	/**
	 * Validates an e-mail with default validation flags that remains <code>true</code> to RFC 2822.
	 *
	 * @param email A string representing an email addres.
	 * @return Whether the e-mail address is compliant with RFC 2822.
	 * @see EmailAddressCriteria#RFC_COMPLIANT
	 */
	public static boolean isValid(final String email) {
		return isValid(email, EmailAddressCriteria.RFC_COMPLIANT);
	}

	/**
	 * Using the given validation criteria, checks to see if the specified string is a valid email address according to the RFC 2822 specification, which is
	 * remarkably squirrely. See doc for this class: 2822 not fully implemented, but probably close enough for almost any needs. <b>Note that things like
	 * spaces
	 * in addresses ("bob @hi.com") are valid according to 2822! Read the docs for this class before using this method!</b>
	 * <p/>
	 * If being used on a 2822 header, this method applies to Sender, Resent-Sender, <b>only</b>, although you can also use it on the Return-Path if you
	 * know it
	 * to be non-empty (see doc for isValidReturnPath()!). Folded header lines should work OK, but I haven't tested that.
	 *
	 * @param email    A complete email address.
	 * @param criteria A set of criteria flags that restrict or relax RFC 2822 compliance.
	 * @return Whether the e-mail address is compliant with RFC 2822, configured using the passed in {@link EmailAddressCriteria}.
	 * @see EmailAddressCriteria
	 */
	public static boolean isValid(final String email, final EnumSet<EmailAddressCriteria> criteria) {
		return isValidMailbox(email, Dragons.fromCriteria(criteria));
	}

	/**
	 * Checks to see if the specified string is a valid email address according to the RFC 2822 specification, which is remarkably squirrely. See doc for this
	 * class: 2822 not fully implemented, but probably close enough for almost any needs. <b>Note that things like spaces in addresses ("bob @hi.com") are
	 * valid
	 * according to 2822! Read the docs for this class before using this method!</b>
	 * <p/>
	 * If being used on a 2822 header, this method applies to Sender, Resent-Sender, <b>only</b>, although you can also use it on the Return-Path if you
	 * know it
	 * to be non-empty (see doc for isValidReturnPath()!). Folded header lines should work OK, but I haven't tested that.
	 *
	 * @param email   the email address string to test for validity (null and &quot;&quot; OK, will return false for those)
	 * @param dragons the regular expressions compiled using given criteria, used to validate email strings with
	 * @return true if the given email text is valid according to RFC 2822, false otherwise.
	 */
	private static boolean isValidMailbox(String email, Dragons dragons) {
		return (email != null) && dragons.MAILBOX_PATTERN.matcher(email).matches();
	}
}