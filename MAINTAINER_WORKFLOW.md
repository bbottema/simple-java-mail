# Maintainer Workflow

This document describes the normal Simple Java Mail maintenance loop for coding agents and human maintainers.
It is intentionally one workflow with switches, so prompts like these can all follow the same backbone:

- "Can you pick up #743 and release it as patch?"
- "There are a few issues open, have a look at them and fix them without releasing."
- "Can you handle the dependabot PRs and release as patch?"
- "Fix up that last issue and prepare for a minor release."

For API additions, read [API_EXPANSION_WORKFLOW.md](API_EXPANSION_WORKFLOW.md) as part of the implementation phase.
For cross-cutting mechanisms such as CLI metadata, optional modules, MIME resources, and instrumentation, read
[PROJECT_MECHANISMS_CATALOGUE.md](PROJECT_MECHANISMS_CATALOGUE.md).

---

## 1. Interpret the Request

Classify the request before editing anything:

| Request shape                                        | Main mode                        | Release?                                                                |
|------------------------------------------------------|----------------------------------|-------------------------------------------------------------------------|
| "Pick up #N and release it as patch"                 | Issue-driven fix                 | Yes, patch release after merge                                          |
| "Look at open issues and fix them without releasing" | Triage and implementation        | No release; keep work on `develop`                                      |
| "Handle dependabot PRs and release as patch"         | Dependency PR batch              | Yes, patch release after verification                                   |
| "Fix the last issue and prepare for a minor release" | Implementation plus release prep | Usually stop before approving release unless explicitly told to release |

If the user explicitly says not to release, do not release.
If the user asks for or approves a patch or minor release at any point in the maintenance task, treat that as standing authorization
to carry the matching release through CircleCI approval, Maven Central verification, GitHub release notes, and final branch sync.
Release-context shorthand such as "new branch, patch" or "make this a minor" counts; do not stop later to ask for a second
"release it" confirmation. This authorization remains active until the user withdraws it or changes the requested release level.

Do not infer release authorization when "patch" only means a code diff or when "minor" merely describes the size of a change;
the surrounding request must indicate a semantic-version release or invoke this release workflow.

---

## 2. Start Clean

Always ground the session in live state:

```powershell
git status --short --branch
git fetch --prune --tags origin
git branch -vv
```

Load local machine paths from the gitignored maintainer environment file when it exists:

```powershell
if (Test-Path .\.maintainer-env.ps1) {
    . .\.maintainer-env.ps1
}
```

Use `gh` from `PATH`, or `$env:SJM_GH` from `.maintainer-env.ps1` when the executable needs an explicit local path:

```powershell
$gh = if ($env:SJM_GH) { $env:SJM_GH } else { "gh" }
& $gh auth status
```

Use JDK 17 or newer for a full-reactor build. Use a real JDK 11 for the separate library compatibility check:

```powershell
if (-not $env:JAVA_HOME) {
    throw "Load .maintainer-env.ps1 or set JAVA_HOME first."
}
java -version   # should report 17 or newer for a full build
```

Do not work around Maven Central or TLS failures with insecure SSL flags until the Windows trust-store option above has been tried.

Past correction to preserve: a retry is not permission to change repo state. If a requested retry depends on a clean/synced branch gate,
re-check the gate and proceed only if it already passes, unless the user explicitly approves syncing or fast-forwarding first.

---

## 3. Choose the Work Branch

Implementation work normally happens on `develop`.

```powershell
git switch develop
git pull --ff-only origin develop
```

Release work happens by merging `develop` into `master` after the release candidate is ready:

```powershell
git switch master
git pull --ff-only origin master
git merge --ff-only develop
git push origin master
```

If a fast-forward merge is not possible, stop and inspect the divergence. Do not rewrite shared branches unless the user explicitly asks.

---

## 4. Triage GitHub Work

Inspect issues or PRs directly through `gh`.

