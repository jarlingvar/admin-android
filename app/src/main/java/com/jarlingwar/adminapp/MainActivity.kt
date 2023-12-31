package com.jarlingwar.adminapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.jarlingwar.adminapp.navigation.Destinations
import com.jarlingwar.adminapp.navigation.NavSetup
import com.jarlingwar.adminapp.services.IntentType
import com.jarlingwar.adminapp.services.MonitoringService
import com.jarlingwar.adminapp.ui.theme.AdminAppTheme
import com.jarlingwar.adminapp.ui.view_models.MainViewModel
import com.jarlingwar.adminapp.utils.ListingFields
import com.jarlingwar.adminapp.utils.clear
import com.jarlingwar.adminapp.utils.observeAndAction
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private lateinit var navController: NavHostController
    private val mainViewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AdminAppTheme {
                navController = rememberNavController()
                NavSetup(navController, mainViewModel.isAuthRequired, mainViewModel.listingFromIntent)
            }
        }
        setupService()
        observeAndAction(mainViewModel.isAuthRequired) { if (it == false) setupService() }
    }

    override fun onPause() {
        super.onPause()
        mainViewModel.listingFromIntent.value = null
    }

    override fun onNewIntent(intent: Intent?) {
        if (!::navController.isInitialized) return
        super.onNewIntent(intent)
        if (intent != null) {
            when (intent.getStringExtra(MonitoringService.INTENT_TYPE)) {
                IntentType.NEW_REVIEW.name -> {
                    navController.navigate(Destinations.Reviews.route)
                }
                IntentType.NEW_LISTING.name -> {
                    val listingId = intent.getStringExtra(ListingFields.ID)
                    if (listingId != null) {
                        mainViewModel.getListing(listingId)
                    }
                }
            }
        }
        intent?.clear()
    }

    private fun setupService() {
        if (!isServiceRunning2<MonitoringService>() && mainViewModel.isAuthRequired.value == false) {
            val intent = Intent(this, MonitoringService::class.java)
            startForegroundService(intent)
        }
    }

    @Suppress("DEPRECATION") // Deprecated for third party Services.
    private inline fun <reified T> Context.isServiceRunning2() =
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == T::class.java.name }
}