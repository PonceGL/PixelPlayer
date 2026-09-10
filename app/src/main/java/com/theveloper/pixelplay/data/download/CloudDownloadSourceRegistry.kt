package com.theveloper.pixelplay.data.download

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that maps a `SourceType` constant to its [CloudDownloadSource].
 * Mirrors [com.theveloper.pixelplay.data.download.storage.DownloadStorageRegistry]'s shape:
 * a Hilt `@IntoMap` multibinding (`@IntKey`, keyed by `SourceType` — not an enum) read
 * once here. Adding a second source later is one more `@Provides @IntoMap` entry; nothing
 * that calls [sourceFor] changes.
 */
@Singleton
class CloudDownloadSourceRegistry @Inject constructor(
    private val sources: Map<@JvmSuppressWildcards Int, @JvmSuppressWildcards CloudDownloadSource>,
) {
    /**
     * The source for [sourceType], or `null` if nothing is registered for it. `null`, not a
     * thrown exception: unlike [com.theveloper.pixelplay.data.download.storage.DownloadStorageRegistry.backendFor]
     * (a wiring bug if missing), an unregistered `SourceType` here is a real runtime fact — a
     * song from a source this build doesn't support downloading from yet.
     */
    fun sourceFor(sourceType: Int): CloudDownloadSource? = sources[sourceType]
}
