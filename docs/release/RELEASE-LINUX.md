# Linux release flow

Step-by-step procedure for cutting signed `.deb` and `.AppImage` artefacts of
PsychonautWiki Journal for end users on Linux. Read [`RELEASE.md`](../../RELEASE.md)
first for the cross-platform context — this document only covers the
Linux-specific build, sign, and verify steps.

Linux has no equivalent of macOS Gatekeeper or Windows SmartScreen. End users
verify provenance themselves via the GPG-signed `SHA256SUMS.asc` (the
cross-platform mechanism in `RELEASE.md`). The per-format signing in this
document is **additional** — it lets users who run `dpkg-sig --verify` or
AppImage's bundled validator confirm the package without leaving their package
manager. It does not replace the top-level checksum signing.

---

## 0. What you need before you start

### Tooling

| Tool | Why | Where to get it |
|------|-----|-----------------|
| Linux build host | Ideally the same one that builds the Nix flake — guarantees the binary matches the reproducibility check in `RELEASE.md` § 4. | n/a |
| JDK 17 | Same JDK the project targets. | Ships in the Nix dev shell. |
| `dpkg-sig` | Embeds a GPG signature in the `.deb`. | `apt install dpkg-sig` (Debian/Ubuntu) or `nix shell nixpkgs#dpkg-sig`. |
| `appimagetool` | Re-packs and signs the AppImage. | <https://github.com/AppImage/AppImageKit/releases> — use the `--sign` flag. |
| `gpg` 2.2+ | Signing key infrastructure. Required by both `dpkg-sig` and `appimagetool`. | already in any reasonable distro |
| `sha256sum` | Cross-platform checksum (covered in `RELEASE.md`). | coreutils |

### 0.1 GPG signing key

You need a **dedicated** signing key for releases — not your personal commit-
signing key. Distinct keys make rotation safer: if a release machine is
compromised, you revoke that key without invalidating your commit history.

Generate once, store the secret offline (paper backup + encrypted USB):

```bash
gpg --quick-generate-key "PsychonautWiki Journal Releases <release@psychonautwiki.org>" \
    ed25519 sign 2y
```

Get the long key ID — that's what you'll quote in `RELEASE.md` and in the
release notes so users can fetch it for verification:

```bash
gpg --list-secret-keys --keyid-format LONG release@psychonautwiki.org
# Look for: sec   ed25519/<KEYID> ...
```

Publish the public key to a keyserver and to the project README so users can
fetch it:

```bash
gpg --send-keys <KEYID>
gpg --armor --export <KEYID> > pubkey.asc
# Commit pubkey.asc to docs/release/.
```

### 0.2 Environment variables

```bash
export GPG_KEY_ID="<KEYID>"
export GPG_TTY=$(tty)         # required for pinentry on headless build hosts
```

The `GPG_TTY` export matters when you're SSH'd into a remote build host —
without it, pinentry can't prompt for the passphrase and signing hangs.

---

## 1. Build the unsigned artefacts

From a clean checkout on the release tag:

```bash
git status                               # MUST be clean
git describe --tags --exact-match HEAD   # MUST print the release tag

cd psychonautwiki-journal-desktop
./gradlew clean packageReleaseDeb packageReleaseAppImage --no-daemon
```

Output appears at:

```
psychonautwiki-journal-desktop/build/compose/binaries/main-release/deb/psychonautwiki-journal_<version>-1_amd64.deb
psychonautwiki-journal-desktop/build/compose/binaries/main-release/appimage/PsychonautWikiJournal-<version>.AppImage
```

(`packageName = "psychonautwiki-journal"` for the deb is set in
[`build.gradle.kts`](../../psychonautwiki-journal-desktop/build.gradle.kts);
the AppImage takes the upper-camel package name.)

Sanity check before signing:

```bash
deb=$(ls build/compose/binaries/main-release/deb/*.deb | head -1)
appimage=$(ls build/compose/binaries/main-release/appimage/*.AppImage | head -1)

dpkg-deb --info "$deb"      # Inspect control fields
dpkg-deb --contents "$deb"  # Inspect file list — sanity-check the bundled JRE is there
file "$appimage"            # Should be "ELF 64-bit LSB executable" or "ISO Media"
```

---

## 2. Smoke-test the unsigned builds

Every distro release flow gets this test. Pick one Debian-family distro
(Debian 12 stable or Ubuntu 22.04 LTS) and one neutral environment for the
AppImage (a fresh user account on any Linux). VMs or Docker containers are
fine.

`.deb`:

```bash
sudo apt install ./psychonautwiki-journal_<version>-1_amd64.deb
psychonautwiki-journal &     # Or launch from menu
# Add experience, ingestion, restart, confirm persistence at
# ~/.psychonautwiki-journal/database.db
sudo apt remove psychonautwiki-journal
ls -la ~/.psychonautwiki-journal/   # User data left intact (by design)
```

AppImage:

