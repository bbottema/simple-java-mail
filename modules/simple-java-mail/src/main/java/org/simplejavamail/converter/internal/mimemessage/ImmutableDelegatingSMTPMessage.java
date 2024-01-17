package org.simplejavamail.converter.internal.mimemessage;

import org.eclipse.angus.mail.smtp.SMTPMessage;
import jakarta.activation.DataHandler;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetHeaders;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.SearchTerm;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Enumeration;

import static java.lang.String.format;

/**
 * This class helps us use methods from SMTPMessage, while retaining an original MimeMessage as a delegate for everything else.
 * <p>
 * Specifically, the envelopeFrom is used from SMTPMessage, so we can specify a bounceTo address on email level, rather than
 * Session level.
 * <p>
 * Also makes sure the MimeMessage delegate is not mutated further.
 */
public class ImmutableDelegatingSMTPMessage extends SMTPMessage {

	private static AssertionError UNSUPPORTED_PROTECTED_METHOD(String s) {
		return new AssertionError(format("This method should not be used, nor can it be supported as this method " +
				"is protected in the delegate. If it is still needed, we need to find a way around: %s", s));
	}
	
	private static AssertionError MUTATION_NOT_SUPPORTED(String s) {
		return new AssertionError(format("Further mutation is not allowed: %s", s));
	}

	@NotNull
	private final MimeMessage delegate;

	ImmutableDelegatingSMTPMessage(@NotNull final MimeMessage delegate, @Nullable final String envelopeFrom) {
		super((Session) null);
		this.delegate = delegate;
		super.setEnvelopeFrom(envelopeFrom);
	}

	@NotNull
	public MimeMessage getDelegate() {
		return delegate;
	}

	@Override
	public boolean getAllow8bitMIME() {
		return (delegate instanceof SMTPMessage) && ((SMTPMessage) delegate).getAllow8bitMIME();
	}

	@Override
	public boolean getSendPartial() {
		return (delegate instanceof SMTPMessage) && ((SMTPMessage) delegate).getSendPartial();
	}

	@Override
	@Nullable
	public String getSubmitter() {
		return (delegate instanceof SMTPMessage) ? ((SMTPMessage) delegate).getSubmitter() : null;
	}

	@Override
	@Nullable
	public String getMailExtension() {
		return (delegate instanceof SMTPMessage) ? ((SMTPMessage) delegate).getMailExtension() : null;
	}

	@Override
	public int getNotifyOptions() {
		return (delegate instanceof SMTPMessage) ? ((SMTPMessage) delegate).getNotifyOptions() : 0;
	}

	@Override
	public int getReturnOption() {
		return (delegate instanceof SMTPMessage) ? ((SMTPMessage) delegate).getReturnOption() : 0;
	}

	@Override
	public Address[] getFrom()
			throws MessagingException {
		return delegate.getFrom();
	}

