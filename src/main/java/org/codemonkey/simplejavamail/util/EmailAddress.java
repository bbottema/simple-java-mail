/**
 * Validates an email address according to <a href="http://www.ietf.org/rfc/rfc2822.txt">RFC 2822</a>, using regular expressions.
 * <p>
 * From the original author: <br>
 * <blockquote> If you use this code, please keep the author information in tact and reference my site at <a
 * href="http://www.leshazlewood.com">leshazlewood.com</a>. Thanks! </blockquote>
 * <p>
 * Code sanitized by Benny Bottema (kept validation 100% in tact).
 *
 * @author Les Hazlewood, Casey Connor, Benny Bottema
 * @see EmailAddressValidationCriteria
 */
package org.codemonkey.simplejavamail.util;

/*
 * Original code Copyright 2013-2016 Les Hazlewood, Boxbe, Inc., Casey Connor
 * EmailAddress.java
 * <P>
 * RFC2822 email address parsing and extraction, some header verification.
 */

/*
 * Original code Copyright 2008 Les Hazlewood
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

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import javax.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;

/**
 * EmailAddress.java
 * <P>
 * A utility class to parse, clean up, and extract email addresses from messages
 * per RFC2822 syntax. Designed to integrate with Javamail (this class will require that you
 * have a javamail mail.jar in your classpath), but you could easily change
 * the existing methods around to not use Javamail at all. For example, if you're changing
 * the code, see the difference between getInternetAddress and getDomain: the latter doesn't
 * depend on any javamail code. This is all a by-product of what this class was written for,
 * so feel free to modify it to suit your needs.
 * <P>
 * For real-world addresses, this class is roughly 3-4 times slower than parsing with
 * InternetAddress (although recent versions of this class might be faster), but
 * it can handle a whole lot more. Because of sensible design tradeoffs made in javamail, if
 * InternetAddress has trouble parsing,
 * it might throw an exception, but often it will silently leave the entire original string
 * in the result of ia.getAddress(). This class can be trusted to only provide authenticated
 * results.
 * <P>
 * This class has been successfully used on many billion real-world addresses, live in
 * production environments, but it's not perfect yet.
 * <P>
 * Comments/Questions/Corrections welcome: java &lt;at&gt; caseyconnor.org
 * <P>
 * Started with code by Les Hazlewood:
 * <a href="http://www.leshazlewood.com">leshazlewood.com</a>.
 * <P>
 * Modified/added: removed some functions, added support for CFWS token,
 * corrected FWSP token, added some boolean flags, added getInternetAddress and
 * extractHeaderAddresses and other methods, some optimization.
 * <P>
 * Where Mr. Hazlewood's version was more for ensuring certain forms that were passed in during
 * registrations, etc, this handles more types of verifying as well a few forms of extracting
 * the data in predictable, cleaned-up chunks.
 * <P>
 * Note: CFWS means the "comment folded whitespace" token from 2822, in other words,
 * whitespace and comment text that is enclosed in ()'s.
 * <P>
 * <b>Limitations</b>: doesn't support nested CFWS (comments within (other) comments), doesn't
 * support mailbox groups except when flat-extracting addresses from headers or when doing
 * verification, doesn't support
 * any of the obs-* tokens. Also: the getInternetAddress and
 * extractHeaderAddresses methods return InternetAddress objects; if the personal name has
 * any quotes or \'s in it at all, the InternetAddress object will always
 * escape the name entirely and put it in quotes, so
 * multiple-token personal names with those characters somewhere in them will always be munged
 * into one big escaped string. This is not really a big deal at all, but I mention it anyway.
 * (And you could get around it by a simple modification to those methods to not use
 * InternetAddress objects.) See the docs of those methods for more info.
 * <P>
 * Note: Unlike InternetAddress, this class will preserve any RFC-2047-encoding of international
 * characters. Thus doing my_internetaddress.getPersonal() will return the 2047-encoded string,
 * ready for use in an RFC-822-compliant message,
 * whereas the common InternetAddress constructor (when used outside the context of
 * EmailAddress) would return the decoded version of the text, if any was needed. If you need the
 * decoded form, you can do something like this (where ia is the InternetAddress object returned
 * from an EmailAddress method):
 * <P>
 * ia.setPersonal(javax.mail.internet.MimeUtility.decodeText(ia.getPersonal()));
 * <P>
 * ...subsequent calls to ia.getPersonal() will then return the decoded text.
 * <P>
 * Note: This class does not do any header-length-checking. There are no such limitations on the
 * email address grammar in 2822, though email headers in general do have length restrictions.
 * So if the return path
 * is 40000 unfolded characters long, but otherwise valid under 2822, this class will pass it.
 * <P>
 * Examples of passing (2822-valid) addresses, believe it or not:
 * <P>
 * <tt>bob @example.com</tt>
 * <BR><tt>&quot;bob&quot;  @  example.com</tt>
 * <BR><tt>bob (comment) (other comment) @example.com (personal name)</tt>
 * <BR><tt>&quot;&lt;bob \&quot; (here) &quot; &lt; (hi there) &quot;bob(the man)smith&quot; (hi) @ (there) example.com (hello) &gt; (again)</tt>
 * <P>
 * (none of which are permitted by javamail's InternetAddress parsing, incidentally)
 * <P>
 * By using getInternetAddress(), you can retrieve an InternetAddress object that, when
 * toString()'ed, would reveal that the parser had converted the above into:
 * <P>
 * <tt>&lt;bob@example.com&gt;</tt>
 * <BR><tt>&lt;bob@example.com&gt;</tt>
 * <BR><tt>&quot;personal name&quot; &lt;bob@example.com&gt;</tt>
 * <BR><tt>&quot;&lt;bob \&quot; (here)&quot; &lt;&quot;bob(the man)smith&quot;@example.com&gt;</tt>
 * <P>(respectively)
 * <P>If parsing headers, however, you'll probably be calling extractHeaderAddresses().
 * <P>
 * A future improvement may be to use this class to extract info from corrupted
 * addresses, but for now, it does not permit them.
 * <P>
 * <b>Some of the configuration booleans allow a bit of tweaking
 * already. The source code can be compiled with these booleans in various
 * states. They are configured to what is probably the most commonly-useful state.</b>
 *
 * @author Les Hazlewood, Casey Connor
 * @version 1.13
 */
