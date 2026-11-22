package com.ryanmorrisroe.lyriamp

import android.app.Application
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsConnectionGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsJsonRpcClient
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerDirectory
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerRemoteGateway
import com.ryanmorrisroe.lyriamp.data.persistence.AndroidKeystoreCredentialCipher
import com.ryanmorrisroe.lyriamp.data.persistence.DataStoreSavedServerRepository
import com.ryanmorrisroe.lyriamp.data.persistence.DataStoreLastSessionRepository
import com.ryanmorrisroe.lyriamp.data.persistence.DataStoreDisplayPreferencesRepository
import com.ryanmorrisroe.lyriamp.data.persistence.DisplayPreferencesRepository
import com.ryanmorrisroe.lyriamp.data.persistence.displayPreferencesDataStore
import com.ryanmorrisroe.lyriamp.data.persistence.LastSessionRepository
import com.ryanmorrisroe.lyriamp.data.persistence.SavedServerRepository
import com.ryanmorrisroe.lyriamp.data.persistence.savedServerDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout

class LyriampApplication : Application() {
    val activePlayerStore = LmsActivePlayerStore()
    val artworkImageLoader: ImageLoader by lazy {
        ImageLoader.Builder(applicationContext)
            .memoryCache {
                MemoryCache.Builder(applicationContext)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("artwork"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .build()
    }
    val savedServerRepository: SavedServerRepository by lazy {
        DataStoreSavedServerRepository(
            applicationContext.savedServerDataStore,
            AndroidKeystoreCredentialCipher()
        )
    }
    val lastSessionRepository: LastSessionRepository by lazy {
        DataStoreLastSessionRepository(applicationContext.savedServerDataStore)
    }
    val displayPreferencesRepository: DisplayPreferencesRepository by lazy {
        DataStoreDisplayPreferencesRepository(applicationContext.displayPreferencesDataStore)
    }

    lateinit var connectionGateway: LmsConnectionGateway
        private set
    lateinit var playerDirectory: LmsPlayerDirectory
        private set
    lateinit var playerRemoteGateway: LmsPlayerRemoteGateway
        private set
    lateinit var libraryGateway: LmsLibraryGateway
        private set

    override fun onCreate() {
        super.onCreate()
        val httpClient = HttpClient(Android) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
        }
        val lmsJsonRpcClient = LmsJsonRpcClient(httpClient)
        connectionGateway = lmsJsonRpcClient
        playerDirectory = lmsJsonRpcClient
        playerRemoteGateway = lmsJsonRpcClient
        libraryGateway = lmsJsonRpcClient
    }
}