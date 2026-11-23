import org.gradle.api.Project

/**
 * The version code of this build, shared by app and wear so the two never drift apart.
 * The decision itself lives in [AppVersion]; this only asks git and reports where the number came from.
 */
fun Project.appVersionCode(): Int {
    fun git(vararg args: String): String? = runCatching {
        providers.exec {
            commandLine("git", *args)
            workingDir = rootDir
            isIgnoreExitValue = true
        }.standardOutput.asText.get()
    }.getOrNull()

    val fallback = (findProperty("APP_VERSION_CODE_FALLBACK") as? String)?.toIntOrNull() ?: 1
    val count = AppVersion.commitCount(
        isShallowOutput = git("rev-parse", "--is-shallow-repository"),
        countOutput = git("rev-list", "--count", "HEAD"),
    )
    val resolved = AppVersion.resolve(findProperty("APP_VERSION_CODE") as? String, count, fallback)
    if (resolved.source == AppVersion.Source.FALLBACK) {
        logger.warn(
            "WARNING: $path has no usable git history (no repository or a shallow clone), so its version " +
                "code is the fallback ${resolved.code}. It will NOT install over a build with a higher code."
        )
    }
    return resolved.code
}
