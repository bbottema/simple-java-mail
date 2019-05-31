package org.simplejavamail.converter.internal.mimemessage;

import com.sun.mail.smtp.SMTPMessage;

import javax.activation.DataHandler;
import javax.annotation.Nonnull;
import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Header;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetHeaders;
import javax.mail.internet.MimeMessage;
import javax.mail.search.SearchTerm;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Enumeration;

/**
 * This class heps us use methods from SMTPMessage, while retaining an original MimeMessage as a delegate for everything else.
 * <p>
 * Specifically, the envelopeFrom is used from SMTPMessage, so we can specify a bounceTo address on email level, rather than
 * Session level.
 */
public class SMTPMessageProxy extends SMTPMessage {

	@Nonnull
	private final MimeMessage delegate;

	SMTPMessageProxy(@Nonnull final MimeMessage delegate) {
		super((Session) null);
		this.delegate = delegate;
	}

	@Nonnull
	public MimeMessage getDelegate() {
		return delegate;
	}

	@Override
	public void setEnvelopeFrom(final String from) {
		super.setEnvelopeFrom(from);
	}

	@Override
	public String getEnvelopeFrom() {
		return super.getEnvelopeFrom();
	}

	@Override
	public void setNotifyOptions(final int options) {
		throw new AssertionError("Only envelopeFrom is supported, other methods require clear and explicit use cases");
	}

	@Override
	public int getNotifyOptions() {
		throw new AssertionError("Only envelopeFrom is supported, other methods require clear and explicit use cases");
	}

	@Override
	public void setReturnOption(final int option) {
		throw new AssertionError("Only envelopeFrom is supported, other methods require clear and explicit use cases");
	}

	@Override
	public int getReturnOption() {
		throw new AssertionError("Only envelopeFrom is supported, other methods require clear and explicit use cases");
	}

	@Override
	public void setAllow8bitMIME(final boolean allow) {
		throw new AssertionError("Only envelopeFrom is supported, other methods require clear and explicit use cases");
	}

	@Override
	public boolean getAllow8bitMIME() {
		//noinspection SimplifiableConditionalExpression
		return (delegate instanceof SMTPMessage)
				? ((SMTPMessage) delegate).getAllow8bitMIME()
				: false; // this value makes sure this proxy has no side-effects
	}

	@Override
	public void setSendPartial(final boolean partial) {
		throw new AssertionError("Only envelopeFrom is supported, other methods require clear and explicit use cases");
	}

	@Override
	public boolean getSendPartial() {
		//noinspection SimplifiableConditionalExpression
		return (delegate instanceof SMTPMessage)
				? ((SMTPMessage) delegate).getSendPartial()
				: false; // this value makes sure this proxy has no side-effects
	}

	@Override
	public void setSubmitter(final String submitter) {
		throw new AssertionError("Only envelopeFrom is supported, other methods require clear and explicit use cases");
	}

	@Override
	public String getSubmitter() {
		return (delegate instanceof SMTPMessage)
				? ((SMTPMessage) delegate).getSubmitter()
				: null; // this value makes sure this proxy has no side-effects
	}

	@Override
	public void setMailExtension(final String extension) {
		throw new AssertionError("Only envelopeFrom is supported, other methods require clear and explicit use cases");
	}

	@Override
	public String getMailExtension() {
		return (delegate instanceof SMTPMessage)
				? ((SMTPMessage) delegate).getMailExtension()
				: null; // this value makes sure this proxy has no side-effects
	}

	@Override
	public void setMessageNumber(final int msgnum) {
		throw new AssertionError("This method should not be used, nor can it be supported as this method is protected in the delegate");
	}

	@Override
	public void setExpunged(final boolean expunged) {
		throw new AssertionError("This method should not be used, nor can it be supported as this method is protected in the delegate");
	}

	@Override
	public void parse(final InputStream is) {
		throw new AssertionError("This method should not be used, nor can it be supported as this method is protected in the delegate");
	}

	@Override
	public InputStream getContentStream() {
		throw new AssertionError("This method should not be used, nor can it be supported as this method is protected in the delegate");
	}

	@Override
	public void updateMessageID() {
		throw new AssertionError("This method should not be used, nor can it be supported as this method is protected in the delegate");
	}

	@Override
	public void updateHeaders() {
		throw new AssertionError("This method should not be used, nor can it be supported as this method is protected in the delegate");
	}

	@Override
	public InternetHeaders createInternetHeaders(final InputStream is) {
		throw new AssertionError("This method should not be used, nor can it be supported as this method is protected in the delegate");
	}

	@Override
	public MimeMessage createMimeMessage(final Session session) {
		throw new AssertionError("This method should not be used, nor can it be supported as this method is protected in the delegate");
	}

	@Override
	public Address[] getFrom()
			throws MessagingException {
		return delegate.getFrom();
	}

