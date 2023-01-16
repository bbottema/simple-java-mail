package org.simplejavamail.mailer.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.config.EmailGovernance;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.converter.internal.mimemessage.MimeMessageProducerHelper;
import org.simplejavamail.mailer.internal.util.SessionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;

import static java.lang.String.format;
import static org.simplejavamail.converter.EmailConverter.mimeMessageToEML;
import static org.simplejavamail.mailer.internal.MailerException.INVALID_ENCODING;

/**
 * So this is getting a bit complicated now, but the idea is that the email to mime message conversion is encapsulated in a closure,
 * so it can be invoked independently when sending the email through a specific Session instance later on. This is important, because
 * in case of the batch-module, we cannot know which Session will be picked for the actual sending, so we need to be able to convert the
 * email to a mime message at the time of sending using Transport, not at the time of sending in the entry MailerImpl. This guarantees
 * that both the operational connection and the emails being sent through a specific SMTP server are managed by the Mailer responsible
 * for this SMTP server configuration (data being email defaults defined on Mailer level).
 */
@RequiredArgsConstructor
@EqualsAndHashCode
@ToString
public class SessionBasedEmailToMimeMessageConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailerImpl.class);

    private static final String MIMEMESSAGE_CONVERTER_KEY = "SESSION_BASED_EMAIL_TO_MIME_MESSAGE_CONVERTER_KEY";

    private final Session session;
    private final OperationalConfig operationalConfig;
    private final EmailGovernance emailGovernance;

    public static void primeSession(Session session, OperationalConfig operationalConfig, EmailGovernance emailGovernance) {
        session.getProperties().put(MIMEMESSAGE_CONVERTER_KEY, new SessionBasedEmailToMimeMessageConverter(session, operationalConfig, emailGovernance));
    }

    public static void unprimeSession(@NotNull Session session) {
        session.getProperties().remove(MIMEMESSAGE_CONVERTER_KEY);
    }

    @NotNull
    public static MimeMessage convertAndLogMimeMessage(Session session, final Email email) throws MessagingException {
        val mimeMessageConverter = (SessionBasedEmailToMimeMessageConverter) session.getProperties().get(MIMEMESSAGE_CONVERTER_KEY);
        return mimeMessageConverter.convertAndLogMimeMessage(email);
    }

    @NotNull
    private MimeMessage convertAndLogMimeMessage(final Email email) throws MessagingException {
        // fill and send wrapped mime message parts
        val message = convertMimeMessage(email, session, emailGovernance);

        SessionLogger.logSession(session, operationalConfig.isAsync(), "mail");
        message.saveChanges(); // some headers and id's will be set for this specific message
        //noinspection deprecation
        email.internalSetId(message.getMessageID());

        logEmail(message, operationalConfig.isTransportModeLoggingOnly(), email);
        return message;
    }

    static private MimeMessage convertMimeMessage(final Email email, final Session session, final EmailGovernance emailGovernance) throws MessagingException {
        try {
            return MimeMessageProducerHelper.produceMimeMessage(email, emailGovernance, session);
        } catch (UnsupportedEncodingException e) {
            LOGGER.trace("Failed to send email {}\n{}", email.getId(), email);
            throw new MailerException(format(INVALID_ENCODING, email.getId()), e);
        }
    }

    static private void logEmail(final MimeMessage message, boolean transportModeLoggingOnly, final Email email) {
        if (transportModeLoggingOnly) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("\n\nEmail: {}\n", email);
                LOGGER.info("\n\nMimeMessage: {}\n", mimeMessageToEML(message));
            }
        } else {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("\t\nEmail: {}", email);
                LOGGER.trace("\t\nMimeMessage: {}\n", mimeMessageToEML(message));
            }
        }
    }
}
