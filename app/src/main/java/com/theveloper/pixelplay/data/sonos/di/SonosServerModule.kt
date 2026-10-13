package com.theveloper.pixelplay.data.sonos.di

import com.theveloper.pixelplay.data.sonos.server.EncryptedSonosServerIdentityStore
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.server.SonosServerIdentityProvider
import com.theveloper.pixelplay.data.sonos.server.SonosServerIdentityStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.security.SecureRandom
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SonosServerBindingsModule {

    @Binds
    abstract fun bindIdentityStore(impl: EncryptedSonosServerIdentityStore): SonosServerIdentityStore
}

@Module
@InstallIn(SingletonComponent::class)
internal object SonosServerModule {

    @Provides
    @Singleton
    fun provideIdentityProvider(store: SonosServerIdentityStore): SonosServerIdentityProvider {
        val random = SecureRandom()
        return SonosServerIdentityProvider(store) { ByteArray(TOKEN_BYTES).also(random::nextBytes) }
    }

    @Provides
    @Singleton
    fun provideMediaAccess(identity: SonosServerIdentityProvider): SonosMediaAccess =
        SonosMediaAccess { identity.current() }

    private const val TOKEN_BYTES = 16
}
