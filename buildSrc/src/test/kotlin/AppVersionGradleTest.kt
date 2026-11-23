import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [appVersionCode] against real git repositories: it is the adapter between git, the properties and [AppVersion]. */
class AppVersionGradleTest {

    @TempDir
    lateinit var tmp: File

    private fun git(dir: File, vararg args: String) {
        val process = ProcessBuilder(
            listOf("git", "-c", "user.name=test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false") + args
        ).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.toList()} failed: $output" }
    }

    private fun repoWithCommits(count: Int): File {
        val dir = File(tmp, "repo").apply { mkdirs() }
        git(dir, "init", "-q")
        repeat(count) { git(dir, "commit", "-q", "--allow-empty", "-m", "commit $it") }
        return dir
    }

    private fun projectIn(dir: File, vararg properties: Pair<String, String>) =
        ProjectBuilder.builder().withProjectDir(dir).build().also { project ->
            properties.forEach { (key, value) -> project.extensions.extraProperties.set(key, value) }
        }

    @Test
    fun `the code is the commit count of the repository`() {
        val project = projectIn(repoWithCommits(3), "APP_VERSION_CODE_FALLBACK" to "9")

        assertThat(project.appVersionCode()).isEqualTo(3)
    }

    @Test
    fun `an explicit APP_VERSION_CODE wins over the count`() {
        val project = projectIn(repoWithCommits(3), "APP_VERSION_CODE" to "77", "APP_VERSION_CODE_FALLBACK" to "9")

        assertThat(project.appVersionCode()).isEqualTo(77)
    }

    @Test
    fun `a folder without git falls back to the fallback property`() {
        val dir = File(tmp, "plain").apply { mkdirs() }
        val project = projectIn(dir, "APP_VERSION_CODE_FALLBACK" to "9")

        assertThat(project.appVersionCode()).isEqualTo(9)
    }

    @Test
    fun `a shallow clone falls back instead of using its short history`() {
        val origin = repoWithCommits(3)
        val clone = File(tmp, "clone")
        git(tmp, "clone", "-q", "--depth", "1", "file://${origin.absoluteFile.invariantSeparatorsPath}", clone.path)
        val project = projectIn(clone, "APP_VERSION_CODE_FALLBACK" to "9")

        assertThat(project.appVersionCode()).isEqualTo(9)
    }
}
