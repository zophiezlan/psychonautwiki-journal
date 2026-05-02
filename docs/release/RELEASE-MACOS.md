# macOS release flow

Step-by-step procedure for cutting a signed, notarized, and stapled `.dmg` of
PsychonautWiki Journal for end users on macOS. Read [`RELEASE.md`](../../RELEASE.md)
first for the cross-platform context — this document only covers the
macOS-specific build, sign, notarize, and verify steps.

The Compose Multiplatform plugin already integrates `codesign` and
`notarytool` (see the `macOS { signing {} notarization {} }` block in
[`build.gradle.kts`](../../psychonautwiki-journal-desktop/build.gradle.kts)).
That means signing is driven by env vars at build time, not by a separate
post-build invocation — but you still need to verify and staple yourself.

---

## 0. What you need before you start

### Tooling

| Tool                         | Why                                                                                                                                | Where to get it                                                                  |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| macOS 13+ build host         | `notarytool` requires recent macOS; older notarization paths via `altool` were retired by Apple in late 2023.                      | n/a                                                                              |
| Xcode 14+ Command Line Tools | Provides `codesign`, `notarytool`, `stapler`, `spctl`.                                                                             | `xcode-select --install`                                                         |
| JDK 17                       | Same JDK the project targets. Match `arch` to the build target — building a Universal binary requires the JDK to be Universal too. | Eclipse Temurin 17 (`brew install --cask temurin@17`), or use the Nix dev shell. |
| Gradle wrapper               | `./gradlew` in-tree.                                                                                                               | already vendored                                                                 |

### 0.1 Apple Developer account + cert

You need an **active paid Apple Developer Program** membership (\$99/yr) and a
**Developer ID Application** certificate. Distinct from "Mac App Store"
certs — those only work for Mac App Store distribution. For a directly-downloaded
`.dmg`, you want Developer ID Application.

Create the cert in **Apple Developer → Certificates, Identifiers & Profiles**.
Download the `.cer` and double-click to install in Keychain. Verify:

```bash
security find-identity -v -p codesigning
# Expect a line like:
#   1) AB12CD34… "Developer ID Application: <Org Name> (TEAMID)"
```

The string starting `Developer ID Application:` is what you'll feed to
`APPLE_DEVELOPER_ID` below — character-for-character including parentheses.

### 0.2 Notarization credentials

`notarytool` authenticates by Apple ID + app-specific password (or by an App
Store Connect API key — see § 8 if you want to use the API key path).

Generate an app-specific password at <https://appleid.apple.com/> → Sign-In
and Security → App-Specific Passwords. **Not** your normal Apple ID password.
Format: `xxxx-xxxx-xxxx-xxxx`.

### 0.3 Environment variables

Export these in the shell where you'll run gradle:

```bash
export APPLE_DEVELOPER_ID="Developer ID Application: <Org Name> (<TEAMID>)"
export APPLE_ID="release@your.org"
export APPLE_TEAM_ID="<TEAMID>"           # 10-character alphanumeric, found in your Apple Developer account
export APPLE_APP_SPECIFIC_PASSWORD="xxxx-xxxx-xxxx-xxxx"
```

> **Never** hard-code these in `.env` files committed to git. The
> `APPLE_APP_SPECIFIC_PASSWORD` is a credential. If it leaks, revoke
> immediately at appleid.apple.com — they revoke individually without
> affecting your main password.

The Compose plugin reads these inside `build.gradle.kts`. If any are missing,
gradle silently produces an UNSIGNED build — confirm signing happened in § 4.

---

## 1. Build the signed DMG

From a clean checkout on the release tag:

```bash
git status                               # MUST be clean
git describe --tags --exact-match HEAD   # MUST print the release tag

cd psychonautwiki-journal-desktop
./gradlew clean packageReleaseDmg --no-daemon
```

Output appears at:

```
psychonautwiki-journal-desktop/build/compose/binaries/main-release/dmg/PsychonautWiki Journal-<version>.dmg
```

The Compose plugin signs the embedded `.app` bundle (and its nested JRE +
native libraries) during the `packageReleaseDmg` task itself — there is no
separate `codesign` step you need to run on the bundle. The DMG container
gets signed too.

If the env vars in § 0.3 weren't set, the build still succeeds but produces
an unsigned binary. Sanity check immediately:

```bash
dmg=$(ls "build/compose/binaries/main-release/dmg/"*.dmg | head -1)
codesign -dv --verbose=4 "$dmg" 2>&1 | grep -E "Authority|TeamIdentifier|Identifier"
# Expect:
#   Identifier=org.psychonautwiki.journal       (or whatever bundle id is set)
#   Authority=Developer ID Application: <Org Name> (<TEAMID>)
#   Authority=Developer ID Certification Authority
#   Authority=Apple Root CA
#   TeamIdentifier=<TEAMID>
```

