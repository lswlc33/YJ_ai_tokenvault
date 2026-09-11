package com.lc33.tokenvault.ui.shell

import com.lc33.tokenvault.domain.model.Group
import com.lc33.tokenvault.domain.model.Provider
import com.lc33.tokenvault.screens.model.ProviderDraft

fun Provider.toDraft(groupIndex: Int): ProviderDraft = ProviderDraft(
    id = id,
    name = name,
    note = note.orEmpty(),
    website = websiteUrl.orEmpty(),
    groupIndex = groupIndex,
    colorIndex = color ?: 0,
    pinned = pinned,
)

fun ProviderDraft.toProvider(existing: Provider?, groups: List<Group>): Provider {
    val base = existing ?: Provider(name = name)
    return base.copy(
        id = existing?.id ?: 0L,
        name = name,
        note = note.ifBlank { null },
        websiteUrl = website.ifBlank { null },
        groupId = if (groupIndex == 0) {
            null
        } else {
            groups.getOrNull(groupIndex - 1)?.id ?: existing?.groupId
        },
        color = colorIndex,
        pinned = pinned,
    )
}
