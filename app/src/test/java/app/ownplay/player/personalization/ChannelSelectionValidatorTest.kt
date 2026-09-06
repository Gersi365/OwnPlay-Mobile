package app.ownplay.player.personalization

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelSelectionValidatorTest {
    @Test
    fun successReturnsSelectionInResolvedSourceOrder() {
        val result = ChannelSelectionValidator.validate(
            requestedChannelIds = setOf("four", "two"),
            availableChannelIdsInOrder = listOf("one", "two", "three", "four"),
        )

        assertEquals(
            ChannelSelectionValidationResult.Success(
                channelIds = listOf("two", "four"),
            ),
            result,
        )
    }

    @Test
    fun emptySelectionFailsExplicitly() {
        val result = ChannelSelectionValidator.validate(
            requestedChannelIds = emptySet(),
            availableChannelIdsInOrder = listOf("one"),
        )

        assertEquals(
            ChannelSelectionValidationResult.Failure(
                reason = ChannelSelectionFailureReason.EMPTY_SELECTION,
            ),
            result,
        )
    }

    @Test
    fun blankChannelIdFailsExplicitly() {
        val result = ChannelSelectionValidator.validate(
            requestedChannelIds = setOf("one", ""),
            availableChannelIdsInOrder = listOf("one"),
        )

        assertEquals(
            ChannelSelectionValidationResult.Failure(
                reason = ChannelSelectionFailureReason.EMPTY_CHANNEL_ID,
            ),
            result,
        )
    }

    @Test
    fun missingChannelFailsDeterministically() {
        val result = ChannelSelectionValidator.validate(
            requestedChannelIds = setOf("z-missing", "a-missing", "one"),
            availableChannelIdsInOrder = listOf("one", "two"),
        )

        assertEquals(
            ChannelSelectionValidationResult.Failure(
                reason = ChannelSelectionFailureReason.CHANNEL_NOT_FOUND,
                channelId = "a-missing",
            ),
            result,
        )
    }
}