If `Authority=` says "ad hoc" or is missing, the env vars didn't take —
re-export and rebuild.

---

## 2. Smoke-test the signed-but-not-notarized build

A signed-but-not-notarized DMG **will run** on a Mac that already has it
installed (Gatekeeper caches), but **won't open** on a fresh download from a
quarantine-aware browser — Gatekeeper blocks it because notarization is
missing. So local smoke-testing the signed-only build is fine; do NOT ship it
to users until § 3 completes.

Minimum smoke test:

1. Mount the DMG. Drag-install to /Applications.
2. Launch from /Applications. Confirm splash + main window appear.
3. Add an experience, add an ingestion, restart the app, confirm persistence.
   Verifies the SQLite path at `~/.psychonautwiki-journal/database.db`.
4. Quit. Trash the app. Confirm the data dir is left intact (uninstall is
   non-destructive of user data, by design).

If anything fails, fix in a new commit, re-tag, rebuild from § 1. Do **not**
notarize a broken build — Apple's notarization service tracks submissions and
a stream of pulled releases looks suspicious.

---

## 3. Notarize

The Compose plugin will notarize automatically as part of `packageReleaseDmg`
when the `APPLE_ID` / `APPLE_TEAM_ID` / `APPLE_APP_SPECIFIC_PASSWORD` env
vars are set. Watch the gradle output for the notarization log line — Apple's
service typically returns within 5–15 minutes.

