package org.hazlewood.connor.bottema.emailaddress;

import javax.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.regex.Matcher;

/**
 * A utility class to parse, clean up, and extract email addresses from messages per RFC2822 syntax. Designed to integrate with Javamail (this class will
 * require that you have a javamail mail.jar in your classpath), but you could easily change the existing methods around to not use Javamail at all. For
 * example, if you're changing the code, see the difference between getInternetAddress and getDomain: the latter doesn't depend on any javamail code. This is
 * all a by-product of what this class was written for, so feel free to modify it to suit your needs.
 * <p/>
 * <hr /> Regarding the parameter <code>extractCfwsPersonalNames</code>:
 * <p/>
 * This criteria controls the behavior of getInternetAddress and extractHeaderAddresses. If included, it allows the
 * not-totally-kosher-but-happens-in-the-real-world practice of:
 * <p/>
 * &lt;bob@example.com&gt; (Bob Smith)
 * <p/>
 * In this case, &quot;Bob Smith&quot; is not techinically the personal name, just a comment. If this is included, the methods will convert this into: Bob Smith
 * &lt;bob@example.com&gt;
 * <p/>
 * This also happens somewhat more often and appropriately with
 * <p/>
 * <tt>mailer-daemon@blah.com (Mail Delivery System)</tt>
 * <p/>
 * If a personal name appears to the left and CFWS appears to the right of an address, the methods will favor the personal name to the left. If the methods need
 * to use the CFWS following the address, they will take the first comment token they find. <P>e.g.: <P><tt>"bob smith" &lt;bob@example.com&gt; (Bobby)</tt>
 * <br> will yield personal name &quot;bob smith&quot; <br><tt>&lt;bob@example.com&gt; (Bobby)</tt> <br>will yield personal name &quot;Bobby&quot;
 * <br><tt>bob@example.com (Bobby)</tt> <br>will yield personal name &quot;Bobby&quot; <br><tt>bob@example.com (Bob) (Smith)</tt> <br>will yield personal name
 * &quot;Bob&quot; <hr />
 */
public final class EmailAddressParser {
	/**
	 * Private constructor; this is a utility class with static methods only, not designed for extension.
	 */
	private EmailAddressParser() {
		//
	}

	/**
	 * Tells us if the email represents a valid return path header string.
	 * <p/>
	 * NOTE: legit forms like &lt;(comment here)&gt; will return true.
	 * <p/>
	 * You can check isValidReturnPath(), and if it is true, and if getInternetAddress() returns null, you know you have a DSN, whether it be an empty return
	 * path or one with only CFWS inside the brackets (which is legit, as demonstated above). Note that you can also simply call getReturnPathAddress() to have
	 * that operation done for you. <P>Note that &lt;&quot;&quot;&gt; is <b>not</b> a valid return-path.
	 */
	public static boolean isValidReturnPath(String email, EnumSet<EmailAddressCriteria> criteria) {
		return (email != null) && Dragons.fromCriteria(criteria).RETURN_PATH_PATTERN.matcher(email).matches();
	}

	/**
	 * WARNING: You may want to use getReturnPathAddress() instead if you're looking for a clean version of the return path without CFWS, etc. See that
	 * documentation first!
	 * <p/>
	 * Pull whatever's inside the angle brackets out, without alteration or cleaning. This is more secure than a simple substring() since paths like:
	 * <P><tt>&lt;(my &gt; path) &gt;</tt> <P>...are legal return-paths and may throw a simpler parser off. However this method will return <b>all</b> CFWS
	 * (comments, whitespace) that may be between the brackets as well. So the example above will return: <P><tt>(my &gt; path)_</tt> <br>(where the _ is the
	 * trailing space from the original string)
	 */
	public static String getReturnPathBracketContents(String email, EnumSet<EmailAddressCriteria> criteria) {
		if (email == null) {
			return (null);
		}
		Matcher m = Dragons.fromCriteria(criteria).RETURN_PATH_PATTERN.matcher(email);
		if (m.matches()) {
			return (m.group(1));
		} else {
			return (null);
		}
	}

