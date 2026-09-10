package com.theveloper.pixelplay.data.download.storage

import com.theveloper.pixelplay.data.download.model.DownloadStorageBackendId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that knows which [DownloadStorageBackend] handles which
 * [DownloadStorageBackendId]. The download engine depends on this, never directly on
 * [AppPrivateDownloadStorage] — adding a SAF backend later means registering a second
 * [DownloadStorageBackend] into the Hilt multibinding set this reads from; nothing that
 * already calls [backendFor] changes.
 */
@Singleton
class DownloadStorageRegistry @Inject constructor(
    backends: Set<@JvmSuppressWildcards DownloadStorageBackend>,
) {
    private val byId: Map<DownloadStorageBackendId, DownloadStorageBackend> =
        backends.associateBy { it.id }

    /**
     * The backend for [id]. Throws [IllegalStateException] — not a [Result] — because an
     * unregistered backend id is a wiring bug (a Hilt module missing an `@IntoSet` binding),
     * not a runtime condition any caller could sensibly recover from.
     */
    fun backendFor(id: DownloadStorageBackendId): DownloadStorageBackend =
        byId[id] ?: error("No DownloadStorageBackend registered for $id")
}
