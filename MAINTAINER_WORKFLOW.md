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
If the user explicitly says to release, carry the release through CircleCI approval, Maven Central verification, GitHub release notes, and final branch sync.

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

Local verification should use JDK 8:

```powershell
if (-not $env:JAVA_HOME) {
    throw "Load .maintainer-env.ps1 or set JAVA_HOME to a Java 8 JDK first."
}
java -version   # should report 1.8.x
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
& $gh api repos/bbottema/simple-java-mail/milestones --paginate --jq '.[] | [.number,.title,.state,.open_issues,.closed_issues] | @tsv'
```

Use existing labels. Common labels include:

- `bug`, `enhancement`, `maintenance`, `documentation`, `security`, `dependencies`, `3rdparty-problem`
- `Priority-Low`, `Priority-Medium`, `Priority-High`
- `invalid`, `question`, `need-user-input`, `will close soon`

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
- Avoid Java 12+ for local CLI metadata regeneration; use JDK 8.

For dependency PRs:

- Preserve Java 8 compatibility. Do not accept dependency lines that require Java 9+ or Java 11+.
- Update `.github/dependabot.yml` ignore rules when Dependabot repeatedly proposes non-Java-8-compatible versions.
- Keep release notes concise. Prefer one dependency-maintenance roll-up over one noisy bullet per automated PR unless the change matters to users.

---

## 6. Verify

Use focused tests first, then full verification before release.

Useful focused commands:

```powershell
mvn -pl modules/simple-java-mail -Dtest=SomeTest test
mvn -pl modules/cli-module -am -Ppublish-cli -DskipTests package
```

Before merging to `master` for a release, run a full JDK 8 verification:

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

- Update `README.md` release notes.
- Keep `RELEASE.txt` in sync with the README release notes.
- Update website source under `simplejavamail.org` for API/config documentation changes, but do not push the website unless explicitly approved.
- Add migration notes for behavior changes, removed API, changed defaults, or compatibility-impacting fixes.

Release-note retention:

- `RELEASE.txt` keeps the full release history.
- `README.md` should stay readable and lively, but should not become the full archive.
- On major releases, trim older README release notes while keeping all notes from the current major line.
- Patch releases are absorbed into their parent minor release notes in the same primary note style; do not create a separate patch-release story unless the user asks.
- Minor releases that follow closely in the wake of a major release are absorbed into the current major-line notes the same way.
- The active release-note header may be a version range, for example `v9.0.0 - v9.0.2`, while the body remains organized by feature/fix/dependency sections.
- For older archived release groups, a compact secondary format with each bullet prefixed by the concrete release version is fine.

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

Only release when the user asked for it.

Before release:

1. Confirm `develop` is green locally with JDK 8.
2. Confirm README and `RELEASE.txt` are in sync.
3. Confirm no unrelated local changes remain.
4. Merge `develop` into `master` with a fast-forward merge.
5. Push `master`.

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
5. Create or update the GitHub release according to the user's request.
6. Attach the release assets: CLI standalone archives and sample logging configs.
7. Close the release milestone after all fixed issues are closed.
8. Fast-forward `develop` to `master` and push `develop`.

If a published artifact is wrong or missing, assume the Central release is immutable. Fix the release lane, ship a patch release, and fold the patch into the existing GitHub release only if the user asks for that specific presentation.

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

For a special packaging patch that should be rolled into an existing release, do not create a new GitHub release. Edit the existing release body and attach the patch artifacts there instead.

---

## 11. Dependabot Patch Release

Dependabot PR handling follows the same workflow with extra Java 8 caution:

1. List open PRs and identify Dependabot PRs.
2. Check each proposed dependency for Java 8 bytecode/runtime compatibility.
3. Update or add `.github/dependabot.yml` ignores for impossible upgrade lines.
4. Merge compatible PRs into `develop`.
5. Run full JDK 8 verification.
6. Add a compact dependency-maintenance release-note entry.
7. Release as patch only if requested.

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

- `master` and `develop` are aligned after release.
- The release tag exists remotely.
- Maven Central has the released artifacts.
- CLI standalone ZIP/TAR exist for `cli-module`.
- The GitHub release has the CLI standalone ZIP/TAR and sample logging config assets.
- GitHub release exists or was intentionally folded into another release.
- Related GitHub issues have a short release-availability comment when applicable.
- Worktree is clean.