public class EmailAddress
{
    /**
     * This constant changes the behavior of the domain parsing. If true, the parser will
     * allow 2822 domains, which include single-level domains (e.g. bob@localhost) as well
     * as domain literals, e.g.:
     *
     * <p><tt>someone@[192.168.1.100]</tt> or
     * <br><tt>john.doe@[23:33:A2:22:16:1F]</tt> or
     * <br><tt>me@[my computer]</tt></p>
     *
     * <p>The RFC says these are valid email addresses, but most people don't like
     * allowing them.
     * If you don't want to allow them, and only want to allow valid domain names
     * (<a href="http://www.ietf.org/rfc/rfc1035.txt">RFC 1035</a>, x.y.z.com, etc),
     * and specifically only those with at least two levels ("example.com"), then
     * change this constant to <tt>false</tt>.
     *
     * <p>Its default (compiled) value is <tt>false</tt>, thus it is not RFC 2822 compliant,
     * but you should set it depending on what you need for your application.
     */
    public static final boolean ALLOW_DOMAIN_LITERALS = false;

    /**
     * This constant states that quoted identifiers are allowed
     * (using quotes and angle brackets around the raw address) are allowed, e.g.:
     *
     * <p><tt>"John Smith" &lt;john.smith@somewhere.com&gt;</tt>
     *
     * <p>The RFC says this is a valid mailbox.  If you don't want to
     * allow this, because for example, you only want users to enter in
     * a raw address (<tt>john.smith@somewhere.com</tt> - no quotes or angle
     * brackets), then change this constant to <tt>false</tt>.
     *
     * <p>Its default (compiled) value is <tt>true</tt> to remain RFC 2822 compliant, but
     * you should set it depending on what you need for your application.
     */
    public static final boolean ALLOW_QUOTED_IDENTIFIERS = true;

    /**
     * This constant allows &quot;.&quot; to appear in atext (note: only atext which appears
     * in the 2822 &quot;name-addr&quot; part of the address, not the other instances)
     * <P>
     * The addresses:
     * <p><tt>Kayaks.org &lt;kayaks@kayaks.org&gt;</tt>
     * <P><tt>Bob K. Smith&lt;bobksmith@bob.net&gt;</tt>
     * <P>
     * ...are not valid. They should be:
     * <P><tt>&quot;Kayaks.org&quot; &lt;kayaks@kayaks.org&gt;</tt>
     * <P><tt>&quot;Bob K. Smith&quot; &lt;bobksmith@bob.net&gt;</tt>
     * <P>
     * If this boolean is set to false, the parser will act per 2822 and will require
     * the quotes; if set to true, it will allow the use of &quot;.&quot; without quotes.
     * Default (compiled) setting is false.
     */
    public static final boolean ALLOW_DOT_IN_ATEXT = false;

    /**
     * This controls the behavior of getInternetAddress and extractHeaderAddresses. If true,
     * it allows the not-totally-kosher-but-happens-in-the-real-world practice of:
     * <P>
     * &lt;bob@example.com&gt; (Bob Smith)
     * <P>
     * In this case, &quot;Bob Smith&quot; is not techinically the personal name, just a
     * comment. If this is set to true, the methods will convert this into:
     * Bob Smith &lt;bob@example.com&gt;
     * <P>
     * This also happens somewhat more often and appropriately with
     * <P>
     * <tt>mailer-daemon@blah.com (Mail Delivery System)</tt>
     * <P>
     * If a personal name appears to the left and CFWS appears to the right of an address,
     * the methods will favor the personal name to the left. If the methods need to use the
     * CFWS following the address, they will take the first comment token they find.
     * <P>e.g.:
     * <P><tt>"bob smith" &lt;bob@example.com&gt; (Bobby)</tt>
     * <br> will yield personal name &quot;bob smith&quot;
     * <br><tt>&lt;bob@example.com&gt; (Bobby)</tt>
     * <br>will yield personal name &quot;Bobby&quot;
     * <br><tt>bob@example.com (Bobby)</tt>
     * <br>will yield personal name &quot;Bobby&quot;
     * <br><tt>bob@example.com (Bob) (Smith)</tt>
     * <br>will yield personal name &quot;Bob&quot;
     * <P>
     * Default (compiled) setting is true.
     */
    public static final boolean EXTRACT_CFWS_PERSONAL_NAMES = true;

    /**
     * This constant allows &quot;[&quot; or &quot;]&quot; to appear in atext. Not very
     * useful, maybe, but there it is.
     * <P>
     * The address:
     * <p><tt>[Kayaks] &lt;kayaks@kayaks.org&gt;</tt>
     * ...is not valid. It should be:
     * <P><tt>&quot;[Kayaks]&quot; &lt;kayaks@kayaks.org&gt;</tt>
     * <P>
     * If this boolean is set to false, the parser will act per 2822 and will require
     * the quotes; if set to true, it will allow them to be missing.
     * <P>
     * One real-world example seen:
     * <P>
     * Bob Smith [mailto:bsmith@gmail.com]=20
     * <P>
     * Use at your own risk. There may be some issue with enabling this feature in conjunction
     * with ALLOW_DOMAIN_LITERALS, but i haven't looked into that. If ALLOW_DOMAIN_LITERALS
     * is false, i think this should be pretty safe. Whether or not it's useful, that's up
     * to you. Default (compiled) setting of false.
     */
    public static final boolean ALLOW_SQUARE_BRACKETS_IN_ATEXT = false;

    /**
     * This contant allows &quot;)&quot; or &quot;(&quot; to appear in quoted versions of
     * the localpart (they are never allowed in unquoted versions)
     * <P>
     * The default (2822) behavior is to allow this, i.e. boolean true.
     * <P>
     * You can disallow it, but better to leave it true. I left this hanging around (from an
     * earlier incarnation of the code) as a random option you can switch off. No, it's not
     * necssarily useful. Long story.
     * <P>
     * If false, it will prevent such addresses from being valid, even though they are:
     * &quot;bob(hi)smith&quot;@test.com
     * <P>
     * Deafult (compiled) setting of true.
     */
    public static final boolean ALLOW_PARENS_IN_LOCALPART = true;

