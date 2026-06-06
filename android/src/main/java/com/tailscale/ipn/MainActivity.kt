// Copyright (c) MagicStreamer
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE
import android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.AndroidTVUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.ui.viewModel.AppViewModel
import com.tailscale.ipn.ui.viewModel.MainViewModel
import com.tailscale.ipn.ui.viewModel.MainViewModelFactory
import com.tailscale.ipn.ui.viewModel.PermissionsViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

  private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
  private lateinit var appViewModel: AppViewModel
  private lateinit var viewModel: MainViewModel

  val permissionsViewModel: PermissionsViewModel by viewModels()

  companion object {
    private const val TAG = "MainActivity"
  }

  private fun Context.isLandscapeCapable(): Boolean {
    return (resources.configuration.screenLayout and SCREENLAYOUT_SIZE_MASK) >=
        SCREENLAYOUT_SIZE_LARGE
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  @SuppressLint("SourceLockedOrientationActivity")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    App.get()
    appViewModel = (application as App).getAppScopedViewModel()
    viewModel =
        ViewModelProvider(this, MainViewModelFactory(appViewModel)).get(MainViewModel::class.java)

    val rm = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    MDMSettings.update(App.get(), rm)

    if (!isLandscapeCapable()) {
      requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    installSplashScreen()

    vpnPermissionLauncher =
        registerForActivityResult(VpnPermissionContract()) { granted ->
          if (granted) {
            appViewModel.setVpnPrepared(true)
            App.get().startVPN()
          } else {
            if (isAnotherVpnActive(this)) {
              AlertDialog.Builder(this)
                  .setTitle("VPN Conflict")
                  .setMessage(
                      "Another VPN is active. Please disconnect it first, then try again.")
                  .setPositiveButton("Open VPN Settings") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
                  }
                  .setNegativeButton("Cancel", null)
                  .show()
            } else {
              AlertDialog.Builder(this)
                  .setTitle("VPN Permission Needed")
                  .setMessage(
                      "MagicStreamer needs VPN permission to route traffic through India.")
                  .setPositiveButton("Try Again") { _, _ ->
                    viewModel.showVPNPermissionLauncherIfUnauthorized()
                  }
                  .setNegativeButton("Cancel", null)
                  .show()
            }
          }
        }

    viewModel.setVpnPermissionLauncher(vpnPermissionLauncher)

    setContent {
      MagicStreamerScreen(
          viewModel = viewModel,
          onConnect = { connect() },
          onDisconnect = { disconnect() })
    }
  }

  private fun connect() {
    val authKey = MDMSettings.authKey.flow.value.value
    if (authKey != null) {
      viewModel.loginWithAuthKey(authKey) { result ->
        result
            .onSuccess {
              TSLog.d(TAG, "Auth key login success — requesting VPN permission")
              viewModel.showVPNPermissionLauncherIfUnauthorized()
              viewModel.selectIndiaExitNode()
            }
            .onFailure { e ->
              TSLog.e(TAG, "Auth key login failed: ${e.message}")
              // Try anyway — might already be logged in
              viewModel.showVPNPermissionLauncherIfUnauthorized()
              viewModel.selectIndiaExitNode()
            }
      }
    } else {
      viewModel.showVPNPermissionLauncherIfUnauthorized()
      viewModel.selectIndiaExitNode()
    }
  }

  private fun disconnect() {
    viewModel.stopVPN()
  }

  override fun onResume() {
    super.onResume()
    val rm = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    lifecycleScope.launch { MDMSettings.update(App.get(), rm) }
  }

  override fun onStop() {
    super.onStop()
    val rm = getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    lifecycleScope.launch { MDMSettings.update(App.get(), rm) }
  }

  fun isAnotherVpnActive(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
  }
}

@Composable
fun MagicStreamerScreen(
    viewModel: MainViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
  val state by Notifier.state.collectAsState()

  val statusText =
      when (state) {
        Ipn.State.Running -> "Connected via India"
        Ipn.State.Starting -> "Connecting..."
        Ipn.State.Stopped -> "Disconnected"
        Ipn.State.NeedsLogin -> "Not logged in"
        Ipn.State.NeedsMachineAuth -> "Awaiting approval"
        else -> "Checking..."
      }

  val statusColor =
      when (state) {
        Ipn.State.Running -> Color(0xFF28A745)
        Ipn.State.Starting -> Color(0xFFFFC107)
        Ipn.State.NeedsMachineAuth -> Color(0xFFFFC107)
        else -> Color(0xFFDC3545)
      }

  Box(
      modifier = Modifier.fillMaxSize().background(Color(0xFF121212)),
      contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)) {

          // Logo / Title
          Text(
              text = "MagicStreamer",
              fontSize = 36.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White,
              textAlign = TextAlign.Center)

          Spacer(modifier = Modifier.height(8.dp))

          Text(
              text = "Watch Indian content from anywhere",
              fontSize = 14.sp,
              color = Color(0xFF888888),
              textAlign = TextAlign.Center)

          Spacer(modifier = Modifier.height(40.dp))

          // Status
          Text(
              text = statusText,
              fontSize = 18.sp,
              fontWeight = FontWeight.Medium,
              color = statusColor,
              textAlign = TextAlign.Center)

          Spacer(modifier = Modifier.height(48.dp))

          // Buttons
          Row(
              horizontalArrangement = Arrangement.spacedBy(24.dp),
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {

            Button(
                onClick = onConnect,
                enabled = state != Ipn.State.Running && state != Ipn.State.Starting,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF28A745),
                        disabledContainerColor = Color(0xFF1A5C2C)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(64.dp)) {
                  Text(
                      text = "Connect",
                      fontSize = 18.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color.White)
                }

            Button(
                onClick = onDisconnect,
                enabled = state == Ipn.State.Running || state == Ipn.State.Starting,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC3545),
                        disabledContainerColor = Color(0xFF5C1A22)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(64.dp)) {
                  Text(
                      text = "Disconnect",
                      fontSize = 18.sp,
                      fontWeight = FontWeight.Bold,
                      color = Color.White)
                }
          }
        }
      }
}

class VpnPermissionContract : ActivityResultContract<Intent, Boolean>() {
  override fun createIntent(context: Context, input: Intent): Intent = input

  override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
      resultCode == Activity.RESULT_OK
}
