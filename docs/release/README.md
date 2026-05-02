# Release documentation

Per-platform procedures for cutting a signed release. Read the cross-platform
flow in [`/RELEASE.md`](../../RELEASE.md) first — it covers checksums, the
detached PGP signature, and reproducibility verification that span all three
platforms.

| Platform | Doc | Output |
|----------|-----|--------|
| Windows  | [RELEASE-WINDOWS.md](RELEASE-WINDOWS.md) | signed `.msi` |
| macOS    | [RELEASE-MACOS.md](RELEASE-MACOS.md)     | signed + notarized + stapled `.dmg` |
| Linux    | [RELEASE-LINUX.md](RELEASE-LINUX.md)     | signed `.deb`, signed `.AppImage` |

Each doc follows the same eight-or-nine-section template:

0. Tooling, certificate / key, env vars
1. Build the unsigned artefact
2. Smoke-test before signing (so reputation isn't burned on a broken build)
3. Sign
4. Verify the signature
5. Verify the full chain (signature + platform-specific gates like notarization)
6. Hash and publish
7. Distribution-channel notes (where applicable)
8. Common failure modes
9. CI signing skeleton (where automatable)

The signing key for `SHA256SUMS.asc` is the same across all platforms. When
you publish that key (`pubkey.asc`), put it in this directory next to the
release docs so users have a single canonical place to fetch it from.