	/**
	 * Pull out the cleaned-up return path address. May return an empty string. Will require two parsings due to an inefficiency.
	 *
	 * @param extractCfwsPersonalNames See {@link EmailAddressParser}
	 * @return null if there are any syntax issues or other weirdness, otherwise the valid, trimmed return path email address without CFWS, surrounding angle
	 * brackets, with quotes stripped where possible, etc. (may return an empty string).
	 */
	public static String getReturnPathAddress(String email, EnumSet<EmailAddressCriteria> criteria, boolean extractCfwsPersonalNames) {
		if (email == null) {
			return (null);
		}
		// inefficient, but there is no parallel grammar tree to extract the return path
		// accurately:
		if (isValidReturnPath(email, criteria)) {
			InternetAddress ia = getInternetAddress(email, criteria, extractCfwsPersonalNames);
			if (ia == null) {
				return ("");
			} else {
				return (ia.getAddress());
			}
		} else {
			return (null);
		}
	}

	/**
	 * Tells us if a header line is valid, i.e. checks for a 2822 mailbox-list (which could only have one address in it, or might have more.) Applicable to From
	 * or Resent-From headers <b>only</b>.
	 * <p/>
	 * This method seems quick enough so far, but I'm not totally convinced it couldn't be slow given a complicated near-miss string. You may just want to call
	 * extractHeaderAddresses() instead, unless you must confirm that the format is perfect. I think that in 99.9999% of real-world cases this method will work
	 * fine.
	 * <p/>
	 *
	 * @see #isValidAddressList(String, EnumSet)
	 */
	public static boolean isValidMailboxList(String header_txt, EnumSet<EmailAddressCriteria> criteria) {
		return (Dragons.fromCriteria(criteria).MAILBOX_LIST_PATTERN.matcher(header_txt).matches());
	}

	/**
	 * Tells us if a header line is valid, i.e. a 2822 address-list (which could only have one address in it, or might have more.) Applicable to To, Cc, Bcc,
	 * Reply-To, Resent-To, Resent-Cc, and Resent-Bcc headers <b>only</b>.
	 * <p/>
	 * This method seems quick enough so far, but I'm not totally convinced it couldn't be slow given a complicated near-miss string. You may just want to call
	 * extractHeaderAddresses() instead, unless you must confirm that the format is perfect. I think that in 99.9999% of real-world cases this method will work
	 * fine and quickly enough. Let me know what your testing reveals.
	 * <p/>
	 *
	 * @see #isValidMailboxList(String, EnumSet)
	 */
	public static boolean isValidAddressList(String header_txt, EnumSet<EmailAddressCriteria> criteria) {
		// creating the actual ADDRESS_LIST_PATTERN string proved too large for java, but
		// forutnately we can use this alternative FSM to check. Since the address pattern
		// is greedy, it will match all CFWS up to the comma which we can then require easily.
		boolean valid = false;
		Matcher m = Dragons.fromCriteria(criteria).ADDRESS_PATTERN.matcher(header_txt);
		int max = header_txt.length();
		while (m.lookingAt()) {
			if (m.end() == max) {
				valid = true;
				break;
			} else {
				valid = false;
				if (header_txt.charAt(m.end()) == ',') {
					m.region(m.end() + 1, max);
					continue;
				} else {
					break;
				}
			}
		}
		return (valid);
		// return(ADDRESS_LIST_PATTERN.matcher(header_txt).matches());
	}

