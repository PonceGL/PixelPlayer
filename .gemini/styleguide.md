# Review guide for PixelPlayer (Kotlin, Jetpack Compose, Media3)

PixelPlayer is a local music player for Android, written in Kotlin with Jetpack Compose and Media3.

## How to review

- Review only what the pull request changes. This code base has old code that breaks several of the rules
  below; do not report those lines unless the change touches or relies on them.
- Name the rule you apply and say why it matters here, not only that it is a rule.
- Prefer a few findings that are certain over many that are doubtful. If you are not sure, say so.
- Do not comment on formatting, import order or trailing spaces: ktlint checks them in CI.
- Do not comment on translations (`values-*/strings*.xml`) beyond a missing or misspelled key.

## Must (report as high severity)

### Kotlin
- No `!!` in production code. Use a safe call, `requireNotNull` with a message, or model the absence.
- No `lateinit` except for framework injection (Hilt, Android lifecycle).
- Model states and errors with a `sealed interface`, and models of state with `data class`.
- Expose read-only collections (`List`, `Set`, `Map`), never mutable ones, from public API.

### Coroutines and Flow
- No `GlobalScope`. Every coroutine has a scope with an owner.
- No `runBlocking` in production code.
- `suspend` functions are main-safe: they switch to the right dispatcher themselves. Dispatchers are injected,
  never hard-coded with `Dispatchers.IO` inside the class.
- Never swallow `CancellationException`: a generic `catch (e: Exception)` in a coroutine must rethrow it.
- Expose `StateFlow`, never the `MutableStateFlow`.
- Collect flows with the lifecycle in mind (`collectAsStateWithLifecycle`, `repeatOnLifecycle`).
- Search and filter work cancels the stale request when a new one arrives.

### Architecture
- A ViewModel has no `Context`, no `R.string` and no UI framework types.
- No business logic in an `Activity` or a `Fragment`.
- State that must survive process death goes in `SavedStateHandle`.
- Navigation arguments are identifiers, not whole objects.

### Compose
- Composables are stateless with state hoisted. The signature takes a `modifier: Modifier = Modifier`
  as the first optional parameter, and applies the received modifier to the root.
- No side effects in the body of a composable. Use `LaunchedEffect`, `DisposableEffect` or `SideEffect`, with
  correct keys. Use `rememberUpdatedState` for values that change without restarting the effect.
- Lazy lists have a stable `key` (and `contentType` when the items differ).
- Never pass a ViewModel to a child composable: pass state and lambdas.
- Business state lives in the ViewModel; `remember` is only for UI state, `rememberSaveable` when it must
  survive a configuration change.
- Read frequently changing state (animation, scroll) as late as possible, in a lambda or a graphics layer.
- Images and icons have a correct `contentDescription` (`null` only for decorative ones).

### Performance, memory and security
- No blocking work on the main thread.
- No `Activity` `Context` in a static field or a singleton. Unregister listeners, observers, receivers and timers.
- Decode bitmaps at the size they are shown.
- No secrets or tokens in `SharedPreferences`, unencrypted `DataStore` or `BuildConfig`.
- New components in `AndroidManifest.xml` are `android:exported="false"` unless there is a reason.
- `WebView` has JavaScript disabled unless the screen needs it.

## Tests and documentation (report as medium severity)

- New or changed behaviour comes with a unit test. Fixing a bug comes with a test that fails without the fix.
  Say which new function or branch has no test.
- Tests check behaviour, not the implementation, and use real code over mocks where it is practical.
- New public classes and functions have a short KDoc that says what they do and any non-obvious contract.
- A change does not mix unrelated work: say so if one pull request does two things.
