# Image source attributes and CLI deadline guidance

Accepted 28 October 2026 under [#897](https://github.com/bbottema/simple-java-mail/issues/897) and
[#898](https://github.com/bbottema/simple-java-mail/issues/898). The maintainer agreed to both review findings and requested subagents
to carry the workflow to completion. This authorizes design, implementation, selective commits, pushes to the current
`codex/10.0.0` and website `codex/10.0.0-content` branches, and issue completion after lead review. No release or deployment is included.

## Contracts and ownership

- Image worker: core `EmailPopulatingBuilder.IMG_SRC_PATTERN` and its Javadoc, and focused image-resolution tests in the main module.
  Select the actual quoted `src`, not text in preceding attributes, preserving both quote forms and quoted `>` characters.
  Keep named groups, untouched markup, file/classpath/URL order, opt-in flags, containment, repeated sources, existing CIDs and failed-pass state.
  No entity decoding or general HTML recovery. Governing decision: ADR 0036, with ADR 0008 for outgoing rendering.
- CLI documentation worker: website `src/pages/sending-and-execution.hbs` and any directly related current CLI deadline examples.
  Replace `--mailer:withMailSendTimeout=PT30S` with `--mailer:withMailSendTimeout PT30S`, retaining the property assignment and parser.
  Governing decision: ADR 0018. No new regression test for a one-line documentation repair.
- Lead: issues/project bookkeeping, ADRs and index, release history, OpenRewrite plan/research, image-related website guidance,
  combined review, selective delivery, and cleanup script. Preserve all pre-existing root/index/nested changes.

## Paths and scenarios

Image build: `EmailPopulatingBuilderImpl.buildEmail()` -> `resolveDynamicEmbeddedImageDataSources()` -> public matcher -> existing
file/classpath/URL acquisition -> replacement and final atomic application. Exercise actual build output and resource bytes.
Cover decoy `src` text inside both quoted attribute forms, real source before/after other attributes, quoted `>` characters,
absent/present decoy files, both source delimiters, preserved markup and meaningful existing CID/containment/failed-pass controls.
Independent review also requires a many-attribute prefix control for successful selection and no-source input, without timing thresholds.

CLI guidance: source Handlebars -> Eleventy output -> displayed option. Confirm supported parsing from the current CLI model;
inspect rendered deadline text and complete normal website checks for the selected content. No runtime parser or metadata change.

## Surface reconciliation and verification order

1. Image worker writes focused failing regressions before the narrow fix, then runs the new cases and existing image controls against fresh reactor classes.
   Use Java 11 library compatibility and the configured modern JDK where useful; skip the Windows license formatter.
2. Lead reviews the actual combined code, Javadoc and boundary cases. Public matcher Javadoc changes require current CLI metadata regeneration
   only if they enter generated option help; inspect that boundary rather than changing cache binaries unnecessarily.
3. Synchronize current embedding/migration guidance, release history and frozen-target OpenRewrite fixture obligations. No source recipe or new report topic.
   Search README, Journal, case studies, downloads and integration examples; update only affected recommendations.
4. Run website checks/build and inspect affected rendered pages/links. Keep any pre-existing concurrent-content failures separate and validate the selected published diff.
5. Deliver website first, then parent commits/gitlink. Verify remote heads, issue labels/milestone/status, and completion comments with final usage.
6. Retire this plan after reporting results directly to the maintainer. Scratch is confined to `.local/api-review-897-898-20261028`;
   list every additional created path before use. Update/dry-run the cleanup script without deleting other rounds' targets.

## Progress

- Issues created with milestone 10.0.0 and existing labels; project items moved from Todo to In Progress for approved implementation.
- ADR decisions recorded before implementation. Root and website companion guidance are aligned; CLI caches need no change because the matcher is a field.
- The 13 main regression invocations failed before the repair. Final focused checks pass on Java 11 and Java 17 (63 each), including many-attribute controls.
  Independent review accepted the final guarded possessive matcher; fresh core Javadoc generation and packaged content also passed.
- Website checks, full build, internal links, indexability and the affected rendered paragraphs passed on the shared checkout, including concurrent content.
- Delivered website commit `34276e1` first, then main `a09de742` and `4f5e99a4`. Remote heads and parent gitlink match.
  Both issues have completion usage, closed/completed state, milestone 10.0.0, expected labels and project status Done.
- This plan is complete and disposable. All owned helpers have stopped. Cleanup script syntax passed; its dry run stops on the pre-existing
  `.local/import-review-round-20261028/website/node_modules` reparse point, which remains untouched.
- Automatic approval review rejected both the guarded inline removal and the fixed-literal removal of this round's scratch and plan with
  `blocked by policy`. Their exact paths remain allowlisted for later cleanup; no deletion occurred. Read-only preflight established ordinary
  paths beneath this repository with no tracked/committed files, nested repositories, reparse points or registered worktrees.
