package com.theveloper.pixelplay.data.sonos.server

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.inject.Inject
import timber.log.Timber

/**
 * Keeps the token and port in encrypted preferences. When they cannot be created (a broken keystore)
 * nothing is persisted: the token then lives only in memory, which loses the queue already on a
 * speaker after a restart but never leaves a secret readable on disk.
 */
internal class EncryptedSonosServerIdentityStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SonosServerIdentityStore {

    @Volatile
    private var unusable = false
    private val prefs: SharedPreferences? by lazy { createPreferences() }

    override fun load(): StoredIdentity? {
        val preferences = prefs ?: return null
        return try {
            StoredIdentity(
                token = preferences.getString(KEY_TOKEN, null),
                port = preferences.getInt(KEY_PORT, NO_PORT).takeIf { it != NO_PORT },
            )
        } catch (e: RuntimeException) {
            // The library surfaces keystore and decryption problems as several unrelated runtime
            // exceptions (SecurityException when a restored file cannot be decrypted, among others).
            giveUp(e)
            null
        }
    }

    override fun save(token: String, port: Int?): Boolean {
        val preferences = prefs?.takeUnless { unusable } ?: return false
        return try {
            preferences.edit {
                putString(KEY_TOKEN, token)
                if (port == null) remove(KEY_PORT) else putInt(KEY_PORT, port)
            }
            true
        } catch (e: RuntimeException) {
            giveUp(e)
            false
        }
    }

    private fun giveUp(cause: RuntimeException) {
        unusable = true
        Timber.w(cause, "Encrypted storage failed; the Sonos server token stays in memory for this run")
    }

    private fun createPreferences(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: GeneralSecurityException) {
        Timber.w(e, "Encrypted storage unavailable; the Sonos server token will not be persisted")
        null
    } catch (e: IOException) {
        Timber.w(e, "Encrypted storage unavailable; the Sonos server token will not be persisted")
        null
    } catch (e: SecurityException) {
        Timber.w(e, "Encrypted storage unavailable; the Sonos server token will not be persisted")
        null
    } catch (e: ProviderException) {
        Timber.w(e, "Encrypted storage unavailable; the Sonos server token will not be persisted")
        null
    }

    private companion object {
        const val FILE_NAME = "sonos_server_identity"
        const val KEY_TOKEN = "token"
        const val KEY_PORT = "port"
        const val NO_PORT = -1
    }
}