    /**
     * Checks to see if the specified string is a valid
     * email address according to the RFC 2822 specification, which is remarkably
     * squirrely. See doc for this class: 2822 not fully implemented, but probably close
     * enough for almost any needs. <b>Note that things like spaces in addresses ("bob @hi.com")
     * are valid according to 2822! Read the docs for this class before using this method!</b>
     * <P>
     * If being used on a 2822 header, this method applies to Sender, Resent-Sender,
     * <b>only</b>,
     * although you can also use it on the Return-Path if you know it to be non-empty
     * (see doc for isValidReturnPath()!). Folded header lines should work OK, but I haven't
     * tested that.
     * <P>
     * @param email the email address string to test for validity (null and &quot;&quot; OK,
     * will return false for those)
     * @return true if the given email text is valid according to RFC 2822, false otherwise.
     */
    public static boolean isValidMailbox(String email)
    {
        return (email != null) && MAILBOX_PATTERN.matcher(email).matches();
    }

    /**
     * Tells us if the email represents a valid return path header string.
     * <P>
     * NOTE: legit forms like &lt;(comment here)&gt; will return true.
     * <P>
     * You can check isValidReturnPath(), and
     * if it is true, and if getInternetAddress() returns null, you know you have a DSN,
     * whether it be an empty return path or one with only CFWS inside the brackets (which is
     * legit, as demonstated above). Note that
     * you can also simply call getReturnPathAddress() to have that operation done for you.
     * <P>Note that &lt;&quot;&quot;&gt; is <b>not</b> a valid return-path.
     */
    public static boolean isValidReturnPath(String email)
    {
        return(email != null) && RETURN_PATH_PATTERN.matcher(email).matches();
    }

    /**
     * WARNING: You may want to use getReturnPathAddress() instead if you're
     * looking for a clean version of the return path without CFWS, etc. See that
     * documentation first!
     * <P>
     * Pull whatever's inside the angle brackets out, without alteration or cleaning.
     * This is more secure than a simple substring() since paths like:
     * <P><tt>&lt;(my &gt; path) &gt;</tt>
     * <P>...are legal return-paths and may throw a simpler parser off. However
     * this method will return <b>all</b> CFWS (comments, whitespace) that may be between
     * the brackets as well. So the example above will return:
     * <P><tt>(my &gt; path)_</tt> <br>(where the _ is the trailing space from the original
     * string)
     */
    public static String getReturnPathBracketContents(String email)
    {
        if (email == null) return(null);
        Matcher m = RETURN_PATH_PATTERN.matcher(email);

        if (m.matches())
            return(m.group(1));
        else return(null);
    }

    /**
     * Pull out the cleaned-up return path address. May return an empty string.
     * Will require two parsings due to an inefficiency.
     *
     * @return null if there are any syntax issues or other weirdness, otherwise
     * the valid, trimmed return path email address without CFWS, surrounding angle brackets,
     * with quotes stripped where possible, etc. (may return an empty string).
     */
    public static String getReturnPathAddress(String email)
    {
        if (email == null) return(null);

        // inefficient, but there is no parallel grammar tree to extract the return path
        // accurately:

        if (isValidReturnPath(email))
        {
            InternetAddress ia = getInternetAddress(email);
            if (ia == null) return("");
            else return(ia.getAddress());
        }
        else return(null);
    }

    /**
     * Tells us if a header line is valid, i.e. checks for a 2822 mailbox-list (which
     * could only have one address in it, or might have more.) Applicable to From or
     * Resent-From headers <b>only</b>.
     * <P>
     * This method seems quick enough so far, but I'm not totally
     * convinced it couldn't be slow given a complicated near-miss string. You may just
     * want to call extractHeaderAddresses() instead, unless you must confirm that the
     * format is perfect. I think that in 99.9999% of real-world cases this method will
     * work fine.
     * <P>
     * @see #isValidAddressList(String)
     */
    public static boolean isValidMailboxList(String header_txt)
    {
        return(MAILBOX_LIST_PATTERN.matcher(header_txt).matches());
    }

    /**
     * Tells us if a header line is valid, i.e. a 2822 address-list (which
     * could only have one address in it, or might have more.) Applicable to To, Cc, Bcc,
     * Reply-To, Resent-To, Resent-Cc, and Resent-Bcc headers <b>only</b>.
     * <P>
     * This method seems quick enough so far, but I'm not totally
     * convinced it couldn't be slow given a complicated near-miss string. You may just
     * want to call extractHeaderAddresses() instead, unless you must confirm that the
     * format is perfect. I think that in 99.9999% of real-world cases this method will
     * work fine and quickly enough. Let me know what your testing reveals.
     * <P>
     * @see #isValidMailboxList(String)
     */
    public static boolean isValidAddressList(String header_txt)
    {
        // creating the actual ADDRESS_LIST_PATTERN string proved too large for java, but
        // forutnately we can use this alternative FSM to check. Since the address pattern
        // is greedy, it will match all CFWS up to the comma which we can then require easily.

        boolean valid = false;
        Matcher m = ADDRESS_PATTERN.matcher(header_txt);
        int max = header_txt.length();

        while (m.lookingAt())
        {
            if (m.end() == max)
            {
                valid = true;
                break;
            }
            else
            {
                valid = false;
                if (header_txt.charAt(m.end()) == ',')
                {
                    m.region(m.end() + 1, max);
                    continue;
                }
                else break;
            }
        }

        return(valid);
        // return(ADDRESS_LIST_PATTERN.matcher(header_txt).matches());
    }

