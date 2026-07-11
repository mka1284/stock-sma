package com.stocksma

import android.Manifest
import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocksma.ui.AppRoot
import com.stocksma.ui.AppViewModel
import com.stocksma.ui.StockSmaTheme
import com.stocksma.work.FetchWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FetchWorker.ensureChannel(this)
    }
}

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            val settings by vm.settings.collectAsStateWithLifecycle()

            // (Re-)schedule the periodic background fetch whenever the frequency changes.
            LaunchedEffect(settings.fetchesPerDay) {
                FetchWorker.schedule(this@MainActivity, settings.fetchesPerDay)
            }

            // Android 13+ runtime permission for notifications.
            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            StockSmaTheme(settings.themeMode) {
                val sizeClass = calculateWindowSizeClass(this)
                AppRoot(vm, sizeClass.widthSizeClass)
            }
        }
    }
}
