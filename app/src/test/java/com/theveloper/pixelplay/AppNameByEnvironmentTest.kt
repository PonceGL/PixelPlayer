package com.theveloper.pixelplay

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The launcher cuts long names at the end, so the environment goes first: two installs of the app
 * (a test build and the real one) must be told apart by the first characters.
 */
class AppNameByEnvironmentTest {

    private val appNamePattern = Regex("""<string\s+name="app_name"[^>]*>([^<]*)</string>""")

    /**
     * Every `app_name` an environment declares, keyed by the file that declares it (with `/` as the separator,
     * whatever the system). Gradle runs unit tests from the module folder, but a runner started at the project
     * root is tolerated too.
     */
    private fun appNames(environment: String): Map<String, String> {
        val dir = File("src/$environment/res").takeIf { it.exists() } ?: File("app/src/$environment/res")
        return dir.walk()
            .filter { it.name == "strings.xml" }
            .mapNotNull { file ->
                appNamePattern.find(file.readText())?.let { file.invariantSeparatorsPath to it.groupValues[1] }
            }
            .toMap()
    }

    @Test
    fun `debug names are the app name with the DEV prefix`() {
        val names = appNames("debug")

        assertThat(names).isNotEmpty()
        names.forEach { (file, name) -> assertWithMessage(file).that(name).isEqualTo("DEV PixelPlayer") }
    }

    @Test
    fun `personal names are the app name with the DEV prefix`() {
        val names = appNames("personal")

        assertThat(names).isNotEmpty()
        names.forEach { (file, name) -> assertWithMessage(file).that(name).isEqualTo("DEV PixelPlayer") }
    }

    @Test
    fun `the main name carries no environment prefix`() {
        val names = appNames("main")

        val mainName = names.filterKeys { it.endsWith("src/main/res/values/strings.xml") }.values.firstOrNull()

        assertThat(mainName).isEqualTo("PixelPlayer")
    }

    @Test
    fun `no environment keeps the old suffix style`() {
        val all = listOf("debug", "personal", "main").flatMap { appNames(it).values }

        assertThat(all.filter { it.contains("[") }).isEmpty()
    }
}