	/**
	 * Given a 2822-valid single address string, give us an InternetAddress object holding that address, otherwise returns null. The email address that comes
	 * back from the resulting InternetAddress object's getAddress() call will have comments and unnecessary quotation marks or whitespace removed.
	 * <p/>
	 * If your String is an email header, you should probably use extractHeaderAddresses instead, since most headers can have multiple addresses in them. (see
	 * that method for more info.) This method will indeed fail if you use it on a header line with more than one address.
	 * <p/>
	 * Exception: You CAN and should use this for the Sender header, and probably you want to use it for the X-Original-To as well.
	 * <p/>
	 * Another exception: You can use this for the Return-Path, but if you want to know that a Return-Path is valid and you want to extract it, you will have to
	 * call both this method and isValidReturnPath; this operation can be done for you by simply calling getReturnPathAddress() instead of this method. In terms
	 * of this method's application to the return-path, note that the common valid Return-Path value &lt;&gt; will return null. So will the illegitimate
	 * &quot;&quot; or legitimate empty-string, but other illegitimate Return-Paths like <P><tt>&quot;hi&quot; &lt;bob@smith.com&gt;</tt> <P>will return an
	 * address, so the moral is that you may want to check isValidReturnPath() first, if you care. This method is useful if you trust the return path and want
	 * to extract a clean address from it without CFWS (getReturnPathBracketContents() will return any CFWS), or if you want to determine if a validated return
	 * path actually contains an address in it and isn't just empty or full of CFWS. Except for empty return paths (those lacking an address) the Return-Path
	 * specification is a subset of valid 2822 addresses, so this method will work on all non-empty return-paths, failing only on the empty ones.
	 * <p/>
	 * In general for this method, note: although this method does not use InternetAddress to parse/extract the information, it does ensure that InternetAddress
	 * can use the results (i.e. that there are no encoding issues), but note that an InternetAddress object can hold (and use) values for the address which it
	 * could not have parsed itself. Thus, it's possible that for InternetAddress addr, which came as the result of this method, the following may throw an
	 * exception <b>or</b> may silently fail:<BR> InternetAddress addr2 = InternetAddress.parse(addr.toString());
	 * <p/>
	 * The InternetAddress objects returned by this method will not do any decoding of RFC-2047 encoded personal names. See the documentation for this overall
	 * class (above) for more.
	 * <p/>
	 * Again, all other uses of that addr object should work OK. It is recommended that if you are using this class that you never create an InternetAddress
	 * object using InternetAddress's own constructors or parsing methods; rather, retrieve them through this class. Perhaps the addr.clone() would work OK,
	 * though.
	 * <p/>
	 * The personal name will include any and all phrase token(s) to the left of the address, if they exist, and the string will be trim()'ed, but note that
	 * InternetAddress, when generating the getPersonal() result or the toString() result, if it encounters any quotes or backslashes in the personal name
	 * String, will put the entire thing in a big quoted-escaped chunk.
	 * <p/>
	 * This will do some smart unescaping to prevent that from happening unnecessarily; specifically, if there are unecessary quotes around a personal name, it
	 * will remove them. E.g.
	 * <p/>
	 * "Bob" &lt;bob@hi.com&gt; <br>becomes: <BR>Bob &lt;bob@hi.com&gt;
	 * <p/>
	 * (apologies to bob@hi.com for everything i've done to him)
	 *
	 * @param extractCfwsPersonalNames See {@link EmailAddressParser}
	 */
	public static InternetAddress getInternetAddress(String email, EnumSet<EmailAddressCriteria> criteria, boolean extractCfwsPersonalNames) {
		if (email == null) {
			return (null);
		}
		Matcher m = Dragons.fromCriteria(criteria).MAILBOX_PATTERN.matcher(email);
		if (m.matches()) {
			return (pullFromGroups(m, criteria, extractCfwsPersonalNames));
		} else {
			return (null);
		}
	}

	/**
	 * See getInternetAddress; does the same thing but returns the constituent parts of the address in a three-element array (or null if the address is
	 * invalid).
	 * <p/>
	 * This may be useful because even with cleaned-up address extracted with this class the parsing to achieve this is not trivial.
	 * <p/>
	 * To actually use these values in an email, you should construct an InternetAddress object (or equivalent) which can handle the various quoting, adding of
	 * the angle brackets around the address, etc., necessary for presenting the whole address.
	 * <p/>
	 * To construct the email address, you can safely use: <BR>result[1] + &quot;@&quot; + result[2]
	 * <p/>
	 *
	 * @param extractCfwsPersonalNames See {@link EmailAddressParser}
	 * @return a three-element array containing the personal name String, local part String, and the domain part String of the address, in that order, without
	 * the @; will return null if the address is invalid; if it is valid this will not return null but the personal name (at index 0) may be null
	 */
	public static String[] getAddressParts(String email, EnumSet<EmailAddressCriteria> criteria, boolean extractCfwsPersonalNames) {
		if (email == null) {
			return (null);
		}
		Matcher m = Dragons.fromCriteria(criteria).MAILBOX_PATTERN.matcher(email);
		if (m.matches()) {
			return (getMatcherParts(m, criteria, extractCfwsPersonalNames));
		} else {
			return (null);
		}
	}

