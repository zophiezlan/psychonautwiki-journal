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

## 1. Build & sign per platform

Each platform has its own quirks (signing tool, notarization service, hardware
token requirements). Treat the per-platform docs as the source of truth:

| Platform | Doc | Output |
|----------|-----|--------|
| Windows  | [`docs/release/RELEASE-WINDOWS.md`](docs/release/RELEASE-WINDOWS.md) | signed `.msi` |
| macOS    | [`docs/release/RELEASE-MACOS.md`](docs/release/RELEASE-MACOS.md) | signed + notarized + stapled `.dmg` |
| Linux    | [`docs/release/RELEASE-LINUX.md`](docs/release/RELEASE-LINUX.md) | signed `.deb`, signed `.AppImage` |

Each platform-specific doc walks through:

1. Building the unsigned artefact (`gradle packageRelease<Format>`).
2. Smoke-testing **before** signing, so reputation isn't burned on a broken
   build.
3. Applying the platform's signature with proper timestamping.
4. Verifying the signature with the platform's verification tool
   (`spctl`, `signtool verify /pa`, `dpkg-sig --verify`).

The Compose Multiplatform plugin reads `APPLE_*` env vars (see
`build.gradle.kts`) for macOS and skips signing when they're absent;
Windows and Linux signing currently happen as a separate post-build step.

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
- [ ] All platforms built from the SAME commit (verify with `git rev-parse HEAD` on each build host)
- [ ] macOS: `spctl -a -t open --context context:primary-signature -v *.dmg` accepts AND `xcrun stapler validate *.dmg` succeeds ([`docs/release/RELEASE-MACOS.md`](docs/release/RELEASE-MACOS.md) § 5)
- [ ] Windows: `signtool verify /pa /v *.msi` reports zero errors AND a timestamp ([`docs/release/RELEASE-WINDOWS.md`](docs/release/RELEASE-WINDOWS.md) § 4)
- [ ] Linux: `dpkg-sig --verify *.deb` AND `appimagetool --validate *.AppImage` both succeed ([`docs/release/RELEASE-LINUX.md`](docs/release/RELEASE-LINUX.md) § 5)
- [ ] `SHA256SUMS` covers EVERY released artefact (signed copies, not the unsigned intermediates)
- [ ] `SHA256SUMS.asc` is a detached PGP signature of `SHA256SUMS`
- [ ] Release notes link to the verification commands
- [ ] No build env var (`APPLE_*`, `WIN_SIGN_*`) appears in CI logs — search the run output before publishing
- [ ] PFX / signing materials wiped from any CI runner that touched them (use `if: always()` cleanup steps)
