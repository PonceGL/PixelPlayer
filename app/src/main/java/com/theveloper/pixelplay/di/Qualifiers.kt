package com.theveloper.pixelplay.di

import javax.inject.Qualifier

/**
 * Qualifier for Deezer Retrofit instance.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DeezerRetrofit

/**
 * Qualifier for Fast OkHttpClient (Short timeouts).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FastOkHttpClient

/**
 * Qualifier for Gson instance configured for backup serialization.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackupGson

/**
 * Qualifier for application-lifetime coroutine scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

/**
 * Qualifier for the OkHttpClient dedicated to cloud downloads (F1.2). Built from scratch,
 * never derived from another client's `newBuilder()`: that would silently inherit whatever
 * interceptors the source client has, logging included (`AND-SEC-01`, C4).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadOkHttpClient

/**
 * Qualifier for `Dispatchers.IO` (`AND-CONC-03`). `F1.6b`'s `HttpFileDownloader` is this
 * project's first consumer: earlier cloud-downloads code (F1.1a, F1.4a) kept predicting a
 * consumer and finding none, because every blocking call up to that point already lived
 * inside pre-existing, main-safe `suspend` functions with their own established (uninjected)
 * `Dispatchers.IO` convention (`JellyfinApiService`) that a single new file didn't justify
 * refactoring (`GEN-DES-08`/D-28). `HttpFileDownloader` has no such precedent to respect: it
 * is entirely new code, so `AND-CONC-03`'s "inject the dispatcher" applies without a
 * pre-existing convention pulling the other way.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
