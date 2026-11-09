package com.ryanmorrisroe.lyriamp

import android.app.Application
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsConnectionGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsJsonRpcClient
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerDirectory
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerRemoteGateway
import com.ryanmorrisroe.lyriamp.data.persistence.AndroidKeystoreCredentialCipher
import com.ryanmorrisroe.lyriamp.data.persistence.DataStoreSavedServerRepository
import com.ryanmorrisroe.lyriamp.data.persistence.SavedServerRepository
import com.ryanmorrisroe.lyriamp.data.persistence.savedServerDataStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout

class LyriampApplication : Application() {
    val activePlayerStore = LmsActivePlayerStore()
    val savedServerRepository: SavedServerRepository by lazy {
        DataStoreSavedServerRepository(
            applicationContext.savedServerDataStore,
            AndroidKeystoreCredentialCipher()
        )
    }

    lateinit var connectionGateway: LmsConnectionGateway
        private set
    lateinit var playerDirectory: LmsPlayerDirectory
        private set
    lateinit var playerRemoteGateway: LmsPlayerRemoteGateway
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
    }
}