    /**
     * Given a 2822-valid single address string, give us an InternetAddress object holding
     * that address, otherwise returns null. The email address that comes back from the
     * resulting InternetAddress object's getAddress() call will have comments and unnecessary
     * quotation marks or whitespace removed.
     * <P>
     * If your String is an email header, you should probably use
     * extractHeaderAddresses instead, since most headers can have multiple addresses in them.
     * (see that method for more info.) This method will indeed fail if you use it on a header
     * line with more than one address.
     * <P>
     * Exception: You CAN and should use this for the Sender header, and probably you want
     * to use it for the X-Original-To as well.
     * <P>
     * Another exception: You can use this for the Return-Path, but if you want to know that
     * a Return-Path is valid and you want to extract
     * it, you will have to call both this method and isValidReturnPath; this operation can
     * be done for you by simply calling getReturnPathAddress() instead of this method. In
     * terms of this method's application to the return-path, note that
     * the common valid Return-Path value &lt;&gt; will return null. So will the illegitimate
     * &quot;&quot; or legitimate
     * empty-string, but other illegitimate Return-Paths like
     * <P><tt>&quot;hi&quot; &lt;bob@smith.com&gt;</tt>
     * <P>will return an address, so the moral is that
     * you may want to check isValidReturnPath() first, if you care. This method is useful if
     * you trust the return path and want to extract a clean address from it without CFWS
     * (getReturnPathBracketContents() will return any CFWS),
     * or if you want to determine if a validated return path actually contains an address in
     * it and isn't just empty or full of CFWS. Except for empty return paths (those lacking an
     * address) the Return-Path specification is a subset
     * of valid 2822 addresses, so this method will work on all non-empty return-paths,
     * failing only on the empty ones.
     * <P>
     * In general for this method, note: although this method does not use InternetAddress to
     * parse/extract the
     * information, it does ensure that InternetAddress can use the results (i.e. that
     * there are no encoding issues), but note that an InternetAddress object can hold
     * (and use) values for the address which it could not have parsed itself.
     * Thus, it's possible that for InternetAddress addr, which came as the result of
     * this method, the following may throw an exception <b>or</b> may silently fail:<BR>
     * InternetAddress addr2 = InternetAddress.parse(addr.toString());
     * <P>
     * The InternetAddress objects returned by this method will not do any decoding of RFC-2047
     * encoded personal names. See the documentation for this overall class (above) for more.
     * <P>
     * Again, all other uses of that addr object should work OK. It is recommended that if
     * you are using this class that you never create an InternetAddress object using
     * InternetAddress's own constructors or parsing methods; rather, retrieve them through
     * this class. Perhaps the addr.clone() would work OK, though.
     * <P>
     * The personal name will include any and all phrase token(s) to the left of the address,
     * if they exist, and the string will be trim()'ed, but note that InternetAddress, when
     * generating the getPersonal() result or the toString() result, if
     * it encounters any quotes or backslashes in the personal name String, will put the entire
     * thing in a big quoted-escaped chunk.
     * <P>
     * This will do some smart unescaping to prevent that from happening unnecessarily;
     * specifically, if there are unecessary quotes around a personal name, it will remove
     * them. E.g.
     * <P>
     * "Bob" &lt;bob@hi.com&gt;
     * <br>becomes:
     * <BR>Bob &lt;bob@hi.com&gt;
     * <P>
     * (apologies to bob@hi.com for everything i've done to him)
     */
    public static InternetAddress getInternetAddress(String email)
    {
        if (email == null) return(null);

        Matcher m = MAILBOX_PATTERN.matcher(email);

        if (m.matches()) return(pullFromGroups(m));
        else return(null);
    }

    /**
     * See getInternetAddress; does the same thing but returns the constituent parts
     * of the address in a three-element array (or null if the address is invalid).
     * <P>
     * This may be useful because even with cleaned-up address extracted with this class
     * the parsing to achieve this is not trivial.
     * <P>
     * To actually use these values in an email, you should construct an InternetAddress
     * object (or
     * equivalent) which can handle the various quoting, adding of the angle brackets
     * around the address, etc., necessary for presenting the whole address.
     * <P>
     * To construct the email address, you can safely use:
     * <BR>result[1] + &quot;@&quot; + result[2]
     * <P>
     * @return a three-element array containing the personal name String, local part String,
     * and the domain part String of the address, in that order, without the @; will return
     * null if the address is invalid; if it is valid this will not
     * return null but the personal name (at index 0) may be null
     */
    public static String[] getAddressParts(String email)
    {
        if (email == null) return (null);

        Matcher m = MAILBOX_PATTERN.matcher(email);

        if (m.matches()) return(getMatcherParts(m)); else return(null);
    }

    /**
     * See getInternetAddress; does the same thing but returns the personal name that would
     * have been returned from getInternetAddress() in String
     * form.
     * <P>
     * The Strings returned by this method will not reflect any decoding of RFC-2047
     * encoded personal names. See the documentation for this overall class (above) for more.
     */
    public static String getPersonalName(String email)
    {
        if (email == null) return (null);

        Matcher m = MAILBOX_PATTERN.matcher(email);

        if (m.matches()) return(getMatcherParts(m)[0]); else return(null);
    }

    /**
     * See getInternetAddress; does the same thing but returns the local part that would
     * have been returned from getInternetAddress() in String
     * form (essentially, the part to the left of the @). This may be useful because
     * a simple search/split on a &quot;@&quot; is not a safe way to do this, given
     * escaped quoted strings, etc.
     */
    public static String getLocalPart(String email)
    {
        if (email == null) return (null);

        Matcher m = MAILBOX_PATTERN.matcher(email);

        if (m.matches()) return(getMatcherParts(m)[1]); else return(null);
    }

    /**
     * See getInternetAddress; does the same thing but returns the domain part in string
     * form (essentially, the part to the right of the @). This may be useful because
     * a simple search/split on a &quot;@&quot; is not a safe way to do this, given
     * escaped quoted strings, etc.
     */
    public static String getDomain(String email)
    {
        if (email == null) return (null);

        Matcher m = MAILBOX_PATTERN.matcher(email);

        if (m.matches()) return(getMatcherParts(m)[2]); else return(null);
    }

