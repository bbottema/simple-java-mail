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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps a closeable network handle outside Angus's synchronized SMTP methods and records its final submission boundary.
 * Angus still owns the complete protocol implementation. Public construction is required by Session's provider loader.
 */
public final class ManagedAngusTransport extends SMTPTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedAngusTransport.class);
    private final String propertyPrefix;
    private final boolean allowUtf8;
    @Nullable private final AngusSocketFactory socketFactory;
    private final AtomicReference<Socket> rawSocket = new AtomicReference<>();
    private final AtomicBoolean aborted = new AtomicBoolean();
    private boolean commitPossible;
    private boolean readingFinalResponse;
    private boolean sending;
    private int pendingRecipientIndex = -1;
    @Nullable private SmtpServerResponse finalResponse;
    private final List<SmtpServerResponse> recipientResponses = new ArrayList<>();
    @Nullable private AngusRecipientCommands activeRecipientCommands;

    public ManagedAngusTransport(final Session session, final URLName urlName) {
        super(session, urlName, urlName == null ? "smtp" : urlName.getProtocol(),
                urlName != null && "smtps".equals(urlName.getProtocol()));
        propertyPrefix = "mail." + (urlName == null ? "smtp" : urlName.getProtocol());
        allowUtf8 = PropUtil.getBooleanProperty(session.getProperties(), "mail.mime.allowutf8", false);
        final Object configuredFactory = session.getProperties().get(propertyPrefix + ".socketFactory");
        socketFactory = configuredFactory instanceof AngusSocketFactory ? (AngusSocketFactory) configuredFactory : null;
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

    @Override
    protected synchronized boolean protocolConnect(final String host, final int port, final String user, final String password)
            throws MessagingException {
        if (socketFactory == null) {
            return super.protocolConnect(host, port, user, password);
        }
        final ManagedAngusTransport previous = socketFactory.bind(this);
        try {
            final boolean connected = super.protocolConnect(host, port, user, password);
            if (!connected) {
                closeTrackedSocket();
            }
            return connected;
        } catch (MessagingException | RuntimeException | Error failure) {
            closeTrackedSocket();
            throw failure;
        } finally {
            socketFactory.restore(previous);
        }
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
            } catch (IOException failure) {
                LOGGER.warn("Unable to close the aborted SMTP socket", failure);
            }
        }
    }

    @Override
    public synchronized void close() throws MessagingException {
        try {
            super.close();
        } catch (MessagingException failure) {
            // Angus has already cleared its streams/connected state in finally; QUIT on our deliberately closed socket is expected.
            if (!aborted.get() || !(failure.getCause() instanceof SocketException)) {
                throw failure;
            }
            LOGGER.debug("Closed SMTP transport after its socket was aborted", failure);
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
        activeRecipientCommands = message instanceof AngusMailTransportAdapter.AngusSmtpMessage
                ? ((AngusMailTransportAdapter.AngusSmtpMessage) message).getRecipientCommands() : null;
        sending = true;
    }

    private void endSubmission() {
        sending = false;
        activeRecipientCommands = null;
    }

    @Override
    protected void sendCommand(final String command) throws MessagingException {
        if (sending) {
            trackOutgoingCommand(command);
        }
        super.sendCommand(applyRecipientParameters(command));
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
        final int response = super.readServerResponse();
        final SmtpServerResponse observed = AngusSubmissionResult.smtpResponse(response, getLastServerResponse());
        if (pendingRecipientIndex >= 0) {
            recipientResponses.set(pendingRecipientIndex, observed);
            pendingRecipientIndex = -1;
        }
        if (readingFinalResponse) {
            finalResponse = observed;
            readingFinalResponse = false;
        }
        return response;
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
