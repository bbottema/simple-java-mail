package org.simplejavamail.internal.mailprovider.angus;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.URLName;
import org.eclipse.angus.mail.smtp.SMTPTransport;
import org.eclipse.angus.mail.util.PropUtil;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.SmtpServerResponse;
import org.simplejavamail.api.mailer.MailSendProgressStage;
import org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter.ConnectionProgressRegistration;
import org.simplejavamail.api.mailer.spi.MailTransportProgress;
import org.simplejavamail.internal.util.SmtpSizeSupport;
import org.simplejavamail.internal.util.SmtpServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps a closeable network handle outside Angus's synchronized SMTP methods, observes live protocol stages and records final submission.
 * Angus still owns the complete protocol implementation. Public construction is required by Session's provider loader.
 */
public final class ManagedAngusTransport extends SMTPTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedAngusTransport.class);
    private final String propertyPrefix;
    private final boolean allowUtf8;
    @Nullable private final AngusSocketFactory socketFactory;
    @Nullable private final AngusWriteTimeoutScheduler writeTimeoutScheduler;
    @Nullable private AngusWriteTimeoutScheduler.Lease writeTimeoutLease;
    private final AtomicReference<Socket> rawSocket = new AtomicReference<>();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private boolean commitPossible;
    private boolean readingFinalResponse;
    private boolean sending;
    // Setup failures are eligible for failover only before Angus can begin a mail submission.
    private boolean connectionSetupInProgress;
    private boolean awaitingGreeting;
    private final AtomicReference<MailTransportProgress> connectionProgress = new AtomicReference<>();
    private int pendingRecipientIndex = -1;
    @Nullable private SmtpServerResponse finalResponse;
    private final List<SmtpServerResponse> recipientResponses = new ArrayList<>();
    @Nullable private AngusRecipientCommands activeRecipientCommands;
    @Nullable private AngusMailFromParameters activeMailFromParameters;
    @Nullable private AngusMailTransportAdapter.AngusSmtpMessage activeMessage;
    @Nullable private MailTransportProgress activeProgress;
    @Nullable private AngusContentCountingStream contentProgress;
    private SmtpSizeSupport currentSizeSupport = SmtpSizeSupport.unadvertised();

    public ManagedAngusTransport(final Session session, final URLName urlName) {
        super(session, urlName, urlName == null ? "smtp" : urlName.getProtocol(),
                urlName != null && "smtps".equals(urlName.getProtocol()));
        propertyPrefix = "mail." + (urlName == null ? "smtp" : urlName.getProtocol());
        allowUtf8 = PropUtil.getBooleanProperty(session.getProperties(), "mail.mime.allowutf8", false);
        final Object configuredFactory = session.getProperties().get(propertyPrefix + ".socketFactory");
        socketFactory = configuredFactory instanceof AngusSocketFactory ? (AngusSocketFactory) configuredFactory : null;
        writeTimeoutScheduler = AngusWriteTimeoutScheduler.find(session.getProperties(), urlName == null ? "smtp" : urlName.getProtocol());
    }

    /** Reuses Angus's SMTP xtext encoder for ENVID and ASCII ORCPT without creating or using a transport connection. */
    static String encodeXtext(final String value) {
        return xtext(value, false);
    }

    boolean hasTrackedSocketConfiguration() {
        return socketFactory != null && session.getProperties().get(propertyPrefix + ".socketFactory") == socketFactory
                && session.getProperties().get(propertyPrefix + ".ssl.socketFactory") == null
                && session.getProperty(propertyPrefix + ".ssl.socketFactory.class") == null
                && "false".equals(session.getProperty(propertyPrefix + ".socketFactory.fallback"));
    }

    /** Match Angus's constructor-time wire encoding flag and the current, possibly post-STARTTLS capability set. */
    boolean supportsUtf8RecipientCommands() {
        return allowUtf8 && supportsExtension("SMTPUTF8");
    }

    /** Command encoding is fixed at construction; legacy permission does not change this flag or imply an advertised capability. */
    boolean isUtf8CommandEncodingEnabled() {
        return allowUtf8;
    }

    /** Read the selected connection's own Session; another Mailer's advanced properties must not govern this submission. */
    boolean isPartialSendingEnabled() {
        return PropUtil.getBooleanProperty(session.getProperties(), propertyPrefix + ".sendpartial", false);
    }

    /** Unregistration uses identity and never waits on Angus's network monitor, including cancellation during a blocked connect. */
    @Nullable
    ConnectionProgressRegistration observeConnectionSetup(final MailTransportProgress progress) {
        return connectionProgress.compareAndSet(null, progress) ? () -> connectionProgress.compareAndSet(progress, null) : null;
    }

    private void connectionStage(final MailSendProgressStage stage) {
        final MailTransportProgress progress = connectionProgress.get();
        if (connectionSetupInProgress && progress != null) {
            progress.stageChanged(stage);
        }
    }

    private void submissionStage(final MailSendProgressStage stage) {
        if (sending && activeProgress != null) {
            activeProgress.stageChanged(stage);
        }
    }

    @Override
    protected synchronized boolean protocolConnect(final String host, final int port, final String user, final String password)
            throws MessagingException {
        currentSizeSupport = SmtpSizeSupport.unadvertised();
        if (writeTimeoutLease == null && writeTimeoutScheduler != null) {
            writeTimeoutLease = writeTimeoutScheduler.retainConnection();
        }
        final AngusSocketFactory.SocketTracker previous = socketFactory == null ? null : socketFactory.bind(this::trackSocket);
        connectionSetupInProgress = true;
        awaitingGreeting = true;
        try {
            final boolean connected = super.protocolConnect(host, port, user, password);
            if (!connected) {
                releaseConnectionResources();
            }
            return connected;
        } catch (MessagingException | RuntimeException | Error failure) {
            releaseAfterFailure(failure);
            throw failure;
        } finally {
            connectionStage(MailSendProgressStage.ACQUIRING_CONNECTION);
            connectionSetupInProgress = false;
            awaitingGreeting = false;
            if (socketFactory != null) {
                socketFactory.restore(previous);
            }
        }
    }

    @Override
    protected boolean ehlo(final String domain) throws MessagingException {
        // In particular, a failed post-STARTTLS EHLO must not leave the pre-TLS SIZE limit active.
        currentSizeSupport = SmtpSizeSupport.unadvertised();
        connectionStage(MailSendProgressStage.NEGOTIATING_CAPABILITIES);
        try {
            final boolean succeeded = super.ehlo(domain);
            if (succeeded) {
                currentSizeSupport = AngusSmtpCapabilities.parseSizeSupport(getLastServerResponse());
            }
            return succeeded;
        } finally {
            connectionStage(MailSendProgressStage.ACQUIRING_CONNECTION);
        }
    }

    @Override
    protected void helo(final String domain) throws MessagingException {
        currentSizeSupport = SmtpSizeSupport.unadvertised();
        connectionStage(MailSendProgressStage.NEGOTIATING_CAPABILITIES);
        try {
            super.helo(domain);
        } finally {
            connectionStage(MailSendProgressStage.ACQUIRING_CONNECTION);
        }
    }

    @Override
    protected void startTLS() throws MessagingException {
        connectionStage(MailSendProgressStage.NEGOTIATING_TLS);
        try {
            super.startTLS();
        } finally {
            connectionStage(MailSendProgressStage.ACQUIRING_CONNECTION);
        }
    }

    @Nullable
    Long getServerMaximumMessageSize() {
        return currentSizeSupport.getMaximumMessageSize();
    }

    void trackSocket(final Socket socket) throws IOException {
        rawSocket.set(socket);
        if (aborted.get()) {
            socket.close();
        }
    }

    /** The owner fences this action before returning the transport to another borrower. Never take the SMTP monitor here. */
    void abortConnection() {
        aborted.set(true);
        closeTrackedSocket();
    }

    private void closeTrackedSocket() {
        final Socket socket = rawSocket.get();
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException | RuntimeException failure) {
                LOGGER.warn("Unable to close the managed SMTP socket", failure);
            }
        }
    }

    @Override
    public synchronized void close() throws MessagingException {
        Throwable primaryFailure = null;
        try {
            super.close();
        } catch (MessagingException failure) {
            // Angus has already cleared its streams/connected state in finally; QUIT on our deliberately closed socket is expected.
            if (!aborted.get() || !(failure.getCause() instanceof SocketException)) {
                primaryFailure = failure;
                throw failure;
            }
            LOGGER.debug("Closed SMTP transport after its socket was aborted", failure);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            currentSizeSupport = SmtpSizeSupport.unadvertised();
            if (primaryFailure == null) {
                releaseConnectionResources();
            } else {
                releaseAfterFailure(primaryFailure);
            }
        }
    }

    private void releaseConnectionResources() {
        try {
            closeTrackedSocket();
        } finally {
            rawSocket.set(null);
            releaseWriteTimeoutLease();
        }
    }

    private void releaseAfterFailure(final Throwable primaryFailure) {
        try {
            releaseConnectionResources();
        } catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != primaryFailure) {
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private void releaseWriteTimeoutLease() {
        if (writeTimeoutLease != null) {
            final AngusWriteTimeoutScheduler.Lease lease = writeTimeoutLease;
            writeTimeoutLease = null;
            lease.close();
        }
    }

    @Override
    public synchronized void sendMessage(final Message message, final Address[] recipients) throws MessagingException {
        beginSubmission(message);
        try {
            super.sendMessage(message, recipients);
        } finally {
            endSubmission();
        }
    }

    private void beginSubmission(final Message message) {
        commitPossible = false;
        readingFinalResponse = false;
        pendingRecipientIndex = -1;
        finalResponse = null;
        recipientResponses.clear();
        activeMessage = message instanceof AngusMailTransportAdapter.AngusSmtpMessage ? (AngusMailTransportAdapter.AngusSmtpMessage) message : null;
        activeProgress = activeMessage == null ? null : activeMessage.getProgress();
        activeRecipientCommands = activeMessage == null ? null : activeMessage.getRecipientCommands();
        activeMailFromParameters = activeMessage == null ? null : activeMessage.getMailFromParameters();
        sending = true;
    }

    private void endSubmission() {
        sending = false;
        activeRecipientCommands = null;
        activeMailFromParameters = null;
        if (activeMessage != null) {
            activeMessage.clearProgress();
        }
        activeMessage = null;
        activeProgress = null;
        contentProgress = null;
    }

    @Override
    protected OutputStream data() throws MessagingException {
        try {
            return observeContent(super.data());
        } catch (MessagingException | RuntimeException failure) {
            submissionStage(MailSendProgressStage.SUBMITTING);
            throw failure;
        }
    }

    @Override
    protected OutputStream bdat() throws MessagingException {
        return observeContent(super.bdat());
    }

    private OutputStream observeContent(final OutputStream stream) {
        if (activeProgress == null) {
            return stream;
        }
        activeProgress.contentTransferStarted(activeMessage.getProgressContentSize());
        contentProgress = new AngusContentCountingStream(stream, activeProgress);
        return contentProgress;
    }

    @Override
    protected void finishData() throws IOException, MessagingException {
        finishContentObservation();
        super.finishData();
    }

    @Override
    protected void finishBdat() throws IOException, MessagingException {
        finishContentObservation();
        super.finishBdat();
    }

    private void finishContentObservation() {
        if (contentProgress != null) {
            contentProgress.finishObservation();
        }
    }

    @Override
    protected void mailFrom() throws MessagingException {
        if (activeMessage != null) {
            // Angus has finished its optional 8-bit conversion here, but has not sent MAIL FROM or consumed ENVID/REQUIRETLS yet.
            activeMessage.prepareMessageSize(getServerMaximumMessageSize(), currentSizeSupport.isAdvertised(),
                    aborted::get);
        }
        try {
            super.mailFrom();
        } finally {
            submissionStage(MailSendProgressStage.SUBMITTING);
        }
    }

    @Override
    protected void sendCommand(final String command) throws MessagingException {
        if (sending) {
            trackOutgoingCommand(command);
        }
        final String mailCommand = activeMailFromParameters == null ? command : activeMailFromParameters.applyToCommand(command);
        final String outgoing = applyRecipientParameters(mailCommand);
        observeCommandCategory(outgoing);
        super.sendCommand(outgoing);
    }

    private void observeCommandCategory(final String command) {
        if (connectionSetupInProgress && connectionProgress.get() != null && command.startsWith("AUTH ")) {
            connectionStage(MailSendProgressStage.AUTHENTICATING);
        }
        if (sending && activeProgress != null) {
            if (command.startsWith("MAIL FROM:")) {
                submissionStage(MailSendProgressStage.SUBMITTING_SENDER);
            } else if (command.startsWith("RCPT TO:")) {
                submissionStage(MailSendProgressStage.SUBMITTING_RECIPIENTS);
            } else if ("DATA".equals(command)) {
                submissionStage(MailSendProgressStage.WAITING_FOR_DATA_PERMISSION);
            }
        }
    }

    @Override
    protected int simpleCommand(final byte[] command) throws MessagingException {
        // Some authentication mechanisms bypass sendCommand(String). Inspect only the five verb bytes, never decode the payload.
        if (connectionSetupInProgress && connectionProgress.get() != null && command.length >= 5
                && command[0] == 'A' && command[1] == 'U' && command[2] == 'T' && command[3] == 'H' && command[4] == ' ') {
            connectionStage(MailSendProgressStage.AUTHENTICATING);
        }
        return super.simpleCommand(command);
    }

    @Override
    protected void rcptTo() throws MessagingException {
        try {
            super.rcptTo();
        } finally {
            submissionStage(MailSendProgressStage.SUBMITTING);
        }
    }

    private void trackOutgoingCommand(final String command) {
        readingFinalResponse = ".".equals(command) || command.startsWith("BDAT ") && command.endsWith(" LAST");
        if (readingFinalResponse) {
            // Mark before writing: an interrupted final command can still have reached the server.
            commitPossible = true;
        }
        pendingRecipientIndex = -1;
        if (command.startsWith("RCPT TO:")) {
            pendingRecipientIndex = recipientResponses.size();
            recipientResponses.add(null);
        }
    }

    private String applyRecipientParameters(final String command) throws MessagingException {
        return pendingRecipientIndex >= 0 && activeRecipientCommands != null
                ? activeRecipientCommands.applyToRecipientCommand(command, pendingRecipientIndex)
                : command;
    }

    @Override
    protected int readServerResponse() throws MessagingException {
        final boolean greeting = connectionSetupInProgress && awaitingGreeting;
        awaitingGreeting = false;
        if (greeting) {
            connectionStage(MailSendProgressStage.WAITING_FOR_GREETING);
        }
        if (readingFinalResponse && activeProgress != null) {
            // Angus has written/flushed the final DATA terminator or LAST chunk before entering this read.
            activeProgress.waitingForResponse();
        }
        try {
            final int response = super.readServerResponse();
            throwIfConnectionSetupFailed(response);
            recordServerResponse(response);
            return response;
        } finally {
            if (greeting) {
                connectionStage(MailSendProgressStage.ACQUIRING_CONNECTION);
            }
        }
    }

    private void throwIfConnectionSetupFailed(final int response) throws MessagingException {
        if (!connectionSetupInProgress) {
            return;
        }
        if (response == 421) {
            throw new SmtpServiceUnavailableException();
        }
        if (response == -1 && "[EOF]".equals(getLastServerResponse())) {
            // Angus 2.0.5 returns this exact sentinel (without a line ending) on empty EOF. Malformed reply lines retain their newline.
            throw new MessagingException("The SMTP server disconnected during connection setup.", new EOFException("SMTP setup ended before a reply"));
        }
    }

    private void recordServerResponse(final int response) {
        final SmtpServerResponse observed = AngusSubmissionResult.smtpResponse(response, getLastServerResponse());
        if (pendingRecipientIndex >= 0) {
            recipientResponses.set(pendingRecipientIndex, observed);
            pendingRecipientIndex = -1;
        }
        if (readingFinalResponse) {
            finalResponse = observed;
            readingFinalResponse = false;
        }
    }

    boolean wasCommitPossible() {
        return commitPossible;
    }

    @Nullable
    SmtpServerResponse getFinalResponse() {
        return finalResponse;
    }

    /** Called under the transport monitor; the list is copied because each pooled attempt reuses these fields. */
    List<SmtpServerResponse> getRecipientResponses() {
        return new ArrayList<>(recipientResponses);
    }
}