    /**
     * Given the value of a header, like the From:, extract valid 2822 addresses from it
     * and place them in an array. Returns an empty array if none found, will not return
     * null. Note that you should pass in everything except, e.g. &quot;From: &quot;, in other
     * words,
     * the header value without the header name and &quot;: &quot; at the start.. The addresses
     * that come back from the
     * resulting InternetAddress objects' getAddress calls will have comments and unnecessary
     * quotation marks or whitespace removed. If a bad address is encountered, parsing stops,
     * and the good
     * addresses found up until then (if any) are returned. This is kind of strict
     * and could be improved, but that's the way it is for now. If you need to know
     * if the header is totally valid (not just up to a certain address) then you can use
     * isValidMailboxList() or isValidAddressList() or isValidMailbox(), depending on
     * the header:
     * <P>
     * This method can handle group addresses, but it does not preseve the group name or
     * the structure of any groups; rather it flattens them all into the same array.
     * You can call this method on the From or any other header that uses the mailbox-list form
     * (which doesn't use groups), or you can call it on the To, Cc, Bcc, or Reply-To or any
     * other header which uses the address-list format which might have groups in there.
     * This method doesn't enforce any group structure syntax either. If you care to test
     * for 2822 validity of a list of addresses (including group format), use the appropriate
     * method. This will dependably extract addresses from a valid list. If the list is
     * invalid, it may extract them anyway, or it may fail somewhere along the line.
     * <P>
     * You should not use this method on the Return-Path header; instead use
     * getInternetAddress() or getReturnPathAddress() (see that doc for info about
     * Return-Path). However, you could use this on the Sender header if you didn't care
     * to check it for validity, since single mailboxes are valid subsets of valid
     * mailbox-lists and address-lists.
     * <P>
     * @param header_txt is text from whatever header (not including the header name and
     * &quot;: &quot;. I don't
     * think the String needs to be unfolded, but i haven't tested that.
     * <P>
     * see getInternetAddress() for more info: this extracts the same way
     * <P>
     * @return zero-length array if erorrs or none found, otherwise an array of length &gt; 0
     * with the addresses as InternetAddresses with the personal name and emails set correctly
     * (i.e. doesn't rely on InternetAddress parsing for extraction, but does require that
     * the address be usable by InternetAddress, although re-parsing with InternetAddress may
     * cause exceptions, see getInternetAddress()); will not return null.
     */
    public static InternetAddress[] extractHeaderAddresses(String header_txt)
    {
        // you may go insane from this code

        if (header_txt == null || header_txt.equals("")) return(new InternetAddress[0]);

// optimize: separate method or boolean to indicate if group should be worried about at all

        Matcher m = MAILBOX_PATTERN.matcher(header_txt);
        Matcher gp = GROUP_PREFIX_PATTERN.matcher(header_txt);

        ArrayList <InternetAddress> result = new ArrayList <InternetAddress> (1);

        int max = header_txt.length();
        InternetAddress cur_addr;
        boolean group_start = false;
        boolean group_end = false;
        int next_comma_index = -1;
        int next_semicolon_index = -1;
        int just_after_group_end = -1;

        // skip past any group prefixes, gobble addresses as usual in a list but
        // skip past the terminating semicolon
        while (true)
        {
            if (group_end)
            {
                next_comma_index = header_txt.indexOf(',', just_after_group_end);

                if (next_comma_index < 0) break;
                if (next_comma_index >= max - 1) break;

                gp.region(next_comma_index + 1, max);
                m.region(next_comma_index + 1, max);

                group_end = false;
            }

            if (header_txt.charAt(m.regionStart()) == ';')
            {
                group_start = false;

                m.region(m.regionStart() + 1, max);
                // could say >= max - 1 or even max - 3 or something, but just to be
                // proper:
                if (m.regionStart() >= max) break;
                gp.region(m.regionStart(), max);

                group_end = true;
                just_after_group_end = m.regionStart();
            }

            if (m.lookingAt())
            {
                group_start = false;

                // must test m.end() == max first with early exit
                if (m.end() == max || header_txt.charAt(m.end()) == ',' ||
                    (group_end = (header_txt.charAt(m.end()) == ';')))
                {
                    cur_addr = pullFromGroups(m);
                    if (cur_addr != null) result.add(cur_addr);

                    if (m.end() < max - 1)
                    {
                        if (!group_end)
                        {
                            // skip the comma
                            gp.region(m.end() + 1, max);
                            m.region(m.end() + 1, max);
                        }
                        else just_after_group_end = m.end() + 1;
                    }
                    else break;
                }
                else break;
            }
            else if (gp.lookingAt())
            {
                if (gp.end() < max)
                {
                    // the colon is included in the gp match, so nothing to skip
                    m.region(gp.end(), max);
                    gp.region(gp.end(), max);
                    group_start = true;
                }
                else break;
            }
            else if (group_start)
            {
                next_semicolon_index = header_txt.indexOf(';', m.regionStart());
                if (next_semicolon_index < 0) break;
                else if (next_semicolon_index >= max - 1) break;

                m.region(next_semicolon_index + 1, max);
                gp.region(next_semicolon_index + 1, max);

                group_start = false;
                group_end = true;
                just_after_group_end = m.regionStart();
            }
            else if (group_end) continue;
            else break;
        }

        return(result.size() > 0 ? result.toArray(new InternetAddress[result.size()]) :
               new InternetAddress[0]);
    }

                    //////////////////////////////////////
                   // MY DRAGONS WILL EAT YOUR DRAGONS //
                  //////////////////////////////////////

    // RFC 2822 2.2.2 Structured Header Field Bodies
    private static final String crlf = "\\r\\n";
    private static final String wsp = "[ \\t]"; //space or tab
    private static final String fwsp = "(?:" + wsp + "*" + crlf + ")?" + wsp + "+";

    //RFC 2822 3.2.1 Primitive tokens
    private static final String dquote = "\\\"";
    //ASCII Control characters excluding white space:
    private static final String noWsCtl = "\\x01-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F";
    //all ASCII characters except CR and LF:
    private static final String asciiText = "[\\x01-\\x09\\x0B\\x0C\\x0E-\\x7F]";

    // RFC 2822 3.2.2 Quoted characters:
    //single backslash followed by a text char
    private static final String quotedPair = "(?:\\\\" + asciiText + ")";

    // RFC 2822 3.2.3 CFWS specification
    // note: nesting should be permitted but is not by these rules given code limitations:

    // rewritten to be shorter:
    //private static final String ctext = "[" + noWsCtl + "\\x21-\\x27\\x2A-\\x5B\\x5D-\\x7E]";
    private static final String ctext = "[" + noWsCtl + "\\!-\\'\\*-\\[\\]-\\~]";
    private static final String ccontent = ctext + "|" + quotedPair; // + "|" + comment;
    private static final String comment = "\\((?:(?:" + fwsp + ")?" + ccontent + ")*(?:" +
        fwsp + ")?\\)";
    private static final String cfws = "(?:(?:" + fwsp + ")?" + comment + ")*(?:(?:(?:" +
        fwsp +")?" + comment + ")|(?:" + fwsp + "))";

    //RFC 2822 3.2.4 Atom:

    private static final String atext = "[a-zA-Z0-9\\!\\#-\\'\\*\\+\\-\\/\\=\\?\\^-\\`\\{-\\~"
        + (ALLOW_DOT_IN_ATEXT ? "\\." : "")
        + (ALLOW_SQUARE_BRACKETS_IN_ATEXT ? "\\[\\]" : "") + "]";
    // regular atext is same as atext but has no . or [ or ] allowed, no matter the class prefs, to prevent
    // long recursions on e.g. "a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p.q.r.s.t"
    private static final String regularAtext = "[a-zA-Z0-9\\!\\#-\\'\\*\\+\\-\\/\\=\\?\\^-\\`\\{-\\~]";

