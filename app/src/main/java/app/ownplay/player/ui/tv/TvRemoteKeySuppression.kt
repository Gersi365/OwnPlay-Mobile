package app.ownplay.player.ui.tv

internal class TvRemoteKeySuppression {
    private val suppressedKeyCodes = mutableSetOf<Int>()

    fun suppress(keyCode: Int) {
        suppressedKeyCodes += keyCode
    }

    fun allow(keyCode: Int) {
        suppressedKeyCodes -= keyCode
    }

    fun consumeRelease(keyCode: Int): Boolean = suppressedKeyCodes.remove(keyCode)

    fun clear() {
        suppressedKeyCodes.clear()
    }
}
