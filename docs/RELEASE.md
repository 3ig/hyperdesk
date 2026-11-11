# Releases and signing

## Release key

The release key is **not** in this repository.

- Keystore: `~/.config/hyperdesk/release.jks` (PKCS12, alias `release`, RSA 4096, valid 100 years)
- Passwords and path: `~/.gradle/gradle.properties` (`hyperdeskReleaseStoreFile`, `hyperdeskReleaseStorePassword`,
  `hyperdeskReleaseKeyAlias`, `hyperdeskReleaseKeyPassword`), file mode 600
- Certificate SHA-256: `6B:27:8F:5D:9C:49:04:BA:E6:42:99:0A:E3:0D:EC:85:6E:86:F1:F8:FB:A8:88:3A:33:76:A6:7A:F6:8B:AC:02`

**Back up the keystore file and its password together** (for example in a password manager). Without them,
installed copies can never be updated: Android only accepts updates signed with the same key.

If the properties are missing, builds still work but are signed with the debug key, with a warning.

## Builds and versions

There is one app ID, `dev.hyperdesk`, for every build. Two copies on one phone would both try to run the desktop
on the monitor, so debug builds do not get a separate ID. When the release key is configured locally, debug builds
are signed with it too, so a build made on a computer installs over a release and the next release installs over it.

Version names come from git tags; the version code is derived from the name:

| Build | Version name | Version code |
| --- | --- | --- |
| Release from tag `v0.1.0` | `0.1.0` | 10000 |
| CI build after that tag | `0.1.0-ci.N` | 10001–10098 |
| Local build after that tag | `0.1.0-dev` | 10099 |
| Next release `v0.1.1` | `0.1.1` | 10100 |

Before the first tag, local builds are `0.0.0-dev` (code 99).

## GitHub Actions

`.github/workflows/build.yml` builds a release APK on every push to `main` and every pull request, and publishes a
GitHub Release with the APK for tags named `v*`. Signing uses these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -i ~/.config/hyperdesk/release.jks` |
| `RELEASE_STORE_PASSWORD` | `hyperdeskReleaseStorePassword` |
| `RELEASE_KEY_ALIAS` | `release` |
| `RELEASE_KEY_PASSWORD` | `hyperdeskReleaseKeyPassword` |

To set them with the GitHub CLI without the values passing through your shell history:

```bash
base64 -i ~/.config/hyperdesk/release.jks | gh secret set RELEASE_KEYSTORE_BASE64 -R 3ig/hyperdesk
grep '^hyperdeskReleaseStorePassword=' ~/.gradle/gradle.properties | cut -d= -f2- | gh secret set RELEASE_STORE_PASSWORD -R 3ig/hyperdesk
gh secret set RELEASE_KEY_ALIAS -R 3ig/hyperdesk --body release
grep '^hyperdeskReleaseKeyPassword=' ~/.gradle/gradle.properties | cut -d= -f2- | gh secret set RELEASE_KEY_PASSWORD -R 3ig/hyperdesk
```

Without the keystore secret, branch builds fall back to the debug key and tagged builds fail on purpose.

## Cutting a release

1. Move the items under `## [Unreleased]` in `CHANGELOG.md` into a new `## [X.Y.Z] - YYYY-MM-DD` section, update the
   compare links at the bottom, and commit as `Release X.Y.Z`. Push `main`.
2. Tag and push: `git tag -a vX.Y.Z -m "HyperDesk X.Y.Z" && git push origin vX.Y.Z`.
3. Actions builds the signed APK and publishes **HyperDesk X.Y.Z** with that changelog section, install notes, the
   APK and its SHA-256. The job fails if the section is missing. Check that the run passed and the APK is attached.
