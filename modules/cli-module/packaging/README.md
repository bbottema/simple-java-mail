# Homebrew and Chocolatey release sources

These files are inputs for publishing the SJM CLI through popular package managers. They are not included in the portable CLI archive and they do not install a second daemon product. Homebrew and Chocolatey install the same `sjm` command; `sjm send -d` starts or reuses its per-user daemon on demand.

Publication and lifecycle validation are tracked by [issue #708](https://github.com/bbottema/simple-java-mail/issues/708). The portable 10.0.0 ZIP and tar remain independently releasable.

Package installation never starts or enables the daemon. Homebrew users may explicitly keep it running with `brew services start sjm`. Chocolatey users can rely on on-demand acquisition or explicitly run the normal `sjm daemon` commands.

## Render a release

Build and publish the versioned standalone tar and ZIP first, then render the package sources with their public URLs and SHA-256 checksums:

```powershell
./Render-PackageTemplates.ps1 `
    -Version 10.0.0 `
    -ReleaseArchiveUrl https://github.com/bbottema/simple-java-mail/releases/download/10.0.0/cli-module-10.0.0-standalone-cli.tar `
    -ReleaseArchiveSha256 <64-hex-character-sha256> `
    -WindowsArchiveUrl https://github.com/bbottema/simple-java-mail/releases/download/10.0.0/cli-module-10.0.0-standalone-cli.zip `
    -WindowsArchiveSha256 <64-hex-character-sha256> `
    -PackageSourceRevision <40-hex-character-commit-sha> `
    -OutputDirectory ./target/package-sources
```

`PackageSourceRevision` is the immutable commit containing these package sources. This remains valid if the packages are published after the 10.0.0 Git tag was created.

The renderer rejects non-HTTPS URLs, malformed checksums, and unresolved template tokens. CI additionally parses the Homebrew formula with Ruby and builds the Chocolatey package with `choco pack`.

Rendering and packing are only source validation. Publishing to a project tap/feed, Homebrew/core, or the Chocolatey Community Repository remains an explicit release action and requires the real package-manager lifecycle tests described in the improvement plan.
