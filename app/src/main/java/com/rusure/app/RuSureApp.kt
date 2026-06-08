package com.rusure.app

import android.app.Application
import com.rusure.app.data.seed.DefaultTargetsSeeder
import com.rusure.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RuSureApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Siembra el catálogo curado la primera vez (idempotente).
        appScope.launch {
            DefaultTargetsSeeder.seedIfEmpty(container.appTargetConfigDao)
        }
    }
}
