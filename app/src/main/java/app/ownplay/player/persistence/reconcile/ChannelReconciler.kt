package app.ownplay.player.persistence.reconcile

data class ExistingChannelIdentity(
    val channelId: String,
    val providerKey: String,
)

data class ReconciliationPlan(
    val matchedChannelIdsByProviderKey: Map<String, String>,
    val newProviderKeys: List<String>,
    val missingChannelIds: List<String>,
)

sealed interface ReconciliationResult {
    data class Success(val plan: ReconciliationPlan) : ReconciliationResult
    data class DuplicateExistingProviderKey(val providerKey: String) : ReconciliationResult
    data class DuplicateIncomingProviderKey(val providerKey: String) : ReconciliationResult
}

object ChannelReconciler {
    fun plan(
        existing: List<ExistingChannelIdentity>,
        incomingProviderKeys: List<String>,
    ): ReconciliationResult {
        val existingByKey = linkedMapOf<String, String>()
        existing.forEach { channel ->
            if (existingByKey.putIfAbsent(channel.providerKey, channel.channelId) != null) {
                return ReconciliationResult.DuplicateExistingProviderKey(channel.providerKey)
            }
        }

        val incomingSeen = linkedSetOf<String>()
        incomingProviderKeys.forEach { providerKey ->
            if (!incomingSeen.add(providerKey)) {
                return ReconciliationResult.DuplicateIncomingProviderKey(providerKey)
            }
        }

        val matched = linkedMapOf<String, String>()
        val newKeys = mutableListOf<String>()
        incomingProviderKeys.forEach { providerKey ->
            val channelId = existingByKey[providerKey]
            if (channelId == null) {
                newKeys += providerKey
            } else {
                matched[providerKey] = channelId
            }
        }

        val incomingSet = incomingSeen.toSet()
        val missing = existing
            .asSequence()
            .filter { it.providerKey !in incomingSet }
            .map { it.channelId }
            .toList()

        return ReconciliationResult.Success(
            ReconciliationPlan(
                matchedChannelIdsByProviderKey = matched,
                newProviderKeys = newKeys,
                missingChannelIds = missing,
            ),
        )
    }
}
