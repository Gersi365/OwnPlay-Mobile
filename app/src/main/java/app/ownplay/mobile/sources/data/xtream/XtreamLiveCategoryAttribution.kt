package app.ownplay.mobile.sources.data.xtream

object XtreamLiveCategoryAttribution {
    fun normalizeProviderCategoryId(raw: String?): String? =
        raw?.trim()?.takeIf(String::isNotEmpty)

    fun needsRecovery(
        knownCategoryIds: Collection<String>,
        streamCategoryIds: Collection<String?>,
    ): Boolean {
        val known = knownCategoryIds.mapNotNull(::normalizeProviderCategoryId).toSet()
        if (known.isEmpty() || streamCategoryIds.isEmpty()) return false
        return streamCategoryIds.any { raw ->
            normalizeProviderCategoryId(raw)?.let(known::contains) != true
        }
    }

    fun recoveredCategoryByStreamId(
        categoryMemberships: Collection<Pair<String, Collection<String>>>,
    ): Map<String, String> {
        val memberships = linkedMapOf<String, MutableSet<String>>()
        categoryMemberships.forEach { (rawCategoryId, streamIds) ->
            val categoryId = normalizeProviderCategoryId(rawCategoryId) ?: return@forEach
            streamIds.asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { streamId ->
                    memberships.getOrPut(streamId) { linkedSetOf() }.add(categoryId)
                }
        }
        return memberships.mapNotNull { (streamId, categories) ->
            categories.singleOrNull()?.let { categoryId -> streamId to categoryId }
        }.toMap()
    }
}
