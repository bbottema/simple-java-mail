package org.hazlewood.connor.bottema.emailaddress;

import static java.util.EnumSet.of;

import java.util.EnumSet;

/**
 * Defines a set of restriction flags for email address validation. To remain completely true to RFC 2822, all flags should be set to <code>true</code>.
 * <p>
 * There are a few basic use cases:
 * <ol>
 *    	 <li>
 *    	     User wants to scrape as much data from a possibly-ugly address as they can and make a sensible address from it; these users typically allow all
 *    	     kinds of addresses (except perhaps for single-domain addresses) because in the wild, legitimate senders often violate 2822. E.g. If your goal is to
 *    	     parse spammy emails for analysis, you may want to allow every variation out there just so you can parse something useful.
 * 		</li>
 *     	<li>
 *     	    User wants to check to see if an email address is of proper, normal syntax; e.g. checking the value entered in a form. These users typically make
 * 			everything strict, since what most people consider a "valid" email address is a drastic subset of 2822. For users with the strictest requirements,
 * 			this library may not be enough, since although it checks most of RFC 2822, it might still be too 'tolerant' for their needs (on the other side of
 * 			the spectrum, most libraries use a simple blah@blah.blah.com type regex, which as we of course know is
 * 			<a href="http://www.troyhunt.com/2013/11/dont-trust-net-web-forms-email-regex.html">rarely a good idea</a>)
 * 		</li>
 * 		<li>
 * 		    User wants to intelligently parse a possibly-ugly address with the goal being a cleaned up usable address that other software
 * 		    (MTAs, databases, whatever) can use / parse without breaking; {@link #DEFAULT} tailors to this use case (with the possible exception of
 * 		    {@link #ALLOW_DOT_IN_A_TEXT}, to taste). In our experience they allowed "real" addresses the highest percentage of the time, and the addresses they
 * 		    failed on were almost all ridiculous.
 * 		</li>
 * </ol>
 *
 * @author Benny Bottema
 */
public enum EmailAddressCriteria {
	/**
	 * This criteria changes the behavior of the domain parsing. If included, the parser will allow 2822 domains, which include single-level domains (e.g.
	 * bob@localhost) as well as domain literals, e.g.: <p> <p><tt>someone@[192.168.1.100]</tt> or <br><tt>john.doe@[23:33:A2:22:16:1F]</tt> or <br><tt>me@[my
	 * computer]</tt>
	 * <p>
	 * <p>The RFC says these are valid email addresses, but most people don't like allowing them. If you don't want to allow them, and only want to allow valid
	 * domain names (<a href="http://www.ietf.org/rfc/rfc1035.txt">RFC 1035</a>, x.y.z.com, etc), and specifically only those with at least two levels
	 * ("example.com"), then don't include this critera.
	 */
	ALLOW_DOMAIN_LITERALS,

	/**
	 * This criteria states that as per RFC 2822, quoted identifiers are allowed (using quotes and angle brackets around the raw address), e.g.:
	 * <p>
	 * <tt>"John Smith" &lt;john.smith@somewhere.com&gt;</tt>
	 * <p>
	 * <p>The RFC says this is a valid mailbox.  If you don't want to allow this, because for example, you only want users to enter in a raw address
	 * (<tt>john.smith@somewhere.com</tt> - no quotes or angle brackets), then don't include this criteria.
	 */
	ALLOW_QUOTED_IDENTIFIERS,

	/**
	 * This criteria allows &quot;.&quot; to appear in atext (note: only atext which appears in the 2822 &quot;name-addr&quot; part of the address, not the
	 * other instances)
	 * <p>
	 * The addresses: <p><tt>Kayaks.org &lt;kayaks@kayaks.org&gt;</tt> <P><tt>Bob K. Smith&lt;bobksmith@bob.net&gt;</tt>
	 * <p>
	 * ...are not valid. They should be: <P><tt>&quot;Kayaks.org&quot; &lt;kayaks@kayaks.org&gt;</tt> <P><tt>&quot;Bob K. Smith&quot;
	 * &lt;bobksmith@bob.net&gt;</tt>
	 * <p>
	 * If this criteria is not included, the parser will act per 2822 and will require the quotes; if included, it will allow the use of &quot;.&quot; without
	 * quotes.
	 */
	ALLOW_DOT_IN_A_TEXT,

	/**
	 * This criteria allows &quot;[&quot; or &quot;]&quot; to appear in atext. Not very useful, maybe, but there it is.
	 * <p>
	 * The address: <p><tt>[Kayaks] &lt;kayaks@kayaks.org&gt;</tt> ...is not valid. It should be: <P><tt>&quot;[Kayaks]&quot; &lt;kayaks@kayaks.org&gt;</tt>
	 * <p>
	 * If this criteria is not included, the parser will act per 2822 and will require the quotes; if included, it will allow them to be missing.
	 * <p>
	 * One real-world example seen:
	 * <p>
	 * Bob Smith [mailto:bsmith@gmail.com]=20
	 * <p>
	 * Use at your own risk. There may be some issue with enabling this feature in conjunction with {@link #ALLOW_DOMAIN_LITERALS}, but i haven't looked into
	 * that. If the <code>ALLOW_DOMAIN_LITERALS</code> criteria is not included, I think this should be pretty safe. Whether or not it's useful, that's up to
	 * you.
	 */
	ALLOW_SQUARE_BRACKETS_IN_A_TEXT,

	/**
	 * This criteria allows as per RFC 2822 &quot;)&quot; or &quot;(&quot; to appear in quoted versions of the localpart (they are never allowed in unquoted
	 * versions)
	 * <p>
	 * You can disallow it, but better to include this criteria. I left this hanging around (from an earlier incarnation of the code) as a random option you can
	 * switch off. No, it's not necssarily useful. Long story.
	 * <p>
	 * If this criteria is not included, it will prevent such addresses from being valid, even though they are: &quot;bob(hi)smith&quot;@test.com
	 */
	ALLOW_PARENS_IN_LOCALPART;

	/**
	 * The default setting is not strictly 2822 compliant. For example, it does not include the {@link #ALLOW_DOMAIN_LITERALS} criteria, which results in
	 * exclusions on single domains. Useful for cleaning up email strings that other middleware (ie. the next server) will be able to understand.
	 * <p>
	 * Included in the defaults are: <ul> <li>{@link #ALLOW_QUOTED_IDENTIFIERS}</li> <li>{@link #ALLOW_PARENS_IN_LOCALPART}</li> </ul>.
	 */
	public static final EnumSet<EmailAddressCriteria> DEFAULT = of(ALLOW_QUOTED_IDENTIFIERS, ALLOW_PARENS_IN_LOCALPART);

	/**
	 * Criteria which is most RFC 2822 compliant and allows all compliant address forms, including the more exotic ones. Most useful for validating the broadest
	 * range of email address that should be allowed within the boundaries of RFC compliancy.
	 */
	public static final EnumSet<EmailAddressCriteria> RFC_COMPLIANT = EnumSet.allOf(EmailAddressCriteria.class);
}