	/**
	 * See getInternetAddress; does the same thing but returns the personal name that would have been returned from getInternetAddress() in String form.
	 * <p/>
	 * The Strings returned by this method will not reflect any decoding of RFC-2047 encoded personal names. See the documentation for this overall class
	 * (above) for more.
	 *
	 * @param extractCfwsPersonalNames See {@link EmailAddressParser}
	 */
	public static String getPersonalName(String email, EnumSet<EmailAddressCriteria> criteria, boolean extractCfwsPersonalNames) {
		if (email == null) {
			return (null);
		}
		Matcher m = Dragons.fromCriteria(criteria).MAILBOX_PATTERN.matcher(email);
		if (m.matches()) {
			return (getMatcherParts(m, criteria, extractCfwsPersonalNames)[0]);
		} else {
			return (null);
		}
	}

	/**
	 * See getInternetAddress; does the same thing but returns the local part that would have been returned from getInternetAddress() in String form
	 * (essentially, the part to the left of the @). This may be useful because a simple search/split on a &quot;@&quot; is not a safe way to do this, given
	 * escaped quoted strings, etc.
	 *
	 * @param extractCfwsPersonalNames See {@link EmailAddressParser}
	 */
	public static String getLocalPart(String email, EnumSet<EmailAddressCriteria> criteria, boolean extractCfwsPersonalNames) {
		if (email == null) {
			return (null);
		}
		Matcher m = Dragons.fromCriteria(criteria).MAILBOX_PATTERN.matcher(email);
		if (m.matches()) {
			return (getMatcherParts(m, criteria, extractCfwsPersonalNames)[1]);
		} else {
			return (null);
		}
	}

	/**
	 * See getInternetAddress; does the same thing but returns the domain part in string form (essentially, the part to the right of the @). This may be useful
	 * because a simple search/split on a &quot;@&quot; is not a safe way to do this, given escaped quoted strings, etc.
	 *
	 * @param extractCfwsPersonalNames See {@link EmailAddressParser}
	 */
	public static String getDomain(String email, EnumSet<EmailAddressCriteria> criteria, boolean extractCfwsPersonalNames) {
		if (email == null) {
			return (null);
		}
		Matcher m = Dragons.fromCriteria(criteria).MAILBOX_PATTERN.matcher(email);
		if (m.matches()) {
			return (getMatcherParts(m, criteria, extractCfwsPersonalNames)[2]);
		} else {
			return (null);
		}
	}

