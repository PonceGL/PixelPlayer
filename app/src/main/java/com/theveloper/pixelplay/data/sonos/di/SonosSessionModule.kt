package com.theveloper.pixelplay.data.sonos.di

import android.content.Context
import android.os.SystemClock
import com.theveloper.pixelplay.data.sonos.net.AndroidSonosPowerLocks
import com.theveloper.pixelplay.data.sonos.net.HttpSonosSubscriber
import com.theveloper.pixelplay.data.sonos.net.LanNetworkProvider
import com.theveloper.pixelplay.data.sonos.net.SonosSubscriber
import com.theveloper.pixelplay.data.sonos.net.SonosTransport
import com.theveloper.pixelplay.data.sonos.server.ServiceSonosMediaServer
import com.theveloper.pixelplay.data.sonos.server.SonosEventRouter
import com.theveloper.pixelplay.data.sonos.server.SonosMediaAccess
import com.theveloper.pixelplay.data.sonos.session.SonosMediaServer
import com.theveloper.pixelplay.data.sonos.session.SonosPowerLocks
import com.theveloper.pixelplay.data.sonos.session.SonosSessionController
import com.theveloper.pixelplay.data.sonos.session.SonosSessionHolder
import com.theveloper.pixelplay.di.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SonosSessionBindingsModule {

    @Binds
    abstract fun bindMediaServer(impl: ServiceSonosMediaServer): SonosMediaServer
}

@Module
@InstallIn(SingletonComponent::class)
internal object SonosSessionModule {

    @Provides
    fun provideSonosSubscriber(
        network: LanNetworkProvider,
        @SonosHttpClient client: OkHttpClient,
        @IoDispatcher io: CoroutineDispatcher,
    ): SonosSubscriber = HttpSonosSubscriber(network, client, io)

    @Provides
    fun providePowerLocks(@ApplicationContext context: Context): SonosPowerLocks = AndroidSonosPowerLocks(context)
}

/** Builds the session controller of one service: its scope is the service's, so it ends with it. */
internal class SonosSessionControllerFactory @Inject constructor(
    private val transport: SonosTransport,
    private val subscriber: SonosSubscriber,
    private val router: SonosEventRouter,
    val mediaAccess: SonosMediaAccess,
    private val server: SonosMediaServer,
    private val powerLocks: SonosPowerLocks,
    private val holder: SonosSessionHolder,
) {
    fun create(scope: CoroutineScope): SonosSessionController =
        SonosSessionController(transport, subscriber, router, mediaAccess, server, powerLocks, holder, SystemClock::elapsedRealtime, scope)
}
