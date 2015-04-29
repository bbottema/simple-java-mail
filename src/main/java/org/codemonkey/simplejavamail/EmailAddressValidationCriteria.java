package org.codemonkey.simplejavamail;

/**
 * Defines a set of restriction flags for email address validation. To remain completely true to RFC 2822, all flags should be set to
 * <code>true</code>.
 * 
 * @author Benny Bottema
 * @see #EmailAddressValidationCriteria(boolean, boolean)
 */
public class EmailAddressValidationCriteria {

	private final boolean allowDomainLiterals;
	private final boolean allowQuotedIdentifiers;

	/**
	 * Criteria which is most RFC 2822 compliant and allows all compiant address forms, including the more exotic ones.
	 * 
	 * @see #EmailAddressValidationCriteria(boolean, boolean)
	 */
	public static final EmailAddressValidationCriteria RFC_COMPLIANT = new EmailAddressValidationCriteria(true, true);

	/**
	 * @param allowDomainLiterals <ul>
	 *            <li>This flag states whether domain literals are allowed in the email address, e.g.:
	 *            <p>
	 *            <tt>someone@[192.168.1.100]</tt> or <br/>
	 *            <tt>john.doe@[23:33:A2:22:16:1F]</tt> or <br/>
	 *            <tt>me@[my computer]</tt>
	 *            </p>
	 *            <p>
	 *            The RFC says these are valid email addresses, but most people don't like allowing them. If you don't want to allow them,
	 *            and only want to allow valid domain names (<a href="http://www.ietf.org/rfc/rfc1035.txt">RFC 1035</a>, x.y.z.com, etc),
	 *            change this constant to <tt>false</tt>.
	 *            <p>
	 *            Its default value is <tt>true</tt> to remain RFC 2822 compliant, but you should set it depending on what you need for your
	 *            application.</li>
	 *            </ul>
	 * @param allowQuotedIdentifiers <ul>
	 *            <li>This flag states whether quoted identifiers are allowed (using quotes and angle brackets around the raw address) are
	 *            allowed, e.g.:
	 *            <p>
	 *            <tt>"John Smith" &lt;john.smith@somewhere.com&gt;</tt>
	 *            <p>
	 *            The RFC says this is a valid mailbox. If you don't want to allow this, because for example, you only want users to enter
	 *            in a raw address (<tt>john.smith@somewhere.com</tt> - no quotes or angle brackets), then change this constant to
	 *            <tt>false</tt>.
	 *            <p>
	 *            Its default value is <tt>true</tt> to remain RFC 2822 compliant, but you should set it depending on what you need for your
	 *            application.</li>
	 *            </ul>
	 */
	public EmailAddressValidationCriteria(boolean allowDomainLiterals, boolean allowQuotedIdentifiers) {
		this.allowDomainLiterals = allowDomainLiterals;
		this.allowQuotedIdentifiers = allowQuotedIdentifiers;
	}

	public final boolean isAllowDomainLiterals() {
		return allowDomainLiterals;
	}

	public final boolean isAllowQuotedIdentifiers() {
		return allowQuotedIdentifiers;
	}
}