    private static final String atom = "(?:" + cfws + ")?" + atext + "+" + "(?:" + cfws + ")?";
    private static final String dotAtomText = regularAtext + "+" + "(?:" + "\\." + regularAtext + "+)*";
    private static final String dotAtom = "(?:" + cfws + ")?" + dotAtomText + "(?:" +
        cfws + ")?";
    private static final String capDotAtomNoCFWS = "(?:" + cfws + ")?(" + dotAtomText +
        ")(?:" + cfws + ")?";
    private static final String capDotAtomTrailingCFWS = "(?:" + cfws + ")?(" + dotAtomText +
        ")(" + cfws + ")?";

    //RFC 2822 3.2.5 Quoted strings:
    //noWsCtl and the rest of ASCII except the doublequote and backslash characters:

    private static final String qtext = "[" + noWsCtl + "\\!\\#-\\[\\]-\\~]";
    private static final String localPartqtext = "[" + noWsCtl + (ALLOW_PARENS_IN_LOCALPART ?
                                     "\\!\\#-\\[\\]-\\~]" : "\\!\\#-\\'\\*-\\[\\]-\\~]");

    private static final String qcontent = "(?:" + qtext + "|" + quotedPair + ")";
    private static final String localPartqcontent = "(?>" + localPartqtext + "|" +
        quotedPair + ")";
    private static final String quotedStringWOCFWS = dquote + "(?>(?:" +
        fwsp + ")?" + qcontent + ")*(?:" + fwsp + ")?" + dquote;
    private static final String quotedString = "(?:" + cfws + ")?" + quotedStringWOCFWS +
        "(?:" + cfws + ")?";
    private static final String localPartQuotedString = "(?:" + cfws + ")?(" + dquote +
        "(?:(?:" + fwsp + ")?" + localPartqcontent + ")*(?:" + fwsp + ")?" + dquote +
        ")(?:" + cfws + ")?";

    //RFC 2822 3.2.6 Miscellaneous tokens
    private static final String word = "(?:(?:" + atom + ")|(?:" + quotedString + "))";
    // by 2822: phrase = 1*word / obs-phrase
    // implemented here as: phrase = word (FWS word)*
    // so that aaaa can't be four words, which can cause tons of recursive backtracking
    //private static final String phrase = "(?:" + word + "+?)"; //one or more words
    private static final String phrase = word + "(?:(?:" + fwsp + ")" + word + ")*";

    //RFC 1035 tokens for domain names:
    private static final String letter = "[a-zA-Z]";
    private static final String letDig = "[a-zA-Z0-9]";
    private static final String letDigHyp = "[a-zA-Z0-9-]";
    private static final String rfcLabel = letDig + "(?:" + letDigHyp + "{0,61}" +
        letDig + ")?";
    private static final String rfc1035DomainName = rfcLabel + "(?:\\." + rfcLabel + ")*\\." +
        letter + "{2,6}";

    //RFC 2822 3.4 Address specification
    //domain text - non white space controls and the rest of ASCII chars not
    // including [, ], or \:
    // rewritten to save space:
    //private static final String dtext = "[" + noWsCtl + "\\x21-\\x5A\\x5E-\\x7E]";
    private static final String dtext = "[" + noWsCtl + "\\!-Z\\^-\\~]";

    private static final String dcontent = dtext + "|" + quotedPair;
    private static final String capDomainLiteralNoCFWS = "(?:" + cfws + ")?" + "(\\[" +
        "(?:(?:" + fwsp + ")?(?:" + dcontent + ")+)*(?:" + fwsp + ")?\\])" + "(?:" + cfws +
        ")?";
    private static final String capDomainLiteralTrailingCFWS = "(?:" + cfws + ")?" + "(\\[" +
        "(?:(?:" + fwsp + ")?(?:" + dcontent + ")+)*(?:" + fwsp + ")?\\])" + "(" + cfws + ")?";
    private static final String rfc2822Domain = "(?:" + capDotAtomNoCFWS + "|" +
        capDomainLiteralNoCFWS + ")";
    private static final String capCFWSRfc2822Domain = "(?:" + capDotAtomTrailingCFWS + "|" +
        capDomainLiteralTrailingCFWS + ")";

    // Les chose to implement the more-strict 1035 instead of just relying on "dot-atom"
    // as would be implied by 2822 without the domain-literal token. The issue is that 2822
    // allows CFWS around the local part and the domain,
    // strange though that may be (and you "SHOULD NOT" put it around the @). This version
    // allows the cfws before and after.
    // private static final String domain =
    //    ALLOW_DOMAIN_LITERALS ? rfc2822Domain : rfc1035DomainName;
    private static final String domain = ALLOW_DOMAIN_LITERALS ? rfc2822Domain : "(?:" +
        cfws + ")?(" + rfc1035DomainName + ")(?:" + cfws + ")?";
    private static final String capCFWSDomain =
        ALLOW_DOMAIN_LITERALS ? capCFWSRfc2822Domain : "(?:" +
        cfws + ")?(" + rfc1035DomainName + ")(" + cfws + ")?";
    private static final String localPart = "(" + capDotAtomNoCFWS + "|" +
        localPartQuotedString + ")";
    // uniqueAddrSpec exists so we can have a duplicate tree that has a capturing group
    // instead of a non-capturing group for the trailing CFWS after the domain token
    // that we wouldn't want if it was inside
    // an angleAddr. The matching should be otherwise identical.
    private static final String addrSpec = localPart + "@" + domain;
    private static final String uniqueAddrSpec = localPart + "@" + capCFWSDomain;
    private static final String angleAddr = "(?:" + cfws + ")?<" + addrSpec + ">(" +
        cfws + ")?";
    // uses a reluctant quantifier to skip ahead and make sure there's actually an
    // address in there somewhere... hmmm, maybe regex in java doesn't optimize that
    // case by skipping over it at the start by default? Doesn't seem to solve the
    // issue of recursion on long strings like [A-Za-z], but issue was solved by
    // changing phrase definition (see above):
    private static final String nameAddr = "(" + phrase + ")??(" + angleAddr + ")";
    private static final String mailbox = (ALLOW_QUOTED_IDENTIFIERS ?
                                           "(" + nameAddr + ")|" : "") + "(" +
        uniqueAddrSpec + ")";

    private static final String returnPath = "(?:(?:" + cfws + ")?<((?:" + cfws + ")?|" +
        addrSpec + ")>(?:" + cfws + ")?)";

