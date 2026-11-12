# Imported filename preservation (#899)

Delivered: main `5eff00342e0ebeb2d886983b071a71a10ccffe2d`, README `3f93a15995609d4ac194a4ba4b69e03ecda7de97`, website `be91411fa78a23cdd5fd41e8e1de96c4d009c422`. Issue #899 is closed/completed and its project item is Done. Implementation and publication are complete. Automatic approval review rejected guarded deletion of this round's exact temporary paths and plan as "blocked by policy" before execution. Cleanup remains pending in the shared script's exact allowlists; its older unrelated node_modules reparse-point blocker is preserved.

Accepted 29 October 2026: preserve decoded filenames on attachments and embedded images; normalize only actual Content-IDs and the existing missing-name fallback. The maintainer authorized implementation and complete review-workflow delivery on the current main and website branches, plus the README version correction without a separate issue.

Issue: https://github.com/bbottema/simple-java-mail/issues/899. Architecture: ADR 0008, ADR 0035, ADR 0036.

Impact: static/fluent MIME/EML import and exact inspection pass parsed names through MimeMessageEmailMapper. Remove its filename CID normalization; keep the shared CID helper and parser fallback. Cover bracket-bearing names and ordinary names, attachment/inline classification, actual CIDs, fallback names, eager/lazy content, copy/serialization, re-export and exact bytes. Existing inline metadata tests cover content acquisition ownership and protected import.

Owned surfaces: mapper, focused ImportedFilenameTest, ADR 0035/index, release history, README wording, imported-resource website guidance/migration notes, and OpenRewrite research/plan. Journal/case studies require a search for conflicting filename guidance. No public signatures, configuration, CLI metadata or provider contracts change.

Verification order: failing focused regression; narrow mapper correction; focused regression and adjacent metadata/naming suites on modern JDK and JDK 11; documentation review and website checks/rendered output. Selective semantic delivery, issue completion and task-owned scratch cleanup follow review. Preserve unrelated staged/unstaged/nested work.
