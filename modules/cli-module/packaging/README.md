# Homebrew and Chocolatey release sources

These are thin wrappers around the public standalone CLI archives. Homebrew downloads the tar, Chocolatey downloads the ZIP, and both expose the same `sjm` command. Neither package starts a daemon or installs a service. Use `sjm send -d` or the normal `sjm daemon ...` commands when a daemon is useful.

The portable ZIP and tar remain supported on their own. Package-manager publication is tracked by [issue #708](https://github.com/bbottema/simple-java-mail/issues/708).

## Prepare a public release

Create the GitHub release and upload its versioned standalone tar and ZIP first. From a checkout of the matching release tag, run:

```powershell
./Prepare-PackageRelease.ps1 `
    -Version 10.0.0 `
    -OutputDirectory ./target/package-sources
```

The preparation command requires a public, non-draft, non-prerelease GitHub release. It finds the exact versioned assets, downloads them, calculates their SHA-256 hashes, checks GitHub's asset digests when present, resolves the tag to a commit, and requires the current checkout to be that commit. It then renders both package sources and writes `package-release.json` with the verified coordinates.

`Render-PackageTemplates.ps1` remains available for focused template development. It rejects malformed versions and hashes, non-HTTPS URLs, invalid source revisions, and unresolved tokens.

## Validate package sources

On a machine with Ruby and Chocolatey:

```powershell
./Test-PackageSources.ps1 `
    -SourceDirectory ./target/package-sources `
    -PackageOutputDirectory ./target/packages
```

This parses every Chocolatey PowerShell file, runs `ruby -c` on the formula, and runs `choco pack`. The resulting Chocolatey package contains only its NuSpec and install hooks; the CLI ZIP is still downloaded from the checksum-pinned GitHub release URL during installation.

## CircleCI release actions

Ordinary pipelines leave `package_action` at `none`. After the public GitHub release exists, explicitly trigger a pipeline on `master` with a `package_version` and either:

- `package_action: smoke` for the one-time macOS Homebrew and Windows Chocolatey installation checks;
- `package_action: publish` for release validation and idempotent publication.

The smoke workflow is only needed again when package installation behavior or the templates materially change. Normal later releases can go straight to `publish`.

The publish job uses the project-restricted `SJM_PACKAGE_PUBLISHING` context with:

- `HOMEBREW_TAP_TOKEN`: a fine-grained GitHub token with contents write access only to `simple-java-mail/homebrew-tap`;
- `CHOCO_API_KEY`: the Chocolatey Community API key.

It skips an exact Chocolatey version that already exists and only commits the Homebrew formula when its content changed, so rerunning after a partial success is safe. Package jobs do not persist artifacts or workspaces.