```bash
chmod +x PsychonautWikiJournal-<version>.AppImage
./PsychonautWikiJournal-<version>.AppImage
# Same smoke-test sequence
```

Common failure on the AppImage: missing FUSE on the host. The AppImage will
print "AppImages require FUSE to run". Tell users in the release notes:
`apt install libfuse2` (Ubuntu 22.04+) or `--appimage-extract-and-run` as a
fallback. Don't sign over a broken AppImage.

---

## 3. Sign the .deb

```bash
dpkg-sig --sign builder --gpg-options="--local-user $GPG_KEY_ID" "$deb"
```

`--sign builder` is the conventional role name for build-side signatures
(distinct from `--sign repo` which a repository operator would apply). The
`--gpg-options` flag is required when you have multiple secret keys —
without it `dpkg-sig` picks the default key, which is rarely what you want.

Verify locally with the same key:

```bash
dpkg-sig --verify "$deb"
# Expect:
#   Processing <file>...
#   GOODSIG _gpgbuilder <KEYID> <YYYY-MM-DDTHH:MM:SS+ZZZZ>
```

Verify with **only the public key** (simulates an end user) by exporting the
public key to a fresh GPG home:

```bash
mkdir -p /tmp/gpg-test && chmod 700 /tmp/gpg-test
gpg --homedir /tmp/gpg-test --import pubkey.asc
GNUPGHOME=/tmp/gpg-test dpkg-sig --verify "$deb"
rm -rf /tmp/gpg-test
```

If verification with only the public key succeeds, the signature is
self-contained and end users can verify the same way.

---

## 4. Sign the AppImage

AppImage embeds the signature into a dedicated section of the ELF file —
`appimagetool --sign` re-packs the AppImage and writes the signature inline.

```bash
appimagetool --sign --sign-key "$GPG_KEY_ID" "$appimage"
```

Verify with the AppImage's bundled validator:

```bash
"$appimage" --appimage-signature
# Prints the embedded signature; compare against your key.

# Or use the standalone validate tool:
appimagetool --validate "$appimage"
```

Note the signature is over the AppImage's content **before** the inline
signature section is written, not over the final file — so the SHA-256 of
the file changes after signing. Compute the published hash AFTER signing.

---

## 5. Verify (full chain)

Run all of these on the final, signed artefacts:

```bash
# .deb chain
dpkg-sig --verify "$deb"

# AppImage chain
appimagetool --validate "$appimage"

# Cross-platform — every artefact must end up in SHA256SUMS,
# which is itself signed with $GPG_KEY_ID per RELEASE.md § 3.
sha256sum "$deb" "$appimage"
```

Belt-and-braces: copy both files to a fresh user on a fresh distro, import
only the public key, and run the verifications. If they pass without
network access, end users can do the same offline.

---

## 6. Hash and publish

Compute hashes of the **signed** files (signing changes the bytes):

```bash
sha256sum "$deb" "$appimage" >> SHA256SUMS
```

Continue with the PGP detached-signature step from
[`RELEASE.md`](../../RELEASE.md) § 3 — `SHA256SUMS.asc` covers all platforms
in one go.

Publish next to the release artefacts:

- `psychonautwiki-journal_<version>-1_amd64.deb`
- `PsychonautWikiJournal-<version>.AppImage`
- `SHA256SUMS`
- `SHA256SUMS.asc`
- `pubkey.asc` (the same key used to sign all of the above)

The release notes MUST include the verification recipe:

```bash
gpg --import pubkey.asc
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS
dpkg-sig --verify *.deb           # Optional: per-deb signature
"$appimage" --appimage-signature  # Optional: per-AppImage signature
```

---

## 7. Distribution channels (optional)

These are downstream of the signing flow above and add reach without changing
the release procedure.

### Flathub

A Flatpak manifest gets built by Flathub's CI from the upstream tarball you
publish. You don't sign the Flatpak yourself — Flathub signs the OSTree
commits with its own key. Submission flow:
<https://github.com/flathub/flathub/wiki/App-Submission>. The maintainer
needs to commit to keeping the manifest in sync with each release.

### AUR

Arch users typically prefer AUR over Flatpak. A `PKGBUILD` referencing the
GitHub release URL + the `SHA256SUMS` hash gives them what they want. The
PKGBUILD lives in the AUR repo, not this one. Bonus: AUR users automatically
verify the SHA-256, so a tampered release fails to build for them.

### APT repository