    private static final String mailboxList = "(?:(?:" + mailbox + ")(?:,(?:" +
        mailbox + "))*)";
    private static final String groupPostfix = "(?:" + cfws + "|(?:" + mailboxList + ")"
        + ")?;(?:" + cfws + ")?";
    private static final String groupPrefix = phrase + ":";
    private static final String group = groupPrefix + groupPostfix;
    private static final String address = "(?:(?:" + mailbox + ")|(?:" + group + "))";
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
     * Java regex pattern for 2822 &quot;mailbox&quot; token; Not necessarily useful,
     * but available in case.
     */
    public static final Pattern MAILBOX_PATTERN = Pattern.compile(mailbox);
    /**
     * Java regex pattern for 2822 &quot;addr-spec&quot; token; Not necessarily useful,
     * but available in case.
     */
    public static final Pattern ADDR_SPEC_PATTERN = Pattern.compile(addrSpec);
    /**
     * Java regex pattern for 2822 &quot;mailbox-list&quot; token; Not necessarily useful,
     * but available in case.
     */
    public static final Pattern MAILBOX_LIST_PATTERN = Pattern.compile(mailboxList);
    //    public static final Pattern ADDRESS_LIST_PATTERN = Pattern.compile(addressList);
    /**
     * Java regex pattern for 2822 &quot;address&quot; token; Not necessarily useful,
     * but available in case.
     */
    public static final Pattern ADDRESS_PATTERN = Pattern.compile(address);
    /**
     * Java regex pattern for 2822 &quot;comment&quot; token; Not necessarily useful,
     * but available in case.
     */
    public static final Pattern COMMENT_PATTERN = Pattern.compile(comment);

    private static final Pattern QUOTED_STRING_WO_CFWS_PATTERN =
        Pattern.compile(quotedStringWOCFWS);
    private static final Pattern RETURN_PATH_PATTERN = Pattern.compile(returnPath);
    private static final Pattern GROUP_PREFIX_PATTERN = Pattern.compile(groupPrefix);

    // confused yet? Try this:
    private static final Pattern ESCAPED_QUOTE_PATTERN = Pattern.compile("\\\\\"");
    private static final Pattern ESCAPED_BSLASH_PATTERN = Pattern.compile("\\\\\\\\");

    /**
     * Using knowledge of the group-ID numbers (see comments at top) pull the
     * data relevant to us from an already-successfully-matched matcher. See doc
     * for getInternetAddress and extractHeaderAddresses for info re: InternetAddress
     * parsing compatability.
     * <p>
     * You could roll your own method that does what you care about.
     * <P>
     * This should work on the matcher for MAILBOX_LIST_PATTERN or MAILBOX_PATTERN, but
     * only those. With some tweaking it could easily be adapted to some others.
     * <P>
     * May return null on encoding errors.
     * <P>
     * Also cleans up the address: tries to strip bounding quotes off of the local
     * part without damaging it's parsability (by this class); if it can, do that; all other
     * cases, don't.
     * <P>
     * e.g. &quot;bob&quot;@example.com becomes bob@example.com
     */
    private static InternetAddress pullFromGroups(Matcher m)
    {
        InternetAddress current_ia = null;
        String[] parts = getMatcherParts(m);

        if (parts[1] == null || parts[2] == null) return(null);

        // if for some reason you want to require that the result be re-parsable by
        // InternetAddress, you
        // could uncomment the appropriate stuff below, but note that not all the utility
        // functions use pullFromGroups; some call getMatcherParts directly.
        try
        {
            //current_ia = new InternetAddress(parts[0] + " <" + parts[1] + "@" +
            //                                 parts[2]+ ">", true);
            // so it parses it OK, but since javamail doesn't extract too well
            // we make sure that the consituent parts
            // are correct

            current_ia = new InternetAddress();
            current_ia.setPersonal(parts[0]);
            current_ia.setAddress(parts[1] + "@" + parts[2]);
        }
        //catch (AddressException ae)
        //        {
        //System.out.println("ex: " + ae);
        //            current_ia = null;
        //        }
        catch (UnsupportedEncodingException uee)
        {
            current_ia = null;
        }

        return(current_ia);
    }

