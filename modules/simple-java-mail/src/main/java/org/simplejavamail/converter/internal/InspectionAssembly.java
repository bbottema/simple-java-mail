package org.simplejavamail.converter.internal;

import org.simplejavamail.api.email.*;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.email.EmailInspection;
import org.simplejavamail.api.internal.smimesupport.builder.SmimeParseResult;
import org.simplejavamail.api.internal.smimesupport.model.AttachmentDecryptionResult;

import java.util.ArrayList;
import java.util.List;

/** Assembles already observed source/protection facts without reading content or performing inspection. */
final class InspectionAssembly {
    private InspectionAssembly() { }

    static void attach(InternalEmailPopulatingBuilder builder, EmailSourceData content, List<EmailProblem> findings) {
        List<EmailProblem> combined = new ArrayList<>(builder.getProblems());
        String prefix = "source/derived[" + builder.getSourceData().getDerivedSources().size() + "]/";
        for (EmailProblem finding : findings) {
            String location = finding.getLocation();
            combined.add(EmailInspection.problem(finding.getOrigin(), finding.getOperation(), finding.getCode(),
                    prefix + (location.startsWith("source/") ? location.substring(7) : location), finding.getSourceValue(), finding.getMessage(),
                    finding.getSources()));
        }
        builder.retainInspection(append(builder.getSourceData(), content), combined);
    }

    private static EmailSourceData append(EmailSourceData parent, EmailSourceData child) {
        final List<EmailSourceData> derived = new ArrayList<>(parent.getDerivedSources());
        derived.add(child);
        return EmailInspection.withDerivedSources(parent, derived);
    }

    static void attachmentProtection(final InternalEmailPopulatingBuilder builder, final SmimeParseResult result,
            final java.util.function.Function<AttachmentResource, EmailSourcePart> sourceOf) {
        attachmentProtection(builder, result.getDecryptedAttachmentResults(), sourceOf);
    }

    static void attachmentProtection(final InternalEmailPopulatingBuilder builder, final List<AttachmentDecryptionResult> results,
            final java.util.function.Function<AttachmentResource, EmailSourcePart> sourceOf) {
        final List<EmailProblem> findings = new ArrayList<>(builder.getProblems());
        for (final AttachmentDecryptionResult attachment : results) {
            if (attachment.getFailureReason() != null) {
                final EmailSourcePart source = sourceOf.apply(attachment.getAttachmentResource());
                final EmailSourceIdentity origin = source == null ? builder.getSourceData().getOrigin() : source.getOrigin();
                findings.add(EmailInspection.problem(origin, EmailProblem.Operation.IMPORT, EmailProblem.Code.PROTECTION_FAILED,
                        source == null ? "S/MIME/attachment" : source.getLocation(), null, attachment.getFailureReason(),
                        source == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(source)));
            }
        }
        builder.retainInspection(builder.getSourceData(), findings);
    }

    static void protection(InternalEmailPopulatingBuilder builder, java.util.function.Supplier<EmailSourceIdentity> origin) {
        List<EmailProblem> values = new ArrayList<>(builder.getProblems());
        OriginalSmimeDetails smime = builder.getOriginalSmimeDetails();
        if (smime != null) {
            if (smime.getVerificationStatus() == OriginalSmimeDetails.VerificationStatus.INVALID)
                add(values, origin.get(), EmailProblem.Code.SIGNATURE_INVALID, "S/MIME/signature", "The received S/MIME signature is invalid");
            if (smime.getVerificationStatus() == OriginalSmimeDetails.VerificationStatus.ERROR
                    || smime.getDecryptionStatus() == OriginalSmimeDetails.DecryptionStatus.FAILED)
                add(values, origin.get(), EmailProblem.Code.PROTECTION_FAILED, "S/MIME", smime.getFailureReason());
        }
        OriginalOpenPgpDetails pgp = builder.getOriginalOpenPgpDetails();
        if (pgp != null) {
            if (pgp.getSignatureStatus() == OriginalOpenPgpDetails.SignatureStatus.INVALID)
                add(values, origin.get(), EmailProblem.Code.SIGNATURE_INVALID, "OpenPGP/signature", "The received OpenPGP signature is invalid");
            if (pgp.getSignatureStatus() == OriginalOpenPgpDetails.SignatureStatus.ERROR
                    || pgp.getDecryptionStatus() == OriginalOpenPgpDetails.DecryptionStatus.FAILED)
                add(values, origin.get(), EmailProblem.Code.PROTECTION_FAILED, "OpenPGP", pgp.getFailureReason());
        }
        builder.retainInspection(builder.getSourceData(), values);
    }

    private static void add(List<EmailProblem> problems, EmailSourceIdentity origin, EmailProblem.Code code, String location, String message) {
        problems.add(EmailInspection.problem(origin, EmailProblem.Operation.IMPORT, code, location, null,
                message == null ? "Protection processing failed; inspect the recorded protection details" : message));
    }
}
