# Release procedure

> Anything you publish under the project's name MUST be a signed binary
> accompanied by a SHA-256 checksum and a detached PGP signature of the
> checksum file. Unsigned binaries are unverifiable by users and indistinguishable
> from a trojaned drop.

## 0. Prerequisites

| Platform | What you need |
|----------|---------------|
| macOS    | An active Apple Developer ID Application certificate, an Apple ID with an app-specific password, your team ID. |
| Windows  | An Authenticode code-signing certificate (EV recommended) exported as `.pfx`, plus its passphrase. |
| Linux    | A PGP key for signing the `.deb` and the `SHA256SUMS` file. |
| All      | A clean checkout on a tag (`git checkout vX.Y.Z`) — never release from a dirty working tree. |

## 1. Build

```bash
cd psychonautwiki-journal-desktop

# macOS — sign + notarize
APPLE_DEVELOPER_ID="Developer ID Application: <Org Name> (<TEAMID>)" \
APPLE_ID="release@your.org" \
APPLE_TEAM_ID="<TEAMID>" \
APPLE_APP_SPECIFIC_PASSWORD="xxxx-xxxx-xxxx-xxxx" \
gradle packageReleaseDmg

# Linux — produces an unsigned .deb / AppImage; sign in step 2.
gradle packageReleaseDeb packageReleaseAppImage

# Windows — produces an unsigned .msi; sign in step 2.
gradle packageReleaseMsi
```

The Compose Multiplatform plugin reads the env vars above (see `build.gradle.kts`)
and skips signing when they're absent. Verify after each build:

```bash
codesign -dv --verbose=4 build/compose/binaries/main-release/dmg/*.dmg
spctl -a -t open --context context:primary-signature -v build/compose/binaries/main-release/dmg/*.dmg
```

## 2. Sign Windows / Linux artefacts

```bash
# Windows — Authenticode via signtool from the Windows SDK
signtool sign /f "$WIN_SIGN_PFX_PATH" /p "$WIN_SIGN_PFX_PASSWORD" \
  /tr http://timestamp.digicert.com /td sha256 /fd sha256 \
  build/compose/binaries/main-release/msi/PsychonautWikiJournal-*.msi

# Linux — sign the .deb with dpkg-sig
dpkg-sig --sign builder build/compose/binaries/main-release/deb/*.deb
```

## 3. Generate checksums

```bash
cd build/compose/binaries/main-release
sha256sum dmg/*.dmg msi/*.msi deb/*.deb appimage/*.AppImage > SHA256SUMS
gpg --armor --detach-sign --output SHA256SUMS.asc SHA256SUMS
```

Publish `SHA256SUMS` and `SHA256SUMS.asc` next to the binaries on every release
page. The README installation instructions MUST link to verification steps:

```
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS
```

## 4. Reproducible build verification

The `flake.nix` derivation pins the inputs via `flake.lock`. To let third
parties rebuild and confirm the published checksums match a from-source build:

```bash
nix build .#default
sha256sum result/bin/*
```

If the hash differs from the released artefact, treat the released artefact as
suspect and pull it.

## 5. Release checklist

- [ ] Tag matches version in `build.gradle.kts` (`packageVersion`)
- [ ] `flake.lock` committed
- [ ] All four platforms built from the SAME commit
- [ ] macOS: `spctl` accepts the .dmg
- [ ] Windows: `signtool verify /pa /v *.msi` succeeds
- [ ] Linux: `dpkg-sig --verify *.deb` succeeds
- [ ] `SHA256SUMS` and `SHA256SUMS.asc` published
- [ ] Release notes link to the verification commands
- [ ] No build env var (APPLE_*, WIN_SIGN_*) leaked into CI logs