If you need to notarize manually (e.g. you signed but skipped notarization
because the Apple ID env vars weren't set during the build):

```bash
dmg="build/compose/binaries/main-release/dmg/PsychonautWiki Journal-1.0.0.dmg"

xcrun notarytool submit "$dmg" \
    --apple-id   "$APPLE_ID" \
    --team-id    "$APPLE_TEAM_ID" \
    --password   "$APPLE_APP_SPECIFIC_PASSWORD" \
    --wait
```

`--wait` blocks until Apple finishes — exit 0 means accepted. If it fails,
fetch the rejection log:

```bash
xcrun notarytool log <submission-id> \
    --apple-id "$APPLE_ID" --team-id "$APPLE_TEAM_ID" --password "$APPLE_APP_SPECIFIC_PASSWORD"
```

Common rejection reasons in § 7.

---

## 4. Staple the notarization ticket

Notarization records the binary in Apple's database, but for users to install
**offline** without an internet check, the ticket must be stapled into the
DMG. The Compose plugin does NOT staple by default — do this yourself
post-notarization:

```bash
xcrun stapler staple "$dmg"
# Should print: The staple and validate action worked!

xcrun stapler validate "$dmg"
# Should print: The validate action worked!
```

Without the staple, first-launch on a network-restricted Mac fails because
Gatekeeper can't reach Apple's servers to confirm notarization status. Ship
ONLY stapled DMGs.

---

## 5. Verify the full chain

Run all three of these on the final, stapled DMG:

```bash
# 1. Signature is valid and matches your team
codesign -dv --verbose=4 "$dmg"

# 2. Gatekeeper accepts the binary as if a fresh user downloaded it
spctl -a -t open --context context:primary-signature -v "$dmg"
# Expect: <path>: accepted
#         source=Notarized Developer ID

# 3. Notarization ticket is stapled
xcrun stapler validate "$dmg"
```

All three must succeed. The `spctl` check specifically uses
`context:primary-signature` to evaluate the signature the same way Gatekeeper
does on a quarantined download — without that context, `spctl` may give a
false positive on locally-built binaries.

For belt-and-braces, copy the DMG to a clean macOS VM, set the quarantine
attribute manually, and try to open it:

```bash
xattr -w com.apple.quarantine "0083;$(printf %x $(date +%s));Safari;" /tmp/test.dmg
open /tmp/test.dmg
```

A correctly-signed-and-stapled DMG opens without warnings. An unsigned or
non-notarized one shows the "cannot be opened because the developer cannot
be verified" dialog.

---

## 6. Hash and publish

Compute the hash of the **stapled** DMG (the staple changes the file, so the
hash from before stapling is different from the one after):

```bash
shasum -a 256 "$dmg"
```

Append `<hash>  PsychonautWiki Journal-<version>.dmg` to `SHA256SUMS` (two
spaces, mind the literal space in the filename). Continue with the PGP
detached-signature step from [`RELEASE.md`](../../RELEASE.md) § 3.

---

## 7. Common failure modes

### Notarization rejected: "The signature of the binary is invalid"

A nested binary inside the .app bundle is unsigned or signed by a different
identity than the outer bundle. Most often a JRE library or a JNI `.dylib`.
Inspect with:

```bash
codesign --display --verbose=2 \
    "build/compose/binaries/main-release/app/PsychonautWiki Journal.app/Contents/runtime/Contents/Home/lib/libjvm.dylib"
```

The Compose plugin should sign these automatically. If it doesn't, your
JDK is from a non-standard distribution that emits unsigned `.dylib`s — switch
to Temurin 17 or Liberica.

### Notarization rejected: "The executable does not have the hardened runtime enabled"

Compose enables hardened runtime by default. If you've added a custom
`compose.desktop.application.nativeDistributions.macOS { entitlements ... }`
block that overrides the default, restore the hardened runtime entitlement.

### "Killed: 9" on first launch

Hardened runtime is blocking a JNI library because the library wasn't signed
with the inheriting entitlement. Look at `Console.app` → Crash Reports for
the offending library, add `<key>com.apple.security.cs.allow-jit</key><true/>`
or `cs.allow-unsigned-executable-memory` to the entitlements as appropriate.
Don't blanket-disable library validation — that defeats the point of
notarization.

### `spctl` says "rejected" but `codesign` is happy

The DMG isn't notarized, or the staple didn't take. Run § 4 again. If
`stapler staple` itself fails with "the ticket lookup failed", the
notarization is still in flight or got rejected — re-check with `notarytool log`.

### `notarytool` returns "Invalid Credentials"

`APPLE_APP_SPECIFIC_PASSWORD` was generated under a different Apple ID, or
got revoked. Generate a new one at <https://appleid.apple.com/>.

### Build succeeds but DMG is unsigned

`security find-identity` returned no signing identity, so Compose silently
fell back to ad-hoc signing. Confirm the cert is installed in the **login**
keychain (not System), and that Xcode can see it — `security
find-identity -v -p codesigning` must list it. If you're on a fresh CI
runner, see § 8.

### "App is damaged and can't be opened"

Almost always a torn-quarantine attribute on a downloaded copy that bypassed
notarization. Verify the published DMG's hash matches `SHA256SUMS` — if they
match, you've shipped a non-stapled DMG; pull and re-cut after § 4.

---

## 8. CI signing (future)

GitHub Actions has macOS runners that can do this end-to-end. The signing
cert exports as a `.p12` file with a password; both go in as Actions secrets.

Skeleton (do not enable until secrets are provisioned):

```yaml
- name: Import signing certificate
  env:
    P12_BASE64: ${{ secrets.MACOS_CERTIFICATE_P12 }}
    P12_PASSWORD: ${{ secrets.MACOS_CERTIFICATE_PASSWORD }}
    KC_PASSWORD: ${{ secrets.MACOS_KEYCHAIN_PASSWORD }}
  run: |
    echo "$P12_BASE64" | base64 --decode > certificate.p12
    security create-keychain -p "$KC_PASSWORD" build.keychain
    security default-keychain -s build.keychain
    security unlock-keychain -p "$KC_PASSWORD" build.keychain
    security import certificate.p12 -k build.keychain \
        -P "$P12_PASSWORD" -T /usr/bin/codesign
    security set-key-partition-list -S apple-tool:,apple: \
        -s -k "$KC_PASSWORD" build.keychain
    security find-identity -v -p codesigning

- name: Build, sign, notarize
  env:
    APPLE_DEVELOPER_ID: ${{ secrets.APPLE_DEVELOPER_ID }}
    APPLE_ID: ${{ secrets.APPLE_ID }}
    APPLE_TEAM_ID: ${{ secrets.APPLE_TEAM_ID }}
    APPLE_APP_SPECIFIC_PASSWORD: ${{ secrets.APPLE_APP_SPECIFIC_PASSWORD }}
  run: |
    cd psychonautwiki-journal-desktop
    ./gradlew packageReleaseDmg --no-daemon

- name: Staple ticket
  run: |
    dmg=$(ls psychonautwiki-journal-desktop/build/compose/binaries/main-release/dmg/*.dmg | head -1)
    xcrun stapler staple "$dmg"
    xcrun stapler validate "$dmg"

- name: Wipe credentials
  if: always()
  run: |
    rm -f certificate.p12
    security delete-keychain build.keychain || true
```

For higher-volume signing, consider switching from app-specific password to
**App Store Connect API key** (`AuthKey_<KEYID>.p8`). It's a static key file
with `--key`, `--key-id`, `--issuer` flags on `notarytool` — better for
non-interactive CI because it doesn't depend on a person's Apple ID.

The `if: always()` on the wipe step is mandatory — same reasoning as the
Windows PFX wipe in [`RELEASE-WINDOWS.md`](RELEASE-WINDOWS.md) § 8.