	/**
	 * Given the value of a header, like the From:, extract valid 2822 addresses from it and place them in an array. Returns an empty array if none found, will
	 * not return null. Note that you should pass in everything except, e.g. &quot;From: &quot;, in other words, the header value without the header name and
	 * &quot;: &quot; at the start.. The addresses that come back from the resulting InternetAddress objects' getAddress calls will have comments and
	 * unnecessary quotation marks or whitespace removed. If a bad address is encountered, parsing stops, and the good addresses found up until then (if any)
	 * are returned. This is kind of strict and could be improved, but that's the way it is for now. If you need to know if the header is totally valid (not
	 * just up to a certain address) then you can use isValidMailboxList() or isValidAddressList() or isValidMailbox(), depending on the header:
	 * <p/>
	 * This method can handle group addresses, but it does not preseve the group name or the structure of any groups; rather it flattens them all into the same
	 * array. You can call this method on the From or any other header that uses the mailbox-list form (which doesn't use groups), or you can call it on the To,
	 * Cc, Bcc, or Reply-To or any other header which uses the address-list format which might have groups in there. This method doesn't enforce any group
	 * structure syntax either. If you care to test for 2822 validity of a list of addresses (including group format), use the appropriate method. This will
	 * dependably extract addresses from a valid list. If the list is invalid, it may extract them anyway, or it may fail somewhere along the line.
	 * <p/>
	 * You should not use this method on the Return-Path header; instead use getInternetAddress() or getReturnPathAddress() (see that doc for info about
	 * Return-Path). However, you could use this on the Sender header if you didn't care to check it for validity, since single mailboxes are valid subsets of
	 * valid mailbox-lists and address-lists.
	 * <p/>
	 *
	 * @param header_txt               is text from whatever header (not including the header name and &quot;: &quot;. I don't think the String needs to be
	 *                                 unfolded, but i haven't tested that.
	 *                                 <p/>
	 *                                 see getInternetAddress() for more info: this extracts the same way
	 * @param extractCfwsPersonalNames See {@link EmailAddressParser}
	 * @return zero-length array if erorrs or none found, otherwise an array of length &gt; 0 with the addresses as InternetAddresses with the personal name and
	 * emails set correctly (i.e. doesn't rely on InternetAddress parsing for extraction, but does require that the address be usable by InternetAddress,
	 * although re-parsing with InternetAddress may cause exceptions, see getInternetAddress()); will not return null.
	 */
	public static InternetAddress[] extractHeaderAddresses(String header_txt, EnumSet<EmailAddressCriteria> criteria, boolean extractCfwsPersonalNames) {
		// you may go insane from this code
		if (header_txt == null || header_txt.equals("")) {
			return (new InternetAddress[0]);
		}
		// optimize: separate method or boolean to indicate if group should be worried about at all
		Dragons dragons = Dragons.fromCriteria(criteria);
		Matcher m = dragons.MAILBOX_PATTERN.matcher(header_txt);
		Matcher gp = dragons.GROUP_PREFIX_PATTERN.matcher(header_txt);
		ArrayList<InternetAddress> result = new ArrayList<InternetAddress>(1);
		int max = header_txt.length();
		InternetAddress cur_addr;
		boolean group_start = false;
		boolean group_end = false;
		int next_comma_index = -1;
		int next_semicolon_index = -1;
		int just_after_group_end = -1;
		// skip past any group prefixes, gobble addresses as usual in a list but
		// skip past the terminating semicolon
		while (true) {
			if (group_end) {
				next_comma_index = header_txt.indexOf(',', just_after_group_end);
				if (next_comma_index < 0) {
					break;
				}
				if (next_comma_index >= max - 1) {
					break;
				}
				gp.region(next_comma_index + 1, max);
				m.region(next_comma_index + 1, max);
				group_end = false;
			}
			if (header_txt.charAt(m.regionStart()) == ';') {
				group_start = false;
				m.region(m.regionStart() + 1, max);
				// could say >= max - 1 or even max - 3 or something, but just to be
				// proper:
				if (m.regionStart() >= max) {
					break;
				}
				gp.region(m.regionStart(), max);
				group_end = true;
				just_after_group_end = m.regionStart();
			}
			if (m.lookingAt()) {
				group_start = false;
				// must test m.end() == max first with early exit
				if (m.end() == max || header_txt.charAt(m.end()) == ',' ||
						(group_end = (header_txt.charAt(m.end()) == ';'))) {
					cur_addr = pullFromGroups(m, criteria, extractCfwsPersonalNames);
					if (cur_addr != null) {
						result.add(cur_addr);
					}
					if (m.end() < max - 1) {
						if (!group_end) {
							// skip the comma
							gp.region(m.end() + 1, max);
							m.region(m.end() + 1, max);
						} else {
							just_after_group_end = m.end() + 1;
						}
					} else {
						break;
					}
				} else {
					break;
				}
			} else if (gp.lookingAt()) {
				if (gp.end() < max) {
					// the colon is included in the gp match, so nothing to skip
					m.region(gp.end(), max);
					gp.region(gp.end(), max);
					group_start = true;
				} else {
					break;
				}
			} else if (group_start) {
				next_semicolon_index = header_txt.indexOf(';', m.regionStart());
				if (next_semicolon_index < 0) {
					break;
				} else if (next_semicolon_index >= max - 1) {
					break;
				}
				m.region(next_semicolon_index + 1, max);
				gp.region(next_semicolon_index + 1, max);
				group_start = false;
				group_end = true;
				just_after_group_end = m.regionStart();
			} else if (group_end) {
				continue;
			} else {
				break;
			}
		}
		return (result.size() > 0 ? result.toArray(new InternetAddress[result.size()]) : new InternetAddress[0]);
	}

