package com.theveloper.pixelplay.data.sonos.di

import com.theveloper.pixelplay.data.sonos.net.AndroidLanNetworkProvider
import com.theveloper.pixelplay.data.sonos.net.HttpSonosTransport
import com.theveloper.pixelplay.data.sonos.net.LanNetworkProvider
import com.theveloper.pixelplay.data.sonos.net.MulticastSsdpSocket
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.net.SsdpDiscovery
import com.theveloper.pixelplay.data.sonos.session.SonosDiscovery
import com.theveloper.pixelplay.data.sonos.session.SpeakerDiscovery
import com.theveloper.pixelplay.di.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.OkHttpClient

/** Plain client for talking to speakers: no logging or cache interceptors of the app's internet clients. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class SonosHttpClient

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SonosBindingsModule {

    @Binds
    abstract fun bindLanNetworkProvider(impl: AndroidLanNetworkProvider): LanNetworkProvider
}

@Module
@InstallIn(SingletonComponent::class)
internal object SonosModule {

    @Provides
    @Singleton
    @SonosHttpClient
    fun provideSonosHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    fun provideSsdpDiscovery(
        network: LanNetworkProvider,
        sockets: MulticastSsdpSocket.Factory,
        @IoDispatcher io: CoroutineDispatcher,
    ): SsdpDiscovery = SsdpDiscovery(openSocket = sockets::open, network = network, io = io)

    @Provides
    fun provideSonosTransport(
        network: LanNetworkProvider,
        @SonosHttpClient client: OkHttpClient,
        @IoDispatcher io: CoroutineDispatcher,
    ): SonosTransport = HttpSonosTransport(network, client, io)

    @Provides
    fun provideSpeakerDiscovery(
        ssdp: SsdpDiscovery,
        transport: SonosTransport,
        @IoDispatcher io: CoroutineDispatcher,
    ): SpeakerDiscovery = SonosDiscovery(searchSpeakers = { ssdp.search() }, transport = transport, io = io)
}