If you ever want users to `apt install psychonautwiki-journal` from a hosted
repo, you'll need to sign the `Release` file (per-repo, not per-deb) with
`apt-ftparchive` + `gpg`. Out of scope for this doc; see Debian's
[`debian-handbook` § 6.3.4](https://debian-handbook.info/browse/stable/sect.setup-apt-package-repository.html).

### Snap Store

Possible via `snapcraft.yaml`, but Compose Desktop apps in Snaps run into the
classic Snap Java + JDK packaging issues (the snap needs `java-runtime` or
similar). Avoid unless you have a specific reason — Flatpak plays nicer with
a self-bundled JRE.

---

## 8. Common failure modes

### `dpkg-sig --verify` fails on a freshly-signed .deb

The most common cause is that `dpkg-sig` couldn't find your key in the
default keyring (e.g. you generated the key as a different user, or the
keyring is on a removable drive). Set `GNUPGHOME` explicitly:

```bash
GNUPGHOME=$HOME/.gnupg dpkg-sig --verify "$deb"
```

### AppImage signature missing after `appimagetool --sign`

`appimagetool` silently no-ops the signature when it can't find the signing
key. Verbose flag tells you why:

```bash
appimagetool --sign --sign-key "$GPG_KEY_ID" --verbose "$appimage"
```

If pinentry is not interactive (e.g. Docker build), set `gpg-agent` to
loopback mode in `~/.gnupg/gpg-agent.conf`: `allow-loopback-pinentry`, then
`gpg --pinentry-mode loopback ...` for the signing call.

### "GPG signing failed" during `gradle packageReleaseDeb`

The Compose plugin does **not** sign `.deb` files itself — it only handles
macOS code signing. If you see this error, you've enabled some external
plugin that does. Either remove that plugin or wire its config to the env
vars in § 0.2.

### .deb installs but launcher icon missing

The icon path in `linux { iconFile.set(...) }` in `build.gradle.kts` points
at a missing file. The `.deb` installs without erroring because dpkg
considers the icon optional. Fix the path; resign.

### AppImage refuses to run with "/proc/self/exe: No such file or directory"

Running on a system without `binfmt_misc` and no FUSE. Tell users:
`./PsychonautWikiJournal.AppImage --appimage-extract-and-run`, OR install
`libfuse2` (Ubuntu 22.04+ name) / `fuse2` (older).

### Reproducibility check fails

The reproducibility check in `RELEASE.md` § 4 expects `nix build .#default`
to produce a binary whose hash matches the released artefact. If hashes
diverge:

1. Confirm `flake.lock` is committed to the release tag. If not, the
   reproducibility derivation pulled a different `nixpkgs` than the release
   build.
2. Confirm the release was built from the same commit as the tag.
   `git describe --tags --exact-match` on each build host.
3. The signature itself changes the bytes — `nix build` produces an
   unsigned binary, so its hash will differ from the **signed** released
   binary. Compare hashes against the **unsigned intermediate** in
   `build/compose/binaries/main-release/...` before signing.

---

## 9. CI signing (future)

Linux CI signing is the easiest of the three platforms because there's no
hardware-token requirement and no notarization step. The signing key goes in
as a base64-encoded GitHub Actions secret.

Skeleton (do not enable until secrets are provisioned):

```yaml
- name: Import GPG signing key
  env:
    GPG_PRIVATE_KEY:        ${{ secrets.GPG_PRIVATE_KEY_BASE64 }}
    GPG_PASSPHRASE:         ${{ secrets.GPG_PASSPHRASE }}
  run: |
    echo "$GPG_PRIVATE_KEY" | base64 --decode | gpg --batch --import
    echo "allow-loopback-pinentry" >> ~/.gnupg/gpg-agent.conf
    echo RELOADAGENT | gpg-connect-agent
    # Pre-cache the passphrase so dpkg-sig / appimagetool don't prompt.
    echo "test" | gpg --batch --pinentry-mode loopback \
        --passphrase "$GPG_PASSPHRASE" --clearsign > /dev/null

- name: Build artefacts
  run: |
    cd psychonautwiki-journal-desktop
    ./gradlew packageReleaseDeb packageReleaseAppImage --no-daemon

- name: Sign .deb
  env:
    GPG_KEY_ID: ${{ secrets.GPG_KEY_ID }}
  run: |
    deb=$(ls psychonautwiki-journal-desktop/build/compose/binaries/main-release/deb/*.deb | head -1)
    dpkg-sig --sign builder --gpg-options="--local-user $GPG_KEY_ID" "$deb"
    dpkg-sig --verify "$deb"

- name: Sign AppImage
  env:
    GPG_KEY_ID: ${{ secrets.GPG_KEY_ID }}
  run: |
    appimage=$(ls psychonautwiki-journal-desktop/build/compose/binaries/main-release/appimage/*.AppImage | head -1)
    appimagetool --sign --sign-key "$GPG_KEY_ID" "$appimage"
    appimagetool --validate "$appimage"

- name: Wipe key material
  if: always()
  run: |
    gpgconf --kill all
    rm -rf ~/.gnupg
```

The `if: always()` on the wipe step is mandatory — same reasoning as the
Windows PFX wipe in [`RELEASE-WINDOWS.md`](RELEASE-WINDOWS.md) § 8 and the
macOS keychain wipe in [`RELEASE-MACOS.md`](RELEASE-MACOS.md) § 8.