    /**
     * See pullFromGroups
     *
     * @return will not return null
     */
    private static String[] getMatcherParts(Matcher m)
    {
        String current_localpart = null;
        String current_domainpart = null;
        String local_part_da = null;
        String local_part_qs = null;
        String domain_part_da = null;
        String domain_part_dl = null;
        String personal_string = null;

        // see the group-ID lists in the grammar comments

        if (ALLOW_QUOTED_IDENTIFIERS)
        {
            if (ALLOW_DOMAIN_LITERALS)
            {
                // yes quoted identifiers, yes domain literals

                if (m.group(1) != null)
                {
                    // name-addr form
                    local_part_da = m.group(5);
                    if (local_part_da == null) local_part_qs = m.group(6);

                    domain_part_da = m.group(7);
                    if (domain_part_da == null) domain_part_dl = m.group(8);

                    current_localpart =
                        (local_part_da == null ? local_part_qs : local_part_da);

                    current_domainpart =
                        (domain_part_da == null ? domain_part_dl : domain_part_da);

                    personal_string = m.group(2);
                    if (personal_string == null && EXTRACT_CFWS_PERSONAL_NAMES)
                    {
                        personal_string = m.group(9);
                        personal_string = removeAnyBounding('(', ')',
                                                            getFirstComment(personal_string));
                    }
                }
                else if (m.group(10) != null)
                {
                    // addr-spec form

                    local_part_da = m.group(12);
                    if (local_part_da == null) local_part_qs = m.group(13);

                    domain_part_da = m.group(14);
                    if (domain_part_da == null) domain_part_dl = m.group(15);

                    current_localpart =
                        (local_part_da == null ? local_part_qs : local_part_da);

                    current_domainpart =
                        (domain_part_da == null ? domain_part_dl : domain_part_da);

                    if (EXTRACT_CFWS_PERSONAL_NAMES)
                    {
                        personal_string = m.group(16);
                        personal_string = removeAnyBounding('(', ')',
                                                            getFirstComment(personal_string));
                    }
                }
            }
            else
            {
                // yes quoted identifiers, no domain literals

                if (m.group(1) != null)
                {
                    // name-addr form

                    local_part_da = m.group(5);
                    if (local_part_da == null) local_part_qs = m.group(6);

                    current_localpart =
                        (local_part_da == null ? local_part_qs : local_part_da);

                    current_domainpart = m.group(7);

                    personal_string = m.group(2);
                    if (personal_string == null && EXTRACT_CFWS_PERSONAL_NAMES)
                    {
                        personal_string = m.group(8);
                        personal_string = removeAnyBounding('(', ')',
                                                            getFirstComment(personal_string));
                    }
                }
                else if (m.group(9) != null)
                {
                    // addr-spec form

                    local_part_da = m.group(11);
                    if (local_part_da == null) local_part_qs = m.group(12);

                    current_localpart =
                        (local_part_da == null ? local_part_qs : local_part_da);

                    current_domainpart = m.group(13);

                    if (EXTRACT_CFWS_PERSONAL_NAMES)
                    {
                        personal_string = m.group(14);
                        personal_string = removeAnyBounding('(', ')',
                                                            getFirstComment(personal_string));
                    }
                }
            }
        }
        else
        {
            // no quoted identifiers, yes|no domain literals

            local_part_da = m.group(3);
            if (local_part_da == null) local_part_qs = m.group(4);

            domain_part_da = m.group(5);
            if (domain_part_da == null && ALLOW_DOMAIN_LITERALS)
                domain_part_dl = m.group(6);

            current_localpart = (local_part_da == null ? local_part_qs : local_part_da);

            current_domainpart = (domain_part_da == null ? domain_part_dl : domain_part_da);

            if (EXTRACT_CFWS_PERSONAL_NAMES)
            {
                personal_string = m.group((ALLOW_DOMAIN_LITERALS ? 1 : 0) + 6);
                personal_string = removeAnyBounding('(', ')',
                                                    getFirstComment(personal_string));
            }
        }

        if (current_localpart != null) current_localpart = current_localpart.trim();
        if (current_domainpart != null) current_domainpart = current_domainpart.trim();
        if (personal_string != null)
        {
            // trim even though calling cPS which trims, because the latter may return
            // the same thing back without trimming
            personal_string = personal_string.trim();
            personal_string = cleanupPersonalString(personal_string);
        }

        // remove any unecessary bounding quotes from the localpart:

        String test_addr = removeAnyBounding('"', '"', current_localpart) +
            "@" + current_domainpart;

        if (ADDR_SPEC_PATTERN.matcher(test_addr).matches()) current_localpart =
            removeAnyBounding('"', '"', current_localpart);

        return(new String[] { personal_string, current_localpart, current_domainpart });
    }

    /**
     * Given a string, extract the first matched comment token as defined in 2822, trimmed;
     * return null on all errors or non-findings
     * <P>
     * This is probably not super-useful. Included just in case.
     * <P>
     * Note for future improvement: if COMMENT_PATTERN could handle nested
     * comments, then this should be able to as well, but if this method were to be used to
     * find the CFWS personal name (see boolean option) then such a nested comment would
     * probably not be the one you were looking for?
     */
    public static String getFirstComment(String text)
    {
        if (text == null) return(null); // important

        Matcher m = COMMENT_PATTERN.matcher(text);

        if (! m.find()) return(null);

        return(m.group().trim()); // trim important
    }

    /**
     * Given a string, if the string is a quoted string (without CFWS
     * around it, although it will be trimmed) then remove the bounding
     * quotations and then unescape it. Useful when passing
     * simple named address personal names into InternetAddress since InternetAddress always
     * quotes the entire phrase token into one mass; in this simple (and common) case, we
     * can strip off the quotes and de-escape, and passing to javamail will result in a cleaner
     * quote-free result (if there are no embedded escaped characters) or the proper
     * one-level-quoting
     * result (if there are embedded escaped characters). If the string is anything else,
     * this just returns it unadulterated.
     */
    private static String cleanupPersonalString(String text)
    {
        if (text == null) return(null);
        text = text.trim();

        Matcher m = QUOTED_STRING_WO_CFWS_PATTERN.matcher(text);

        if (! m.matches()) return(text);

        text = removeAnyBounding('"', '"', m.group());

        text = ESCAPED_BSLASH_PATTERN.matcher(text).replaceAll("\\\\");
        text = ESCAPED_QUOTE_PATTERN.matcher(text).replaceAll("\"");

        return(text.trim());
    }

    /**
     * If the string starts and ends with s and e, remove them, otherwise return
     * the string as it was passed in.
     */
    private static String removeAnyBounding(char s, char e, String str)
    {
        if (str == null || str.length() < 2) return(str);

        if (str.startsWith(String.valueOf(s)) && str.endsWith(String.valueOf(e)))
            return(str.substring(1, str.length() - 1));
        else return(str);
    }

/* The current regex string for mailbox token, just for fun:
(((?:(?:(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?[a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)|(?:(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?\"(?:(?:(?:[ \t]*\r\n)?[ \t]+)?(?:[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!\#-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F])))*(?:(?:[ \t]*\r\n)?[ \t]+)?\"(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?))(?:(?:(?:[ \t]*\r\n)?[ \t]+)(?:(?:(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?[a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)|(?:(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?\"(?:(?:(?:[ \t]*\r\n)?[ \t]+)?(?:[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!\#-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F])))*(?:(?:[ \t]*\r\n)?[ \t]+)?\"(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)))*)??((?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?<((?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?([a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+(?:\.[a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+)*)(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?|(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?(\"(?:(?:(?:[ \t]*\r\n)?[ \t]+)?(?:[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!\#-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F])))*(?:(?:[ \t]*\r\n)?[ \t]+)?\")(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)@(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\.[a-zA-Z]{2,6})(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?>((?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?))|(((?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?([a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+(?:\.[a-zA-Z0-9\!\#-\'\*\+\-\/\=\?\^-\`\{-\~\.]+)*)(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[\t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?|(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?(\"(?:(?:(?:[ \t]*\r\n)?[ \t]+)?(?:[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!\#-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F])))*(?:(?:[ \t]*\r\n)?[ \t]+)?\")(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)@(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\.[a-zA-Z]{2,6})((?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))*(?:(?:(?:(?:[ \t]*\r\n)?[ \t]+)?\((?:(?:(?:[ \t]*\r\n)?[ \t]+)?[\x01-\x08\x0B\x0C\x0E-\x1F\x7F\!-\'\*-\[\]-\~]|(?:\\[\x01-\x09\x0B\x0C\x0E-\x7F]))*(?:(?:[ \t]*\r\n)?[ \t]+)?\))|(?:(?:[ \t]*\r\n)?[ \t]+)))?)

*/

}