	@Override
	public void setFrom(final Address address)
			throws MessagingException {
		delegate.setFrom(address);
	}

	@Override
	public void setFrom(final String address)
			throws MessagingException {
		delegate.setFrom(address);
	}

	@Override
	public void setFrom()
			throws MessagingException {
		delegate.setFrom();
	}

	@Override
	public void addFrom(final Address[] addresses)
			throws MessagingException {
		delegate.addFrom(addresses);
	}

	@Override
	public Address getSender()
			throws MessagingException {
		return delegate.getSender();
	}

	@Override
	public void setSender(final Address address)
			throws MessagingException {
		delegate.setSender(address);
	}

	@Override
	public Address[] getRecipients(final Message.RecipientType type)
			throws MessagingException {
		return delegate.getRecipients(type);
	}

	@Override
	public Address[] getAllRecipients()
			throws MessagingException {
		return delegate.getAllRecipients();
	}

	@Override
	public void setRecipients(final Message.RecipientType type, final Address[] addresses)
			throws MessagingException {
		delegate.setRecipients(type, addresses);
	}

	@Override
	public void setRecipients(final Message.RecipientType type, final String addresses)
			throws MessagingException {
		delegate.setRecipients(type, addresses);
	}

	@Override
	public void addRecipients(final Message.RecipientType type, final Address[] addresses)
			throws MessagingException {
		delegate.addRecipients(type, addresses);
	}

	@Override
	public void addRecipients(final Message.RecipientType type, final String addresses)
			throws MessagingException {
		delegate.addRecipients(type, addresses);
	}

	@Override
	public Address[] getReplyTo()
			throws MessagingException {
		return delegate.getReplyTo();
	}

	@Override
	public void setReplyTo(final Address[] addresses)
			throws MessagingException {
		delegate.setReplyTo(addresses);
	}

	@Override
	public String getSubject()
			throws MessagingException {
		return delegate.getSubject();
	}

	@Override
	public void setSubject(final String subject)
			throws MessagingException {
		delegate.setSubject(subject);
	}

	@Override
	public void setSubject(final String subject, final String charset)
			throws MessagingException {
		delegate.setSubject(subject, charset);
	}

	@Override
	public Date getSentDate()
			throws MessagingException {
		return delegate.getSentDate();
	}

	@Override
	public void setSentDate(final Date d)
			throws MessagingException {
		delegate.setSentDate(d);
	}

	@Override
	public Date getReceivedDate()
			throws MessagingException {
		return delegate.getReceivedDate();
	}

	@Override
	public int getSize()
			throws MessagingException {
		return delegate.getSize();
	}

	@Override
	public int getLineCount()
			throws MessagingException {
		return delegate.getLineCount();
	}

	@Override
	public String getContentType()
			throws MessagingException {
		return delegate.getContentType();
	}

	@Override
	public boolean isMimeType(final String mimeType)
			throws MessagingException {
		return delegate.isMimeType(mimeType);
	}

	@Override
	public String getDisposition()
			throws MessagingException {
		return delegate.getDisposition();
	}

	@Override
	public void setDisposition(final String disposition)
			throws MessagingException {
		delegate.setDisposition(disposition);
	}

	@Override
	public String getEncoding()
			throws MessagingException {
		return delegate.getEncoding();
	}

	@Override
	public String getContentID()
			throws MessagingException {
		return delegate.getContentID();
	}

	@Override
	public void setContentID(final String cid)
			throws MessagingException {
		delegate.setContentID(cid);
	}

	@Override
	public String getContentMD5()
			throws MessagingException {
		return delegate.getContentMD5();
	}

	@Override
	public void setContentMD5(final String md5)
			throws MessagingException {
		delegate.setContentMD5(md5);
	}

	@Override
	public String getDescription()
			throws MessagingException {
		return delegate.getDescription();
	}

	@Override
	public void setDescription(final String description)
			throws MessagingException {
		delegate.setDescription(description);
	}

	@Override
	public void setDescription(final String description, final String charset)
			throws MessagingException {
		delegate.setDescription(description, charset);
	}

	@Override
	public String[] getContentLanguage()
			throws MessagingException {
		return delegate.getContentLanguage();
	}

	@Override
	public void setContentLanguage(final String[] languages)
			throws MessagingException {
		delegate.setContentLanguage(languages);
	}

	@Override
	public String getMessageID()
			throws MessagingException {
		return delegate.getMessageID();
	}

	@Override
	public String getFileName()
			throws MessagingException {
		return delegate.getFileName();
	}

	@Override
	public void setFileName(final String filename)
			throws MessagingException {
		delegate.setFileName(filename);
	}

	@Override
	public InputStream getInputStream()
			throws IOException, MessagingException {
		return delegate.getInputStream();
	}

