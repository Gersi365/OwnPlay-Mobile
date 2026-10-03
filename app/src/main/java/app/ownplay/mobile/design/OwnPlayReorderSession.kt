package app.ownplay.mobile.design

enum class OwnPlayReorderPhase {
    FIXED,
    MOVING,
    PENDING,
    SAVED,
}

sealed interface OwnPlayReorderSelectorAction<T> {
    data class Updated<T>(val session: OwnPlayReorderSession<T>) : OwnPlayReorderSelectorAction<T>

    data class CommitRequested<T>(
        val session: OwnPlayReorderSession<T>,
        val orderedIds: List<T>,
    ) : OwnPlayReorderSelectorAction<T>
}

data class OwnPlayReorderSession<T>(
    val committedIds: List<T>,
    val workingIds: List<T> = committedIds,
    val activeId: T? = null,
    val phase: OwnPlayReorderPhase = OwnPlayReorderPhase.FIXED,
) {
    val isDirty: Boolean
        get() = workingIds != committedIds

    fun canCommitTo(currentIds: List<T>): Boolean =
        workingIds.size == currentIds.size &&
            workingIds.toSet().size == workingIds.size &&
            currentIds.toSet().size == currentIds.size &&
            workingIds.toSet() == currentIds.toSet()

    fun onSelectorTap(id: T): OwnPlayReorderSelectorAction<T> {
        if (id !in workingIds) return OwnPlayReorderSelectorAction.Updated(this)
        if (isDirty && activeId != id) return OwnPlayReorderSelectorAction.Updated(this)
        if (activeId != id) {
            return OwnPlayReorderSelectorAction.Updated(
                copy(activeId = id, phase = OwnPlayReorderPhase.MOVING),
            )
        }
        if (isDirty) {
            return OwnPlayReorderSelectorAction.CommitRequested(
                session = copy(phase = OwnPlayReorderPhase.PENDING),
                orderedIds = workingIds,
            )
        }
        return OwnPlayReorderSelectorAction.Updated(
            copy(activeId = null, phase = OwnPlayReorderPhase.FIXED),
        )
    }

    fun moveActiveTo(targetIndex: Int): OwnPlayReorderSession<T> {
        val active = activeId ?: return this
        val fromIndex = workingIds.indexOf(active)
        if (fromIndex == -1 || targetIndex !in workingIds.indices || targetIndex == fromIndex) return this
        val moved = workingIds.toMutableList().apply {
            add(targetIndex, removeAt(fromIndex))
        }
        return copy(
            workingIds = moved,
            phase = if (moved == committedIds) OwnPlayReorderPhase.MOVING else OwnPlayReorderPhase.PENDING,
        )
    }

    fun finishDrag(): OwnPlayReorderSession<T> = copy(
        phase = if (isDirty) OwnPlayReorderPhase.PENDING else OwnPlayReorderPhase.MOVING,
    )

    fun markSaved(): OwnPlayReorderSession<T> = copy(
        committedIds = workingIds,
        activeId = null,
        phase = OwnPlayReorderPhase.SAVED,
    )

    fun settleSaved(): OwnPlayReorderSession<T> = if (phase == OwnPlayReorderPhase.SAVED) {
        copy(phase = OwnPlayReorderPhase.FIXED)
    } else {
        this
    }

    fun discard(): OwnPlayReorderSession<T> = copy(
        workingIds = committedIds,
        activeId = null,
        phase = OwnPlayReorderPhase.FIXED,
    )

    fun syncCommitted(ids: List<T>): OwnPlayReorderSession<T> = if (isDirty) {
        this
    } else if (ids == committedIds && workingIds == ids) {
        this
    } else {
        OwnPlayReorderSession(committedIds = ids)
    }
}

fun <T> mergeReorderedSubset(
    fullOrder: List<T>,
    scopedIds: Collection<T>,
    reorderedScopedIds: List<T>,
): List<T> {
    val scopedSet = scopedIds.toSet()
    if (scopedSet.isEmpty()) return fullOrder
    if (reorderedScopedIds.size != scopedSet.size || reorderedScopedIds.toSet() != scopedSet) return fullOrder
    val replacement = reorderedScopedIds.iterator()
    return fullOrder.map { id ->
        if (id in scopedSet && replacement.hasNext()) replacement.next() else id
    }
}