	/**
	 * Using knowledge of the group-ID numbers (see comments at top) pull the data relevant to us from an already-successfully-matched matcher. See doc for
	 * getInternetAddress and extractHeaderAddresses for info re: InternetAddress parsing compatability.
	 * <p/>
	 * You could roll your own method that does what you care about.
	 * <p/>
	 * This should work on the matcher for MAILBOX_LIST_PATTERN or MAILBOX_PATTERN, but only those. With some tweaking it could easily be adapted to some
	 * others.
	 * <p/>
	 * May return null on encoding errors.
	 * <p/>
	 * Also cleans up the address: tries to strip bounding quotes off of the local part without damaging it's parsability (by this class); if it can, do that;
	 * all other cases, don't.
	 * <p/>
	 * e.g. &quot;bob&quot;@example.com becomes bob@example.com
	 *
	 * @param extractCfwsPersonalNames See {@link EmailAddressParser}
	 */
	public static InternetAddress pullFromGroups(Matcher m, EnumSet<EmailAddressCriteria> criteria, boolean extractCfwsPersonalNames) {
		InternetAddress current_ia = null;
		String[] parts = getMatcherParts(m, criteria, extractCfwsPersonalNames);
		if (parts[1] == null || parts[2] == null) {
			return (null);
		}
		// if for some reason you want to require that the result be re-parsable by
		// InternetAddress, you
		// could uncomment the appropriate stuff below, but note that not all the utility
		// functions use pullFromGroups; some call getMatcherParts directly.
		try {
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
		catch (UnsupportedEncodingException uee) {
			current_ia = null;
		}
		return (current_ia);
	}

	/**
	 * See {@link #pullFromGroups(Matcher, EnumSet, boolean)}.
	 *
	 * @param extractCfwsPersonalNames See {@link EmailAddressParser}
	 * @return will not return null
	 */
	public static String[] getMatcherParts(Matcher m, EnumSet<EmailAddressCriteria> criteria, boolean extractCfwsPersonalNames) {
		String current_localpart = null;
		String current_domainpart = null;
		String local_part_da = null;
		String local_part_qs = null;
		String domain_part_da = null;
		String domain_part_dl = null;
		String personal_string = null;
		// see the group-ID lists in the grammar comments
		final boolean allowDomainLiterals = criteria.contains(EmailAddressCriteria.ALLOW_DOMAIN_LITERALS);
		if (criteria.contains(EmailAddressCriteria.ALLOW_QUOTED_IDENTIFIERS)) {
			if (allowDomainLiterals) {
				// yes quoted identifiers, yes domain literals
				if (m.group(1) != null) {
					// name-addr form
					local_part_da = m.group(5);
					if (local_part_da == null) {
						local_part_qs = m.group(6);
					}
					domain_part_da = m.group(7);
					if (domain_part_da == null) {
						domain_part_dl = m.group(8);
					}
					current_localpart = (local_part_da == null ? local_part_qs : local_part_da);
					current_domainpart = (domain_part_da == null ? domain_part_dl : domain_part_da);
					personal_string = m.group(2);
					if (personal_string == null && extractCfwsPersonalNames) {
						personal_string = m.group(9);
						personal_string = removeAnyBounding('(', ')', getFirstComment(personal_string, criteria));
					}
				} else if (m.group(10) != null) {
					// addr-spec form
					local_part_da = m.group(12);
					if (local_part_da == null) {
						local_part_qs = m.group(13);
					}
					domain_part_da = m.group(14);
					if (domain_part_da == null) {
						domain_part_dl = m.group(15);
					}
					current_localpart = (local_part_da == null ? local_part_qs : local_part_da);
					current_domainpart = (domain_part_da == null ? domain_part_dl : domain_part_da);
					if (extractCfwsPersonalNames) {
						personal_string = m.group(16);
						personal_string = removeAnyBounding('(', ')', getFirstComment(personal_string, criteria));
					}
				}
			} else {
				// yes quoted identifiers, no domain literals
				if (m.group(1) != null) {
					// name-addr form
					local_part_da = m.group(5);
					if (local_part_da == null) {
						local_part_qs = m.group(6);
					}
					current_localpart = (local_part_da == null ? local_part_qs : local_part_da);
					current_domainpart = m.group(7);
					personal_string = m.group(2);
					if (personal_string == null && extractCfwsPersonalNames) {
						personal_string = m.group(8);
						personal_string = removeAnyBounding('(', ')', getFirstComment(personal_string, criteria));
					}
				} else if (m.group(9) != null) {
					// addr-spec form
					local_part_da = m.group(11);
					if (local_part_da == null) {
						local_part_qs = m.group(12);
					}
					current_localpart = (local_part_da == null ? local_part_qs : local_part_da);
					current_domainpart = m.group(13);
					if (extractCfwsPersonalNames) {
						personal_string = m.group(14);
						personal_string = removeAnyBounding('(', ')', getFirstComment(personal_string, criteria));
					}
				}
			}
		} else {
			// no quoted identifiers, yes|no domain literals
			local_part_da = m.group(3);
			if (local_part_da == null) {
				local_part_qs = m.group(4);
			}
			domain_part_da = m.group(5);
			if (domain_part_da == null && allowDomainLiterals) {
				domain_part_dl = m.group(6);
			}
			current_localpart = (local_part_da == null ? local_part_qs : local_part_da);
			current_domainpart = (domain_part_da == null ? domain_part_dl : domain_part_da);
			if (extractCfwsPersonalNames) {
				personal_string = m.group((allowDomainLiterals ? 1 : 0) + 6);
				personal_string = removeAnyBounding('(', ')', getFirstComment(personal_string, criteria));
			}
		}
		if (current_localpart != null) {
			current_localpart = current_localpart.trim();
		}
		if (current_domainpart != null) {
			current_domainpart = current_domainpart.trim();
		}
		if (personal_string != null) {
			// trim even though calling cPS which trims, because the latter may return
			// the same thing back without trimming
			personal_string = personal_string.trim();
			personal_string = cleanupPersonalString(personal_string, criteria);
		}
		// remove any unecessary bounding quotes from the localpart:
		String test_addr = removeAnyBounding('"', '"', current_localpart) +
				"@" + current_domainpart;
		if (Dragons.fromCriteria(criteria).ADDR_SPEC_PATTERN.matcher(test_addr).matches()) {
			current_localpart = removeAnyBounding('"', '"', current_localpart);
		}
		return (new String[] { personal_string, current_localpart, current_domainpart });
	}

	/**
	 * Given a string, extract the first matched comment token as defined in 2822, trimmed; return null on all errors or non-findings
	 * <p/>
	 * This is probably not super-useful. Included just in case.
	 * <p/>
	 * Note for future improvement: if COMMENT_PATTERN could handle nested comments, then this should be able to as well, but if this method were to be used to
	 * find the CFWS personal name (see boolean option) then such a nested comment would probably not be the one you were looking for?
	 */
	public static String getFirstComment(String text, EnumSet<EmailAddressCriteria> criteria) {
		if (text == null) {
			return (null); // important
		}
		Matcher m = Dragons.fromCriteria(criteria).COMMENT_PATTERN.matcher(text);
		if (!m.find()) {
			return (null);
		}
		return (m.group().trim()); // trim important
	}

	/**
	 * Given a string, if the string is a quoted string (without CFWS around it, although it will be trimmed) then remove the bounding quotations and then
	 * unescape it. Useful when passing simple named address personal names into InternetAddress since InternetAddress always quotes the entire phrase token
	 * into one mass; in this simple (and common) case, we can strip off the quotes and de-escape, and passing to javamail will result in a cleaner quote-free
	 * result (if there are no embedded escaped characters) or the proper one-level-quoting result (if there are embedded escaped characters). If the string is
	 * anything else, this just returns it unadulterated.
	 */
	public static String cleanupPersonalString(String text, EnumSet<EmailAddressCriteria> criteria) {
		if (text == null) {
			return (null);
		}
		text = text.trim();
		final Dragons dragons = Dragons.fromCriteria(criteria);
		Matcher m = dragons.QUOTED_STRING_WO_CFWS_PATTERN.matcher(text);
		if (!m.matches()) {
			return (text);
		}
		text = removeAnyBounding('"', '"', m.group());
		text = dragons.ESCAPED_BSLASH_PATTERN.matcher(text).replaceAll("\\\\");
		text = dragons.ESCAPED_QUOTE_PATTERN.matcher(text).replaceAll("\"");
		return (text.trim());
	}

	/**
	 * If the string starts and ends with s and e, remove them, otherwise return the string as it was passed in.
	 */
	public static String removeAnyBounding(char s, char e, String str) {
		if (str == null || str.length() < 2) {
			return (str);
		}
		if (str.startsWith(String.valueOf(s)) && str.endsWith(String.valueOf(e))) {
			return (str.substring(1, str.length() - 1));
		} else {
			return (str);
		}
	}
}