```powershell
& $gh issue view 743 --repo bbottema/simple-java-mail --comments --json number,title,body,labels,milestone,author,url,comments
& $gh issue list --repo bbottema/simple-java-mail --state open --limit 50 --json number,title,labels,milestone,author,url
& $gh pr list --repo bbottema/simple-java-mail --state open --limit 50 --json number,title,author,baseRefName,headRefName,url,labels,statusCheckRollup
```

This installed `gh` does not expose a top-level milestone command; use the API:

```powershell
& $gh api 'repos/bbottema/simple-java-mail/milestones?state=all&per_page=100' --paginate --jq '.[] | [.number,.title,.state,.due_on,.open_issues,.closed_issues] | @tsv'
```

Release milestones use the exact numeric release version as their title, without a `v` prefix. Once the target version is known,
reuse its milestone or create it in the open state before release bookkeeping starts. Set `due_on` to the planned release date when
creating the milestone; if the milestone already exists and is still open, fill or update its due date to the current plan:

Keep release milestone descriptions empty. Release summaries, planning notes, and process context belong elsewhere. The only
exception is a cross-version advisory that the maintainer explicitly asks to show on affected milestones; in that case, the
description must contain only that notice.

```powershell
$version = "9.1.4"
$plannedReleaseDate = "2026-08-05T00:00:00Z"
$milestones = & $gh api repos/bbottema/simple-java-mail/milestones?state=all --paginate | ConvertFrom-Json
$milestone = $milestones | Where-Object title -eq $version
if (-not $milestone) {
    $milestone = & $gh api -X POST repos/bbottema/simple-java-mail/milestones `
        -f title=$version -f state=open -f due_on=$plannedReleaseDate | ConvertFrom-Json
} elseif ($milestone.state -eq "open" -and
        (-not $milestone.due_on -or ([DateTimeOffset]$milestone.due_on).UtcDateTime.ToString("yyyy-MM-ddT00:00:00Z") -ne $plannedReleaseDate)) {
    $milestone = & $gh api -X PATCH repos/bbottema/simple-java-mail/milestones/$($milestone.number) `
        -f due_on=$plannedReleaseDate | ConvertFrom-Json
}
```

Assign every issue and PR represented by the release to that milestone. This includes closed Dependabot PRs whose update or
compatibility decision is part of the release, even when the published notes summarize several automated PRs in one maintenance
bullet or a maintainer roll-up PR. Do not add unrelated or superseded proposals that did not contribute to the release.
GitHub treats pull requests as issues for milestone updates:

```powershell
& $gh api -X PATCH repos/bbottema/simple-java-mail/issues/671 -F milestone=$($milestone.number)
```

Before publishing, extract the repository issue and PR links from the version's release-note entries and verify that each linked
Simple Java Mail item belongs to the same-version milestone. Also verify that any summarized Dependabot batch is fully represented
in the milestone even if the compact release note does not link every constituent PR.

```powershell
& $gh api "repos/bbottema/simple-java-mail/issues?milestone=$($milestone.number)&state=all&per_page=100" --paginate --jq '.[] | [.number,.state,.title] | @tsv'
```

Close the milestone only after that membership check passes and every included item is closed. Read the published GitHub release
date and write that actual release date to `due_on` while closing, replacing the earlier planned date when necessary:

```powershell
$publishedAt = & $gh release view $version --repo bbottema/simple-java-mail --json publishedAt --jq .publishedAt
$actualReleaseDate = ([DateTimeOffset]$publishedAt).UtcDateTime.ToString("yyyy-MM-ddT00:00:00Z")
& $gh api -X PATCH repos/bbottema/simple-java-mail/milestones/$($milestone.number) `
    -f state=closed -f due_on=$actualReleaseDate
```

For historical bookkeeping, derive the actual date from the version's release notes or published GitHub release. A closed release
milestone must never be left without a due date, because GitHub uses it to order milestones by release date.

Use existing labels. Common labels include:

- Added functionality, choose one when applicable: `enhancement` or `major feature`
- Other work types: `bug`, `maintenance`, `documentation`, `security`, `dependencies`, `3rdparty-problem`
- `Priority-Low`, `Priority-Medium`, `Priority-High`
- `invalid`, `question`, `need-user-input`, `will close soon`

`enhancement` and `major feature` are mutually exclusive levels of added functionality. Use `enhancement` for an incremental addition
and `major feature` for a substantial new capability that deserves prominent treatment; a `major feature` can still ship in a SemVer
minor release. Never apply both labels to one issue. Orthogonal labels such as `security` and a priority may be added alongside either
one. Re-read the issue's current labels immediately before changing them, and treat a maintainer's removal or replacement of one of
these labels as deliberate rather than restoring it from an older plan or task description.

When an issue belongs upstream, confirm whether the fix belongs in a sibling repo first. If it does, create or update the upstream issue, fix and release the upstream library, then update Simple Java Mail.

For sibling repositories:

- Check hidden CI files such as `.circleci/config.yml`; do not stop at visible-file scans.
- Verify the sibling repo is clean and aligned with its upstream before changing it.
- Do not fast-forward, push, tag, or release sibling repositories unless that was explicitly requested or approved.
- If a sibling repository is released as part of the fix, also complete its GitHub release bookkeeping: fixed issue comment/close,
  milestone close, GitHub release for the tag, and any relevant usage example.
- When copying or flattening parent/dependency configuration, verify the sibling source project is current and compare against the released Maven Central version. Do not bake stale local checkout state into this project.

---

## 5. Implement

Read the relevant code and tests before editing. Use TDD when the issue is reproducible.

For public API or config changes:

- Follow [API_EXPANSION_WORKFLOW.md](API_EXPANSION_WORKFLOW.md).
- Keep Java API, property configuration, Spring support, defaults/overrides, CLI exposure, and website/README docs aligned where applicable.
- Do not expose low-level Jakarta Mail terminology when Simple Java Mail can provide a higher-level concept.
- Prefer builder APIs that hide underlying property names and transport-specific details.

For CLI-related changes:

- CLI generation depends on Javadocs and builder reflection.
- Regenerate and commit `modules/cli-module/src/main/resources/cli.data` and `modules/cli-module/src/main/resources/therapi.data` when the CLI surface changes.
- Use `-Ppublish-cli` when verifying release packaging.
- Regenerate CLI metadata on JDK 17 or the current release JDK.

For dependency PRs:

- Preserve Java 11 compatibility for non-CLI modules. Do not accept library or build dependency lines that require Java 17+ in the JDK 11 lane.
- Update `.github/dependabot.yml` ignore rules when Dependabot repeatedly proposes versions that cannot run in the supported Java lanes.
- Keep release notes concise. Prefer one dependency-maintenance roll-up over one noisy bullet per automated PR unless the change matters to users.

---

## 6. Verify

Use focused tests first, then full verification before release.

Useful focused commands:

```powershell
mvn -pl modules/simple-java-mail -Dtest=SomeTest test
mvn -pl modules/cli-module -am -Ppublish-cli -DskipTests package
```

Before merging to `master` for a release, run the library reactor on a real JDK 11:

```powershell
mvn -pl '!modules/cli-module' clean verify -DexcludeLiveServerTests=true -Dmaven.javadoc.skip=true
```

Then run the full reactor, including CLI publication metadata, on JDK 17 or newer:

```powershell
mvn clean verify -Ppublish-cli -DexcludeLiveServerTests=true
```

If Norton or local certificate interception breaks Maven, retry with:

```powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
```

After any build that ran `license:format`, remove generated headers before committing unless the release artifact itself is being checked:

```powershell
mvn com.mycila:license-maven-plugin:3.0:remove
```

For release packaging checks, confirm the standalone CLI artifacts are built:

```powershell
Get-ChildItem modules\cli-module\target\*standalone-cli*
```

For release artifact checks, inspect the published source jars rather than committing generated headers to the working tree.

---

## 7. Update Documentation and Release Notes

For user-facing changes:

- Update the current version, dependency examples, and versioned release links in `README.md`. Keep its landing-page copy stable unless the release changes the project's lasting developer story.
- Keep `RELEASE.txt` and `RELEASE_HISTORY.md` aligned and complete.
- Update website source under `simplejavamail.org` for API/config documentation changes, but do not push the website unless explicitly approved.
- Add migration notes for behavior changes, removed API, changed defaults, or compatibility-impacting fixes.

Release-note retention:

- `RELEASE.txt` keeps the full release history.
- `RELEASE_HISTORY.md` also keeps the full release history and must stand alone. It starts with the latest release and continues through the archive.
- `README.md` is the repository's developer landing page, not a release-note surface. Keep its `Current release` section to the current version and direct links to the GitHub release, Maven Central artifacts, migration guide, and `RELEASE_HISTORY.md`; do not copy release bullets or retain previous release lines there.
- Update the Maven example, Gradle example, `Current release` label, tag link, and Maven Central directory together. Review the migration link separately and keep it pointed at the guide that actually applies to the release line.
- Major releases create the primary release-note story. A narrative section such as `The Short Version` is acceptable for a major release when it helps explain the release line.
- Regular minor and patch releases should usually be concise bullets that speak for themselves and link to GitHub issues for details. Do not force the major-release narrative format onto ordinary releases.
- Keep the latest-release notes aligned across `RELEASE.txt`, `RELEASE_HISTORY.md`, and the GitHub release body, with detail level adjusted to each surface. Keep the README's version and release links aligned without duplicating those notes. The GitHub release body is a concise pointer to the shipped work, not another full release-note surface.
- Minor releases normally create their own primary release-note entry. If a minor release follows closely in the wake of a major release and is still part of that same release wave, absorb it into the current major-line notes instead.
- Patch releases are absorbed into their parent minor release notes in the same primary note style. Do not create a separate patch-release story unless the user explicitly asks.
- When a patch is absorbed, place each change in the section where it belongs: bug fixes under fixes, dependency bumps under dependencies, packaging fixes under build/release maintenance, and API/docs additions under the relevant feature or enhancement section.
- The active release-note header may be a version range, for example `v9.0.0 - v9.0.2`, while the body remains organized by feature/fix/dependency sections.
- When a release-note heading covers more than one version, prefix every bullet with the exact version that first released that change, for example `- **v9.0.2:** ...`. A bullet may omit its version only when its heading names exactly one version.
- Within each section of a multi-version release-note entry, order the version-prefixed bullets by release number in descending order (newest first). Keep bullets from the same version together and retain their logical editorial order within that version group.
- Never combine changes first released in different versions into one bullet. Split mixed maintenance or dependency summaries by release version so every prefix remains unambiguous.
- Create one GitHub release for every published tag, including patches whose repository notes are absorbed into a version range. Never omit a patch release or fold it into an adjacent tag's GitHub release.
- Every GitHub release title and body must describe one tag only; never roll multiple versions into a GitHub release title or treat another tag's changes as part of that release.
- For a release with one issue or pull request, write the GitHub release body as one concise, natural-language sentence. Lead with the linked issue or pull request, summarize the user-visible outcome, and optionally end with one directly relevant documentation link.
- For a release with multiple issues or pull requests, use one concise bullet per item. Start every bullet with its linked issue or pull request, summarize that item's user-visible outcome in one sentence, and optionally end that bullet with one directly relevant documentation link.
- Do not add an introductory blurb, headings, compatibility boilerplate, implementation detail, or release-process commentary. Use bullets only for releases with multiple items, and include a compatibility fact only when users need it.
- A documentation link may support the summary but must not replace it. Do not point readers generically to `README.md`, `RELEASE_HISTORY.md`, or "the release notes."
- Use this single-item shape: `Fixed [#702](issue-url): clarified that RecipientBuilder accepts one address, while RecipientsBuilder handles address lists; see the [recipient builder examples](docs-url).`
- Use this multi-item shape, with no text before or after the list: `- Fixed [#701](issue-url): corrected the first user-visible problem.` followed by `- Added [#702](issue-url): introduced the second user-visible outcome; see the [usage guide](docs-url).`
- Write GitHub release notes from facts fixed at the tag. Do not depend on mutable branch content or on documentation whose visible focus will change with a later release.
- Keep build, test, packaging-validation, and release-process evidence out of GitHub release bodies. Verification belongs in the internal release checklist; published notes should contain only changes and compatibility information readers need.
- Attach release assets only to their matching tag. Versioned asset filenames and artifact contents must agree with the GitHub release tag.
- For older archived release groups only, a compact secondary format with each bullet prefixed by the concrete release version is fine.

The website checkout is deploy-sensitive and separate from the root repo. Keep root and website status, staging, commit, and push flows separate.
Use the root repo as source of truth for API/config names, and patch website docs only for proven mismatches.

For non-user-facing maintenance:

- Keep notes short or omit them.
- Do not bloat release notes with internal CI details unless users may hit the behavior directly.
- Avoid release-note text that only makes sense to maintainers, such as internal heap, metadata, or "remaining work" phrasing.
- Group routine dependency updates into a compact roll-up unless a dependency change is directly relevant to users.

For GitHub comments:

- Closing a user issue should include a short summary and a usage example when useful.
- Do not sign off comments with test evidence unless the user asks for it.
- After a release, add a short availability comment to related GitHub issues that were fixed or materially affected by the release.
- Comments should stand alone for the issue reporter. Avoid context-dependent wording such as "remaining" unless the comment also links the related issue or commit that explains the split.
- When a fix is split across layers, link the related GitHub issues or commits so the thread explains the whole path.

---

## 8. Commit and Push

Stage selectively and inspect the staged diff:

```powershell
git status --short
git diff
git add <paths>
git diff --cached --check
git diff --cached --stat
git commit -m "fix(scope): concise summary"
```

Use semantic commit subjects: `action(subject): summary, multiline allowed`

Keep unrelated changes out of the commit. Split docs, release notes, build fixes, and implementation work when they are independently meaningful.

For non-code-only changes such as release-note cleanup, issue-bookkeeping docs, or website TODO notes, add `[skip ci]` to the commit subject unless the user explicitly wants CI to run.
Do not use `[skip ci]` for implementation, build, dependency, generated CLI metadata, or release-lane fixes that need CI validation.

Push implementation work to `develop` unless preparing a release:

```powershell
git push origin develop
```

---

## 9. Close or Update Issues Without Releasing

If the user asked to fix without releasing:

1. Push `develop`.
2. Update the issue with a concise summary and usage example when relevant.
3. Apply existing labels and the intended milestone if known.
4. Close only issues that are fully fixed.
5. Leave release notes in an unreleased section.
6. Report clean status and the issue links.

Do not merge to `master` or approve CircleCI release gates.

---

## 10. Release

Only release when the user asked for it. An earlier request or approval for a patch or minor release remains sufficient authorization
throughout the task; do not require a second confirmation after implementation or issue follow-up.

Before release:

1. Perform the defensive Dependabot sweep below against the current `develop` branch.
2. Merge every safe, release-ready patch-level library update selected by the sweep into `develop`.
3. Run the JDK 11 library verification and JDK 17+ full-reactor verification on the final release candidate, including the lifted dependency patches.
4. Confirm the README's current version and release links match the release candidate, and confirm `RELEASE.txt` and `RELEASE_HISTORY.md` include a compact dependency-maintenance note when patches were lifted.
5. Create or reuse the exact-version GitHub milestone, without a `v` prefix, set its due date to the planned release date, and keep it open during the release.
6. Assign all release issues and PRs to it, including applicable closed Dependabot PRs and maintenance roll-ups.
7. Cross-check the version's release-note issue and PR links against milestone membership.
8. Confirm no unrelated local changes remain.
9. Merge `develop` into `master` with a fast-forward merge.
10. Push `master`.

### Java 8 maintenance line

After 10.0.0, treat 9.x as maintenance-only. New features, routine fixes, and routine dependency updates belong on 10.x. Consider a 9.x backport only for a critical problem, and only when the fix can keep the Java 8 and public API contracts intact.

Start that work from the latest 9.x release tag rather than from the 10.x development branch. Keep the patch narrow and run the Java 8 verification documented on that branch before releasing it.

### Defensive Dependabot Sweep

Run this sweep immediately before the final release verification for every release, not only when the original request mentions
Dependabot. Its purpose is to let low-risk library patches travel with an already-planned release instead of requiring another
release shortly afterward.

```powershell
& $gh pr list --repo bbottema/simple-java-mail --state open --author app/dependabot `
    --json number,title,baseRefName,mergeStateStatus,statusCheckRollup,url
```

Inspect every open Dependabot PR and lift a patch-level Java library update into the release only when all of these are true:

- The PR targets `develop`, is current or can be updated cleanly, and has no merge conflict.
- The proposed version is a patch update. Do not classify an update from its title alone when the versioning scheme is unusual.
- The library, its bytecode, and its transitive runtime dependencies remain Java 11-compatible.
- Existing checks pass and the update does not introduce a known behavioral, API, packaging, or licensing change that deserves
  separate release scope.
- The patch can be merged before the final JDK 11 library and JDK 17+ full-build verification and release-note freeze.

Do not hold up or silently broaden the release for minor/major upgrades, failing or uncertain patches, incompatible Java baselines,
or updates that need dedicated investigation. Leave those PRs for separate handling and record an ignore rule when an upgrade line
cannot run in the supported Java lanes. For every patch that is lifted, add its PR to the release milestone and include it in the compact
dependency-maintenance release-note entry before publishing.

Do not modify project POM versions to prepare a release. The CircleCI release workflow owns version bumping and tagging.
After release, the checked-in POM version should represent the current released version, not the next possible version.
If CircleCI successfully published to Maven Central but failed to update the repository, repair the repository manually:

```powershell
mvn versions:set "-DnewVersion=x.y.z"
git add pom.xml modules/*/pom.xml modules/cli-module/src/main/resources/therapi.data
git commit -m "released x.y.z [skip ci]"
git tag x.y.z
git push origin master --tags
```

Only use that manual version/tag repair after confirming Maven Central has the version and CircleCI failed before pushing the release commit or tag.

CircleCI will run `build-and-test` on `master`. The workflow exposes four approval gates:

- `approve-deploy-patch-version`
- `approve-deploy-minor-version`
- `approve-deploy-major-version`
- `approve-deploy-as-is-version`

Approve only the requested gate. The CircleCI CLI is useful for setup, diagnostics, config, and pipeline commands, but the installed CLI may not expose workflow approval commands.
When approval has to be automated, use the CircleCI API with the CLI token and identify the workflow and approval job first; do not approve by guesswork.

After the deploy job finishes:

1. Fetch tags and branch updates.
2. Verify the new version exists in Maven Central.
3. Verify the published sources contain license headers.
4. Verify `cli-module` includes `standalone-cli.tar` and `standalone-cli.zip`.
5. Create or update the GitHub release using the required single-item sentence or multi-item bullet format above.
6. Attach the release assets: the versioned CLI standalone `.tar` and `.zip` archives and the sample `log4j2_example.xml` logging configuration.
7. For 10.0.0, explicitly trigger the CircleCI configuration on `master` with `package_action=smoke` and `package_version=10.0.0`. Publish only after both one-time smoke jobs pass. Repeat this workflow for later releases only when package installation behavior or templates materially change.
8. Trigger the CircleCI configuration on `master` with `package_action=publish` and `package_version=x.y.z`. This checks out the matching tag, verifies the public release and archive hashes, validates both package sources, and publishes Chocolatey and the Homebrew tap. A rerun is safe after a partial success.
9. Verify the exact Chocolatey version and the tap formula are public.
10. After both packages are publicly available, add `brew install simple-java-mail/tap/sjm` and `choco install sjm` to the website while retaining the standalone ZIP and tar download route. Build and publish the website through its normal release path.
11. Recheck that every release-note issue/PR and summarized Dependabot item is in the exact-version milestone.
12. Confirm every milestone item is closed, set the milestone due date to the actual published release date, then close it.
13. Fast-forward `develop` to `master` and push `develop`.

Package pipelines require `package_version`, must be triggered against `master`, and do not create the GitHub release. The `SJM_PACKAGE_PUBLISHING` CircleCI context supplies the Homebrew tap token and Chocolatey API key. Package installation never starts a daemon; daemon use remains explicit through `sjm send -d` or `sjm daemon ...`.

If a published artifact is wrong or missing, assume the Central release is immutable. Fix the release lane and ship a patch release. Fold the patch changes into the parent repository notes using the release-note decision tree above, while giving the patch tag its own GitHub release in the required concise format.

Useful checks:

```powershell
$version = "9.0.1"
Invoke-WebRequest -UseBasicParsing -Uri "https://repo1.maven.org/maven2/org/simplejavamail/simple-java-mail/$version/simple-java-mail-$version.pom"
(Invoke-WebRequest -UseBasicParsing -Uri "https://repo1.maven.org/maven2/org/simplejavamail/cli-module/$version/").Links |
    Select-Object -ExpandProperty href |
    Where-Object { $_ -match "standalone-cli" }
```

For a normal release, create a GitHub release for the tag:

```powershell
& $gh release create $version --repo bbottema/simple-java-mail --title "v$version" --notes-file RELEASE_NOTES.md
```

For a special packaging patch, the repository notes still roll into the parent release range, but the patch tag must always have its own GitHub release whose body states the packaging problem and correction directly using the required format. Do not attach the patch's versioned artifacts to another tag.

---

## 11. Dependabot Patch Release

Dependabot PR handling follows the same workflow with extra Java-baseline caution:

1. List open PRs and identify Dependabot PRs.
2. Check each proposed dependency against the Java 11 library and Java 17 CLI baselines.
3. Update or add `.github/dependabot.yml` ignores for impossible upgrade lines.
4. Merge compatible PRs into `develop`.
5. Run the JDK 11 library and JDK 17+ full-reactor verification.
6. Once the release version is selected, create or reuse its exact-version milestone without a `v` prefix.
7. Assign every Dependabot PR accounted for by the release, including closed PRs consolidated into a maintainer roll-up.
8. Add a compact dependency-maintenance release-note entry.
9. Cross-check the maintenance entry and its constituent PRs against milestone membership.
10. Release as patch only if requested.

If Dependabot keeps reopening the same incompatible upgrade, fix the ignore rule before trying to out-click it.

---

## 12. Definition of Done

For a non-release task:

- Implementation committed and pushed to `develop`.
- Relevant tests pass or skipped tests are explained.
- GitHub issues/PRs are updated.
- Release notes are updated when user-facing.
- Worktree is clean.

For a release task:

- The defensive Dependabot sweep was completed; every eligible patch-level library update was included or deliberately left for separate handling.
- `master` and `develop` are aligned after release.
- The release tag exists remotely.
- Maven Central has the released artifacts.
- CLI standalone ZIP/TAR exist for `cli-module`.
- The GitHub release has the CLI standalone ZIP/TAR and sample `log4j2_example.xml` logging configuration assets.
- A separate GitHub release exists for the current tag; it is not folded into another tag's release.
- The README's Maven and Gradle examples, current-release label, tag link, and Maven Central link all match the published version, and its migration link points to the applicable guide.
- The GitHub release body is one concise sentence for a single item or one concise bullet per item for multiple items; every item starts with its linked issue or pull request, summarizes the user-visible outcome, and has at most one directly relevant documentation link.
- The GitHub release body contains no build/test verification evidence or internal release-process commentary.
- Every multi-version repository release-note section has version-prefixed bullets ordered newest first.
- The exact-version GitHub milestone contains every release issue, PR, and accounted-for Dependabot item; all items and the milestone are closed, and its due date equals the actual published release date.
- Related GitHub issues have a short release-availability comment when applicable.
- Worktree is clean.
