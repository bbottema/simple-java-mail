package org.simplejavamail.internal.email;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceData;
import java.util.List;

/** Internal cross-module assembly of captured evidence and observations; applications inspect the resulting Email instead. */
@ApiStatus.Internal
public interface EmailInspectionBuilder {
    EmailSourceData getSourceData();
    List<EmailProblem> getProblems();
    void retainInspection(EmailSourceData sourceData, List<EmailProblem> problems);
}
