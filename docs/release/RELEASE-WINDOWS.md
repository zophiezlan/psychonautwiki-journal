# Windows release flow

Step-by-step procedure for cutting a signed `.msi` of PsychonautWiki Journal
for end users on Windows. Read [`RELEASE.md`](../../RELEASE.md) first for the
cross-platform context (checksums, PGP signature, reproducibility check) — this
document only covers the Windows-specific build, sign, and verify steps.

---

## 0. What you need before you start

### Tooling

| Tool | Why | Where to get it |
|------|-----|-----------------|
| Windows 10/11 build host | Compose Multiplatform's `packageReleaseMsi` only emits an MSI when run on Windows, because it shells out to WiX. | n/a |
| JDK 17+ | Same JDK the project targets. | Use the Nix dev shell on WSL2, or install Eclipse Temurin 17 directly. |
| Gradle wrapper | `./gradlew.bat` is in-tree; do NOT use a globally installed Gradle. | already vendored |
| WiX Toolset 3 | Compose's MSI packager invokes WiX. | Prefer `wix314-binaries.zip` from the WiX v3.14.1 release and add its folder to `%PATH%`. The `winget install WiXToolset.WiX` installer can work too, but some Windows hosts block it on the legacy `.NET Framework 3.5` prerequisite. v4 is **not** supported by the current Compose plugin. |
| Windows SDK (`signtool.exe`) | Authenticode signing + verification. | `winget install Microsoft.WindowsSDK.10.0.22621` (or whichever SDK matches your Windows build). Confirm `signtool.exe` is on `%PATH%`. |
| Your code-signing certificate | Authenticode signature. | See § 0.1. |

### 0.0 WiX setup when the installer fails on `.NET Framework 3.5`

If the WiX installer refuses to install because `NetFx3` is missing, do **not**
burn time on that first. For this project, Compose only needs the WiX command
line tools such as `candle.exe` and `light.exe`, so the binaries-only zip is
enough.

Recommended path:

1. Download `wix314-binaries.zip` from the WiX v3.14.1 release: <https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm>
2. Extract it somewhere stable, for example:

```powershell
New-Item -ItemType Directory -Force C:\Tools\wix314 | Out-Null
Expand-Archive .\wix314-binaries.zip -DestinationPath C:\Tools\wix314 -Force
$env:PATH = "C:\Tools\wix314;$env:PATH"
```

3. Confirm the tools are visible:

```powershell
candle.exe -?
light.exe -?
```

Both commands should print a WiX 3.14.x banner. Once they do, `.
gradlew.bat packageReleaseMsi` can use them without the MSI installer ever
being installed system-wide.

If you specifically want the full WiX installer, enable the optional Windows
feature first, then retry the install:

```powershell
DISM /Online /Enable-Feature /FeatureName:NetFx3 /All
```

That step may need installation media or Windows Update access depending on how
the machine is managed.

### 0.1 Code-signing certificate

You need an Authenticode certificate from a Microsoft-trusted CA (DigiCert,
Sectigo, GlobalSign, SSL.com, etc.). Two flavours:

- **OV (Organisation Validation)**: cheaper, software-only. SmartScreen
  reputation has to be earned over time — early users see a "Windows protected
  your PC" warning until enough installs without complaints accumulate.
- **EV (Extended Validation)**: hardware-token (FIPS 140-2) only — you cannot
  export the key, you sign by plugging the token in. SmartScreen trusts EV
  signatures immediately. Recommended for a harm-reduction app where a
  scary-looking warning is the difference between a user installing or not.

For the rest of this doc, assume EV via a USB hardware token. Adjust the
`signtool` invocation if you're on a `.pfx` file (see § 4.2).

### 0.2 Environment variables

The build only signs when these are set. Export them in a session that is NOT
the same shell where you'll commit code (so they can never end up in
`.bash_history` or PowerShell command history committed to a dotfiles repo):

```powershell
# Required for signing — NOT used by the gradle build directly today,
# but post-build signtool reads them.
$env:WIN_SIGN_DESCRIPTION    = "PsychonautWiki Journal"
$env:WIN_SIGN_URL            = "https://psychonautwiki.org/"
$env:WIN_SIGN_TIMESTAMP_URL  = "http://timestamp.digicert.com"

# When using a .pfx file (OV cert):
# $env:WIN_SIGN_PFX_PATH     = "C:\path\to\codesign.pfx"
# $env:WIN_SIGN_PFX_PASSWORD = "..."   # avoid: store in Credential Manager and read with cmdkey

# When using an EV hardware token, signtool picks the token automatically;
# no extra env vars needed.
```

> **Never** put the PFX password in version control, in CI logs, or in a
> non-rotating env file. If you must use a PFX in CI, store it as a GitHub
> Actions secret and base64-decode at run time.

---

## 1. Build the unsigned MSI

From a clean checkout on the release tag:

