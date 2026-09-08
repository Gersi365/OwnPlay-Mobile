package app.ownplay.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveRotationPresentationGateTest {
    @Test
    fun mobileShellDoesNotWireRotationDrivenLivePresentation() {
        val main = sourceText("src/main/java/app/ownplay/player/MainActivity.kt")
        val root = sourceText("src/main/java/app/ownplay/player/ui/OwnPlayRoot.kt")
        val target = sourceText("src/mobile/java/app/ownplay/player/ui/TargetOwnPlayApp.kt")
        val shell = sourceText("src/mobile/java/app/ownplay/player/ui/MobileOwnPlayApp.kt")

        assertFalse(main.contains("liveRotationFullscreenEnabled"))
        assertFalse(root.contains("rotationFullscreenEnabled"))
        assertFalse(root.contains("onLivePreviewActiveChanged"))
        assertFalse(target.contains("rotationFullscreenEnabled"))
        assertFalse(target.contains("onLivePreviewActiveChanged"))
        assertFalse(shell.contains("rotationFullscreenEnabled"))
        assertFalse(shell.contains("onLivePreviewActiveChanged"))
        assertFalse(shell.contains("shouldEnterFullscreenFromRotation"))
        assertFalse(shell.contains("shouldReturnToPreviewFromRotation"))
    }

    private fun sourceText(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
        )
        val source = candidates.firstOrNull(File::isFile)
            ?: error("Could not locate source file: $relativePath")
        return source.readText()
    }
}

/**
 * Test-only simulator retained by the surface-ownership harness. Production Mobile presentation
 * must not call this helper or react to configuration changes by switching Preview/Fullscreen.
 */
internal fun liveRotationFullscreenEnabled(
    isSmartphone: Boolean,
    inPictureInPicture: Boolean,
): Boolean = isSmartphone && !inPictureInPicture