	@Override
	@Nullable
	public Address getSender()
			throws MessagingException {
		return delegate.getSender();
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
	public Address[] getReplyTo()
			throws MessagingException {
		return delegate.getReplyTo();
	}

	@Override
	public String getSubject()
			throws MessagingException {
		return delegate.getSubject();
	}

	@Override
	public Date getSentDate()
			throws MessagingException {
		return delegate.getSentDate();
	}

	@Override
	@Nullable
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
	public String getEncoding()
			throws MessagingException {
		return delegate.getEncoding();
	}

	@Override
	@Nullable
	public String getContentID()
			throws MessagingException {
		return delegate.getContentID();
	}

	@Override
	@Nullable
	public String getContentMD5()
			throws MessagingException {
		return delegate.getContentMD5();
	}

	@Override
	public String getDescription()
			throws MessagingException {
		return delegate.getDescription();
	}

	@Override
	@Nullable
	public String[] getContentLanguage()
			throws MessagingException {
		return delegate.getContentLanguage();
	}

	@Override
	@Nullable
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
	@Nullable
	public String[] getHeader(final String name)
			throws MessagingException {
		return delegate.getHeader(name);
	}

	@Override
	@Nullable
	public String getHeader(final String name, final String delimiter)
			throws MessagingException {
		return delegate.getHeader(name, delimiter);
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
	public void saveChanges()
			throws MessagingException {
		delegate.saveChanges();
	}

	@Override
	public Session getSession() {
		return delegate.getSession();
	}

	@Override
	public int getMessageNumber() {
		return delegate.getMessageNumber();
	}

	@Override
	@Nullable
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

	@Override
	public void setMessageNumber(final int msgnum) {
		throw UNSUPPORTED_PROTECTED_METHOD("setMessageNumber(int)");
	}

	@Override
	public void setExpunged(final boolean expunged) {
		throw UNSUPPORTED_PROTECTED_METHOD("setExpunged(boolean)");
	}

	@Override
	public void parse(final InputStream is) {
		throw UNSUPPORTED_PROTECTED_METHOD("parse(InputStream)");
	}

	@Override
	public InputStream getContentStream() {
		throw UNSUPPORTED_PROTECTED_METHOD("getContentStream()");
	}

	@Override
	public void updateMessageID() {
		throw UNSUPPORTED_PROTECTED_METHOD("updateMessageID()");
	}

	@Override
	public void updateHeaders() {
		throw UNSUPPORTED_PROTECTED_METHOD("updateHeaders()");
	}

	@Override
	public InternetHeaders createInternetHeaders(final InputStream is) {
		throw UNSUPPORTED_PROTECTED_METHOD("createInternetHeaders(InputStream)");
	}

	@Override
	public MimeMessage createMimeMessage(final Session session) {
		throw UNSUPPORTED_PROTECTED_METHOD("createMimeMessage(Session)");
	}

	@Override
	public void setEnvelopeFrom(final String from) {
		throw MUTATION_NOT_SUPPORTED("setEnvelopeFrom(String)");
	}

	@Override
	public void setNotifyOptions(final int options) {
		throw MUTATION_NOT_SUPPORTED("setNotifyOptions(int)");
	}

	@Override
	public void setReturnOption(final int option) {
		throw MUTATION_NOT_SUPPORTED("setReturnOption(int)");
	}

	@Override
	public void setAllow8bitMIME(final boolean allow) {
		throw MUTATION_NOT_SUPPORTED("setAllow8bitMIME(boolean)");
	}

	@Override
	public void setSendPartial(final boolean partial) {
		throw MUTATION_NOT_SUPPORTED("setSendPartial(boolean)");
	}

	@Override
	public void setSubmitter(final String submitter) {
		throw MUTATION_NOT_SUPPORTED("setSubmitter(String)");
	}

	@Override
	public void setMailExtension(final String extension) {
		throw MUTATION_NOT_SUPPORTED("setMailExtension(String)");
	}

	@Override
	public void setFrom(final Address address) {
		throw MUTATION_NOT_SUPPORTED("setFrom(Address)");
	}

	@Override
	public void setFrom(final String address){
		throw MUTATION_NOT_SUPPORTED("setFrom(String)");
	}

	@Override
	public void setFrom(){
		throw MUTATION_NOT_SUPPORTED("setFrom()");
	}

	@Override
	public void addFrom(final Address[] addresses){
		throw MUTATION_NOT_SUPPORTED("addFrom(Address[])");
	}

	@Override
	public void setSender(final Address address){
		throw MUTATION_NOT_SUPPORTED("setSender(Address)");
	}

	@Override
	public void setRecipients(final Message.RecipientType type, final Address[] addresses){
		throw MUTATION_NOT_SUPPORTED("setRecipients(RecipientType, Address[])");
	}

	@Override
	public void setRecipients(final Message.RecipientType type, final String addresses){
		throw MUTATION_NOT_SUPPORTED("setRecipients(RecipientType, String)");
	}

	@Override
	public void addRecipients(final Message.RecipientType type, final Address[] addresses){
		throw MUTATION_NOT_SUPPORTED("addRecipients(RecipientType, Address[])");
	}

	@Override
	public void addRecipients(final Message.RecipientType type, final String addresses){
		throw MUTATION_NOT_SUPPORTED("addRecipients(RecipientType, String)");
	}

	@Override
	public void setReplyTo(final Address[] addresses){
		throw MUTATION_NOT_SUPPORTED("setReplyTo(Address[])");
	}

	@Override
	public void setSubject(final String subject){
		throw MUTATION_NOT_SUPPORTED("setSubject(String)");
	}

	@Override
	public void setSubject(final String subject, final String charset){
		throw MUTATION_NOT_SUPPORTED("setSubject(String, String)");
	}

	@Override
	public void setSentDate(final Date d){
		throw MUTATION_NOT_SUPPORTED("setSentDate(Date)");
	}

	@Override
	public void setDisposition(final String disposition){
		throw MUTATION_NOT_SUPPORTED("setDisposition(String)");
	}

	@Override
	public void setContentID(final String cid){
		throw MUTATION_NOT_SUPPORTED("setContentID(String)");
	}

	@Override
	public void setContentMD5(final String md5){
		throw MUTATION_NOT_SUPPORTED("setContentMD5(String)");
	}

	@Override
	public void setDescription(final String description){
		throw MUTATION_NOT_SUPPORTED("setDescription(String)");
	}

	@Override
	public void setDescription(final String description, final String charset){
		throw MUTATION_NOT_SUPPORTED("setDescription(String, String)");
	}

	@Override
	public void setContentLanguage(final String[] languages){
		throw MUTATION_NOT_SUPPORTED("setContentLanguage(String[])");
	}

	@Override
	public void setFileName(final String filename){
		throw MUTATION_NOT_SUPPORTED("setFileName(String)");
	}

	@Override
	public void setDataHandler(final DataHandler dh){
		throw MUTATION_NOT_SUPPORTED("setDataHandler(DataHandler)");
	}

	@Override
	public void setContent(final Object o, final String type){
		throw MUTATION_NOT_SUPPORTED("setContent(Object, String)");
	}

	@Override
	public void setText(final String text){
		throw MUTATION_NOT_SUPPORTED("setText(String)");
	}

	@Override
	public void setText(final String text, final String charset){
		throw MUTATION_NOT_SUPPORTED("setText(String, String)");
	}

	@Override
	public void setText(final String text, final String charset, final String subtype){
		throw MUTATION_NOT_SUPPORTED("setText(String, String, String)");
	}

	@Override
	public void setContent(final Multipart mp){
		throw MUTATION_NOT_SUPPORTED("setContent(Multipart)");
	}

	@Override
	public void setHeader(final String name, final String value){
		throw MUTATION_NOT_SUPPORTED("setHeader(String, String)");
	}

	@Override
	public void addHeader(final String name, final String value){
		throw MUTATION_NOT_SUPPORTED("addHeader(String, String)");
	}

	@Override
	public void removeHeader(final String name){
		throw MUTATION_NOT_SUPPORTED("removeHeader(String)");
	}

	@Override
	public void addHeaderLine(final String line){
		throw MUTATION_NOT_SUPPORTED("addHeaderLine(String)");
	}

	@Override
	public void setFlags(final Flags flag, final boolean set){
		throw MUTATION_NOT_SUPPORTED("setFlags(Flags, boolean)");
	}

	@Override
	public void setRecipient(final Message.RecipientType type, final Address address){
		throw MUTATION_NOT_SUPPORTED("setRecipient(RecipientType, Address)");
	}

	@Override
	public void addRecipient(final Message.RecipientType type, final Address address){
		throw MUTATION_NOT_SUPPORTED("addRecipient(RecipientType, Address)");
	}

	@Override
	public void setFlag(final Flags.Flag flag, final boolean set){
		throw MUTATION_NOT_SUPPORTED("setFlag(Flag, boolean)");
	}
}