```powershell
git status                               # MUST be clean
git describe --tags --exact-match HEAD   # MUST print the release tag

cd psychonautwiki-journal-desktop
.\gradlew.bat clean packageReleaseMsi --no-daemon
```

Output appears at:

```
psychonautwiki-journal-desktop\build\compose\binaries\main-release\msi\PsychonautWikiJournal-<version>.msi
```

Sanity check before signing:

```powershell
$msi = Get-ChildItem build\compose\binaries\main-release\msi\*.msi |
       Sort-Object LastWriteTime -Descending |
       Select-Object -First 1

# Should match the version in build.gradle.kts (packageVersion)
$msi.Name

# Should be in the 100-200 MB range; a tiny file means the build short-circuited.
"{0:N1} MB" -f ($msi.Length / 1MB)
```

If `packageReleaseMsi` succeeded but no MSI was produced, the most common
cause is WiX 4 being on `%PATH%` instead of WiX 3. Run `candle.exe -?` and
confirm the version line says 3.x.

If WiX was installed from `wix314-binaries.zip`, this same check is also how
you confirm `%PATH%` is pointing at the extracted binaries folder rather than a
missing or incompatible system install.

---

## 2. Smoke-test the unsigned MSI before signing

Always install the unsigned build on a clean Windows VM and exercise the
golden path before burning the signature timestamp on it. Signing a broken
MSI just buys you a trusted broken MSI.

Minimum smoke test:

1. Install via `msiexec /i PsychonautWikiJournal-<version>.msi /qb` (basic UI)
   — confirm exit code 0.
2. Launch from Start menu — confirm the splash + main window appear.
3. Add an experience, add an ingestion, restart the app, confirm the data
   persists. This exercises the SQLite path at `%USERPROFILE%\.psychonautwiki-journal\database.db`.
4. Uninstall via Apps & Features — confirm it removes cleanly.

If anything in the smoke test fails, fix in a new commit, re-tag, and rebuild
from § 1. Do NOT sign and ship a broken MSI under the assumption you'll patch
later — the cached SmartScreen reputation will follow the broken release.

---

## 3. Sign the MSI

### 3.1 With an EV hardware token

Plug the token in, then:

```powershell
$msi = "build\compose\binaries\main-release\msi\PsychonautWikiJournal-1.0.0.msi"

signtool sign `
    /tr     $env:WIN_SIGN_TIMESTAMP_URL `
    /td     sha256 `
    /fd     sha256 `
    /a `
    /d      $env:WIN_SIGN_DESCRIPTION `
    /du     $env:WIN_SIGN_URL `
    $msi
```

Flag-by-flag justification (do not omit any):

| Flag | What it does | Why mandatory |
|------|--------------|---------------|
| `/tr` | RFC 3161 timestamp URL | Without a timestamp, the signature expires when the cert expires (typically 1–3 years), and every install after that triggers a "this software is from an unverified publisher" warning. The timestamp pins the signature's validity to the moment of signing, which a CA-backed timestamp authority attests to — so the signature stays valid for the cert's full revocation horizon (~10 years for the timestamp itself). |
| `/td sha256` | Timestamp digest algorithm | SHA-1 is deprecated; Windows 10+ SmartScreen will not honour SHA-1 timestamps. |
| `/fd sha256` | File digest algorithm | Same — SHA-1 file digests are no longer accepted. |
| `/a` | Auto-select best cert from the available stores | With an EV token, this picks the token's cert. Without `/a` you'd need `/n "Common Name"` or `/sha1 <thumbprint>`. |
| `/d` | Description shown in the UAC prompt | This is what the user sees. Spelled out, no abbreviations. |
| `/du` | URL shown in the UAC prompt's "More info" link | Lets users verify the publisher independently. |

### 3.2 With a PFX file (OV cert)

```powershell
signtool sign `
    /f      $env:WIN_SIGN_PFX_PATH `
    /p      $env:WIN_SIGN_PFX_PASSWORD `
    /tr     $env:WIN_SIGN_TIMESTAMP_URL `
    /td     sha256 `
    /fd     sha256 `
    /d      $env:WIN_SIGN_DESCRIPTION `
    /du     $env:WIN_SIGN_URL `
    $msi
```

### 3.3 Dual-sign (only if you absolutely need to support pre-Windows-10)

You don't. Skip.

---

## 4. Verify the signature

```powershell
signtool verify /pa /v $msi
```

`/pa` selects the Authenticode root, `/v` is verbose. The output MUST include:

```
Successfully verified: ...PsychonautWikiJournal-<version>.msi

