# Contributing

Work goes through pull requests to `main`. Small commits, one logical change each.

## Git hooks (once per clone)

The hooks run with [lefthook](https://lefthook.dev) and catch the usual slips before they reach a pull request. They are a convenience: the CI repeats what matters, so a skipped hook is not a way around it.

```sh
brew install lefthook gitleaks ktlint      # or any other way to get the binaries
lefthook install
```

| When | What it checks |
|---|---|
| `pre-commit` | No trailing whitespace or conflict markers in what is staged (`git diff --cached --check`); no staged file over 1 MB unless listed in `scripts/hooks/large-files-allowlist.txt`; no secrets in the staged changes (`gitleaks`, rules in `.gitleaks.toml`) |
| `pre-commit` (format) | The Kotlin files being committed follow `.editorconfig` (`ktlint`). To fix them: `ktlint -F <file>` |
| `commit-msg` | First line is `type(scope): summary` (types: feat, fix, chore, docs, refactor, test, build, ci, perf, style, revert; at most 120 characters), and the message has no `Co-Authored-By` or `Claude-Session` trailer |
| `pre-push` | `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` (slow on purpose) |

To skip a hook once: `git commit --no-verify` or `git push --no-verify`. To test the hook scripts themselves: `scripts/hooks/test-hooks.sh`.

A secret that gitleaks flags by mistake (a test fixture, a public protocol constant) belongs in the allowlist of `.gitleaks.toml`, with a line saying why.

## Format

`.editorconfig` holds the rules (ktlint, `intellij_idea` style, with the rules the older code does not follow turned off). The code that predates them is not held to them **until a change touches it**: the CI (job `format`) and `scripts/format-check.sh [base]` look only at the Kotlin files that differ from the base (default `origin/main`). Touch a file and it has to be clean; `ktlint -F <file>` fixes most of it. The CI installs a pinned ktlint with `scripts/ci/install-ktlint.sh`; update the version and its checksum together. Tests of the script: `scripts/test-format-check.sh`.

## Signing

An install over a previous build only works with the **same signing key**. Debug builds use the key in `debug.keystore` (password `android`, alias `androiddebugkey`): it is public on purpose, so any machine or contributor signs the same and debug builds install over each other. Its certificate SHA-256 is `0b4b77b5ed47520142555589d70cfc0a9b213cee290c83ab0c3ccc75eafb4337`, and the CI checks it. Never use it for release: anyone could sign an APK that installs over yours. Release builds use the maintainer's own key, kept outside the repo.

## Checks before opening a pull request

```sh
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Unit tests use JUnit Jupiter (`org.junit.jupiter.api.Test`).

## Versions and installing a build

**Names.** The launcher cuts long names at the end, so the environment comes first: debug builds show as `DEV PixelPlayer`, release as `PixelPlayer`. Both can be installed side by side (debug has the `.debug` application id suffix).

**Version code (automatic).** Android installs a build over another one only if its `versionCode` is higher. It is the number of commits in the history (`git rev-list --count HEAD`), computed by `buildSrc/.../AppVersion.kt` for `app` and `wear`, so it grows with every commit and nobody has to remember to raise it. Things to know:

- A shallow clone (`git clone --depth`) cannot count: the build falls back to `APP_VERSION_CODE_FALLBACK` from `gradle.properties` and prints a warning. Such a build will not install over a real one. The release workflows fetch the full history and fail if the code of the APK is lower than the commit count.
- The code never goes down: building an older branch gives a lower code than the one already installed, and Android refuses it.
- `-PAPP_VERSION_CODE=n` overrides it when a number has to be chosen by hand.

**Version name (by hand).** `APP_VERSION_NAME` in `gradle.properties` is what people read (`0.7.5-beta`). Raise it in a commit of its own (`chore(release): version X.Y.Z`) when there is a version worth naming.

**Making and installing a build.**

```sh
./gradlew :app:assembleDebug                      # app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/<apk>  # -r: install over the previous one
# the code inside an APK:
$ANDROID_HOME/build-tools/<version>/aapt2 dump badging <apk> | grep versionCode
```

An install over a previous build also needs the **same signing key**. Until the shared debug key and the release key are set up in CI, a build made on another machine, or by the CI, may be refused: uninstall the old one once.
