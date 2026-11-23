import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AppVersionTest {

    @Test
    fun `commitCount reads a plain number`() {
        assertThat(AppVersion.commitCount("false\n", "1234\n")).isEqualTo(1234)
    }

    @Test
    fun `commitCount is null for a shallow clone`() {
        assertThat(AppVersion.commitCount("true", "5")).isNull()
    }

    @Test
    fun `commitCount is null when git printed nothing or text`() {
        assertThat(AppVersion.commitCount(null, null)).isNull()
        assertThat(AppVersion.commitCount("false", "")).isNull()
        assertThat(AppVersion.commitCount("false", "fatal: not a git repository")).isNull()
    }

    @Test
    fun `commitCount is null when git could not say if it is shallow`() {
        assertThat(AppVersion.commitCount("", "1234")).isNull()
        assertThat(AppVersion.commitCount(null, "1234")).isNull()
    }

    @Test
    fun `commitCount is null for zero or negative`() {
        assertThat(AppVersion.commitCount("false", "0")).isNull()
        assertThat(AppVersion.commitCount("false", "-4")).isNull()
    }

    @Test
    fun `resolve prefers the explicit value`() {
        assertThat(AppVersion.resolve("77", 1234, 9))
            .isEqualTo(AppVersion.Resolved(77, AppVersion.Source.EXPLICIT))
    }

    @Test
    fun `resolve uses git when there is no explicit value`() {
        assertThat(AppVersion.resolve(null, 1234, 9))
            .isEqualTo(AppVersion.Resolved(1234, AppVersion.Source.GIT))
    }

    @Test
    fun `resolve falls back and says so`() {
        assertThat(AppVersion.resolve(null, null, 9))
            .isEqualTo(AppVersion.Resolved(9, AppVersion.Source.FALLBACK))
    }

    @Test
    fun `resolve ignores an explicit value that is not a positive number`() {
        val expected = AppVersion.Resolved(1234, AppVersion.Source.GIT)

        assertThat(AppVersion.resolve("abc", 1234, 9)).isEqualTo(expected)
        assertThat(AppVersion.resolve("-3", 1234, 9)).isEqualTo(expected)
        assertThat(AppVersion.resolve("0", 1234, 9)).isEqualTo(expected)
        assertThat(AppVersion.resolve("", 1234, 9)).isEqualTo(expected)
    }
}
