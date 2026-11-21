# Contributing

Work goes through pull requests to `main`. Small commits, one logical change each.

## Git hooks (once per clone)

The hooks run with [lefthook](https://lefthook.dev) and catch the usual slips before they reach a pull request. They are a convenience: the CI repeats what matters, so a skipped hook is not a way around it.

```sh
brew install lefthook gitleaks      # or any other way to get the two binaries
lefthook install
```

| When | What it checks |
|---|---|
| `pre-commit` | No trailing whitespace or conflict markers in what is staged (`git diff --cached --check`); no staged file over 1 MB unless listed in `scripts/hooks/large-files-allowlist.txt`; no secrets in the staged changes (`gitleaks`, rules in `.gitleaks.toml`) |
| `commit-msg` | First line is `type(scope): summary` (types: feat, fix, chore, docs, refactor, test, build, ci, perf, style, revert; at most 120 characters), and the message has no `Co-Authored-By` or `Claude-Session` trailer |
| `pre-push` | `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` (slow on purpose) |

To skip a hook once: `git commit --no-verify` or `git push --no-verify`. To test the hook scripts themselves: `scripts/hooks/test-hooks.sh`.

A secret that gitleaks flags by mistake (a test fixture, a public protocol constant) belongs in the allowlist of `.gitleaks.toml`, with a line saying why.

## Checks before opening a pull request

```sh
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

Unit tests use JUnit Jupiter (`org.junit.jupiter.api.Test`).