	@Override
	public InputStream getRawInputStream()
			throws MessagingException {
		return delegate.getRawInputStream();
	}

	@Override
	public DataHandler getDataHandler()
			throws MessagingException {
		return delegate.getDataHandler();
	}

	@Override
	public Object getContent()
			throws IOException, MessagingException {
		return delegate.getContent();
	}

	@Override
	public void setDataHandler(final DataHandler dh)
			throws MessagingException {
		delegate.setDataHandler(dh);
	}

	@Override
	public void setContent(final Object o, final String type)
			throws MessagingException {
		delegate.setContent(o, type);
	}

	@Override
	public void setText(final String text)
			throws MessagingException {
		delegate.setText(text);
	}

	@Override
	public void setText(final String text, final String charset)
			throws MessagingException {
		delegate.setText(text, charset);
	}

	@Override
	public void setText(final String text, final String charset, final String subtype)
			throws MessagingException {
		delegate.setText(text, charset, subtype);
	}

	@Override
	public void setContent(final Multipart mp)
			throws MessagingException {
		delegate.setContent(mp);
	}

	@Override
	public Message reply(final boolean replyToAll)
			throws MessagingException {
		return delegate.reply(replyToAll);
	}

	@Override
	public Message reply(final boolean replyToAll, final boolean setAnswered)
			throws MessagingException {
		return delegate.reply(replyToAll, setAnswered);
	}

	@Override
	public void writeTo(final OutputStream os)
			throws IOException, MessagingException {
		delegate.writeTo(os);
	}

	@Override
	public void writeTo(final OutputStream os, final String[] ignoreList)
			throws IOException, MessagingException {
		delegate.writeTo(os, ignoreList);
	}

	@Override
	public String[] getHeader(final String name)
			throws MessagingException {
		return delegate.getHeader(name);
	}

	@Override
	public String getHeader(final String name, final String delimiter)
			throws MessagingException {
		return delegate.getHeader(name, delimiter);
	}

	@Override
	public void setHeader(final String name, final String value)
			throws MessagingException {
		delegate.setHeader(name, value);
	}

	@Override
	public void addHeader(final String name, final String value)
			throws MessagingException {
		delegate.addHeader(name, value);
	}

	@Override
	public void removeHeader(final String name)
			throws MessagingException {
		delegate.removeHeader(name);
	}

	@Override
	public Enumeration<Header> getAllHeaders()
			throws MessagingException {
		return delegate.getAllHeaders();
	}

	@Override
	public Enumeration<Header> getMatchingHeaders(final String[] names)
			throws MessagingException {
		return delegate.getMatchingHeaders(names);
	}

	@Override
	public Enumeration<Header> getNonMatchingHeaders(final String[] names)
			throws MessagingException {
		return delegate.getNonMatchingHeaders(names);
	}

	@Override
	public void addHeaderLine(final String line)
			throws MessagingException {
		delegate.addHeaderLine(line);
	}

	@Override
	public Enumeration<String> getAllHeaderLines()
			throws MessagingException {
		return delegate.getAllHeaderLines();
	}

	@Override
	public Enumeration<String> getMatchingHeaderLines(final String[] names)
			throws MessagingException {
		return delegate.getMatchingHeaderLines(names);
	}

	@Override
	public Enumeration<String> getNonMatchingHeaderLines(final String[] names)
			throws MessagingException {
		return delegate.getNonMatchingHeaderLines(names);
	}

	@Override
	public Flags getFlags()
			throws MessagingException {
		return delegate.getFlags();
	}

	@Override
	public boolean isSet(final Flags.Flag flag)
			throws MessagingException {
		return delegate.isSet(flag);
	}

	@Override
	public void setFlags(final Flags flag, final boolean set)
			throws MessagingException {
		delegate.setFlags(flag, set);
	}

	@Override
	public void saveChanges()
			throws MessagingException {
		delegate.saveChanges();
	}

	@Override
	public Session getSession() {
		return delegate.getSession();
	}

	@Override
	public void setRecipient(final Message.RecipientType type, final Address address)
			throws MessagingException {
		delegate.setRecipient(type, address);
	}

	@Override
	public void addRecipient(final Message.RecipientType type, final Address address)
			throws MessagingException {
		delegate.addRecipient(type, address);
	}

	@Override
	public void setFlag(final Flags.Flag flag, final boolean set)
			throws MessagingException {
		delegate.setFlag(flag, set);
	}

	@Override
	public int getMessageNumber() {
		return delegate.getMessageNumber();
	}

	@Override
	public Folder getFolder() {
		return delegate.getFolder();
	}

	@Override
	public boolean isExpunged() {
		return delegate.isExpunged();
	}

	@Override
	public boolean match(final SearchTerm term)
			throws MessagingException {
		return delegate.match(term);
	}
}
