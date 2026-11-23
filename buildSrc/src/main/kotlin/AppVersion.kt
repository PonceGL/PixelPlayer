/**
 * Decides the version code of the app.
 *
 * Android only installs a build over another one when its version code is higher, so the code is the
 * number of commits in the history: it grows with every commit and nobody has to remember to raise it.
 * An explicit `-PAPP_VERSION_CODE` wins, and when git cannot say (no repository, or a shallow clone whose
 * count would be wrong and low) the code falls back to a fixed value and the build says so.
 */
object AppVersion {

    enum class Source { EXPLICIT, GIT, FALLBACK }

    data class Resolved(val code: Int, val source: Source)

    /**
     * The commit count from what `git rev-parse --is-shallow-repository` and `git rev-list --count HEAD`
     * printed, or null when it cannot be trusted: git said nothing, the clone is shallow, or the count is
     * not a positive number.
     */
    fun commitCount(isShallowOutput: String?, countOutput: String?): Int? {
        if (isShallowOutput?.trim() != "false") return null
        return countOutput?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * Picks the version code in this order: a positive [explicit] value, then [commitCount], then [fallback].
     *
     * [explicit] is trimmed and ignored unless it parses as a positive integer. [commitCount] is taken as it
     * comes and must already be the trusted count from [commitCount]. [fallback] is used as given.
     */
    fun resolve(explicit: String?, commitCount: Int?, fallback: Int): Resolved {
        explicit?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { return Resolved(it, Source.EXPLICIT) }
        commitCount?.let { return Resolved(it, Source.GIT) }
        return Resolved(fallback, Source.FALLBACK)
    }
}