Number of files successfully Verified: 1
Number of warnings: 0
Number of errors: 0
```

…AND a "The signature is timestamped" line. If timestamping is missing, re-sign
— do **not** ship a non-timestamped binary.

Cross-check from a non-developer machine using the GUI: right-click the MSI →
Properties → Digital Signatures → Details. Subject Name must match your
organisation, the signature must be SHA-256, and the countersignature
(timestamp) must be present.

---

## 5. Hash and publish

The cross-platform release procedure expects every artefact to land in
`SHA256SUMS`. Compute the hash of the **signed** MSI:

```powershell
Get-FileHash -Algorithm SHA256 $msi | Format-List
```

Append `<hash>  PsychonautWikiJournal-<version>.msi` to `SHA256SUMS` (use two
spaces between hash and filename — `sha256sum -c` on Linux will reject one
space). Then continue with the PGP detached-signature step from
[`RELEASE.md`](../../RELEASE.md) § 3.

---

## 6. SmartScreen reputation

The first time a fresh signature appears on Windows, SmartScreen shows the
"Windows protected your PC" blue dialog. The user must click "More info" →
"Run anyway" to install. Two paths shorten this period:

- **EV certificates bypass it immediately.** This is the main reason to spend
  the extra ~$300/yr on an EV cert.
- **OV certificates earn reputation organically.** Microsoft documents no
  exact threshold but in practice it's "a few hundred installs without user
  reports of malicious behaviour, over a few weeks." Reputation is per-cert,
  so renewing or rotating the cert resets it.

You can submit a binary to Microsoft for accelerated reputation review at
<https://www.microsoft.com/en-us/wdsi/filesubmission> — useful for the v1.0
release if you're on an OV cert. They turn around in 1–3 business days.

---

## 7. Common failure modes

### "The specified PFX password is not correct"

`/p` must be the export password the PFX was created with, not your account
password. If you've forgotten it, the PFX is unrecoverable; ask the CA to
re-issue.

### "SignTool Error: An error occurred while attempting to sign: <file>. Error: 0x800B010A"

Cert chain validation failed. The signing cert's intermediate is missing from
the local cert store. Import the CA's intermediate from the CA's website into
`Cert:\LocalMachine\CA`.

### MSI installs but app launches a black window

Compose Desktop with the `Msi` target sometimes doesn't bundle the JRE
correctly when the build host has a non-standard JDK. Run from `build\compose\binaries\main-release\app` first to verify the un-MSI'd app works,
then rebuild the MSI from a Nix dev shell or a freshly installed Temurin 17.

### `signtool.exe` not found

The Windows SDK installer doesn't add SDK tools to `%PATH%`. Either invoke by
absolute path —

```powershell
& "C:\Program Files (x86)\Windows Kits\10\bin\10.0.22621.0\x64\signtool.exe" sign ...
```

— or add the SDK's `bin\<version>\x64` to `%PATH%` for your release session
only.

### WiX installer fails asking for `.NET Framework 3.5`

Use `wix314-binaries.zip` instead of the installer; for Compose packaging in
this repository, the extracted command-line tools are sufficient as long as
`candle.exe` and `light.exe` are on `%PATH%`. If you still want the installer,
enable `NetFx3` with `DISM /Online /Enable-Feature /FeatureName:NetFx3 /All`
and retry.

### Signature verifies on the build machine but fails on customer machines

Almost always a missing intermediate cert. The user's machine doesn't have the
CA's intermediate cached. Two fixes:

1. **Cross-sign** the binary so the chain terminates at a Microsoft root
   already on every Windows install. Most commercial CAs do this by default;
   if yours doesn't, ask.
2. **Bundle the intermediate** into the signature itself with `/ac`:
   `signtool sign /ac path\to\intermediate.cer ...`

---

## 8. CI signing (future)

The Compose Multiplatform plugin does not yet expose a Windows signing block
analogous to its macOS one. Until it does, signing must run as a separate
GitHub Actions step on a `windows-latest` runner, after `packageReleaseMsi`.
The signing certificate goes in as a GitHub Actions secret in PFX form (EV
tokens cannot be used in GitHub-hosted runners — those need a self-hosted
runner with the token physically attached).

A skeleton for when you're ready (do not enable until the cert + secrets are
provisioned):

```yaml
- name: Decode signing PFX
  shell: pwsh
  run: |
    [System.Convert]::FromBase64String("${{ secrets.WIN_SIGN_PFX_BASE64 }}") |
      Set-Content -AsByteStream -Path codesign.pfx

- name: Sign MSI
  shell: pwsh
  env:
    WIN_SIGN_PFX_PASSWORD: ${{ secrets.WIN_SIGN_PFX_PASSWORD }}
  run: |
    $msi = Get-ChildItem psychonautwiki-journal-desktop\build\compose\binaries\main-release\msi\*.msi |
           Select-Object -First 1
    & "$env:SIGNTOOL_PATH\signtool.exe" sign `
        /f codesign.pfx /p $env:WIN_SIGN_PFX_PASSWORD `
        /tr http://timestamp.digicert.com /td sha256 /fd sha256 `
        /d "PsychonautWiki Journal" /du "https://psychonautwiki.org/" `
        $msi.FullName

- name: Wipe PFX
  if: always()
  shell: pwsh
  run: Remove-Item -Force codesign.pfx
```

The `if: always()` on the wipe step is mandatory — a failure between decode
and wipe would otherwise leave the PFX on the runner's disk for the next job.
