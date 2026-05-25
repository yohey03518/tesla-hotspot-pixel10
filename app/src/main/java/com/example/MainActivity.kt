package com.example

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Initialize State Manager
        HotspotAutomatorManager.init(this)
        HotspotAutomatorManager.addLog("MainActivity onCreate invoked", HotspotAutomatorManager.LogType.INFO)

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        AutomatorDashboardScreen()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if Accessibility Service is enabled in System settings and sync state
        val isEnabled = isAccessibilityServiceEnabled(this, HotspotAccessibilityService::class.java)
        HotspotAutomatorManager.setServiceRunning(isEnabled)
    }

    private fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<out AccessibilityService>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }
}

@SuppressLint("InlinedApi")
@Composable
fun AutomatorDashboardScreen() {
    val context = LocalContext.current
    
    // States from state manager
    val logs by HotspotAutomatorManager.logs.collectAsState()
    val isServiceRunning by HotspotAutomatorManager.isServiceRunning.collectAsState()
    
    // Config states
    var isAutomationEnabled by remember { mutableStateOf(HotspotAutomatorManager.isAutomationEnabled()) }
    var isAutoCloseEnabled by remember { mutableStateOf(HotspotAutomatorManager.isAutoCloseSettingsEnabled()) }
    var selectedDeviceAddress by remember { mutableStateOf(HotspotAutomatorManager.getTargetDeviceAddress()) }
    var selectedDeviceName by remember { mutableStateOf(HotspotAutomatorManager.getTargetDeviceName()) }
    
    // Permission state
    var hasBtPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBtPermission = granted
        if (granted) {
            HotspotAutomatorManager.addLog("Bluetooth Connect permission granted at runtime!", HotspotAutomatorManager.LogType.SUCCESS)
        } else {
            HotspotAutomatorManager.addLog("Bluetooth Connect permission was denied.", HotspotAutomatorManager.LogType.ERROR)
        }
    }

    // Auto discover paired Bluetooth devices when permission is granted
    val bluetoothAdapter: BluetoothAdapter? = remember {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter
    }

    var pairedDevices by remember(hasBtPermission) {
        mutableStateOf(
            if (hasBtPermission && bluetoothAdapter != null) {
                try {
                    bluetoothAdapter.bondedDevices.toList()
                } catch (e: SecurityException) {
                    HotspotAutomatorManager.addLog("SecurityException getting bonded devices: ${e.message}", HotspotAutomatorManager.LogType.ERROR)
                    emptyList()
                }
            } else {
                emptyList()
            }
        )
    }

    // Triggered periodically to refresh service state
    LaunchedEffect(Unit) {
        if (!hasBtPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        AppHeader(isServiceRunning = isServiceRunning)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission Notice Card (Only shown if missing BLUETOOTH_CONNECT)
            if (!hasBtPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    PermissionNoticeCard(onClickRequest = {
                        permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    })
                }
            }

            // Accessibility Status Card
            item {
                AccessibilityStatusCard(
                    isServiceRunning = isServiceRunning,
                    onOpenSettings = {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                            HotspotAutomatorManager.addLog("Opening Accessibility Settings", HotspotAutomatorManager.LogType.ACTION)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open Accessibility Settings: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }

            // General Configuration Options Card
            item {
                AutomationConfigCard(
                    isEnabled = isAutomationEnabled,
                    onEnabledChange = {
                        isAutomationEnabled = it
                        HotspotAutomatorManager.setAutomationEnabled(it)
                    },
                    isAutoClose = isAutoCloseEnabled,
                    onAutoCloseChange = {
                        isAutoCloseEnabled = it
                        HotspotAutomatorManager.setAutoCloseSettingsEnabled(it)
                    }
                )
            }

            // Target Device Selection Card
            item {
                TargetDeviceCard(
                    pairedDevices = pairedDevices,
                    selectedAddress = selectedDeviceAddress,
                    selectedName = selectedDeviceName,
                    onSelectDevice = { address, name ->
                        selectedDeviceAddress = address
                        selectedDeviceName = name
                        HotspotAutomatorManager.saveTargetDevice(address, name)
                    },
                    onRefreshDevices = {
                        if (hasBtPermission && bluetoothAdapter != null) {
                            try {
                                pairedDevices = bluetoothAdapter.bondedDevices.toList()
                                HotspotAutomatorManager.addLog("Refreshed bonded Bluetooth devices list (${pairedDevices.size} found)", HotspotAutomatorManager.LogType.INFO)
                            } catch (e: SecurityException) {
                                HotspotAutomatorManager.addLog("Failed to refresh: Permission missing", HotspotAutomatorManager.LogType.ERROR)
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }
                )
            }

            // Trigger Testing Actions
            item {
                TestingConsoleCard(
                    isServiceActive = isServiceRunning,
                    onTestOn = {
                        HotspotAutomatorManager.addLog("Manual test triggered: Turn Hotspot ON", HotspotAutomatorManager.LogType.ACTION)
                        HotspotAutomatorManager.triggerHotspotToggle(true)
                        // Launch settings activity to trigger accessibility service
                        try {
                            val intent = Intent("android.settings.WIRELESS_SETTINGS").apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            HotspotAutomatorManager.addLog("Failed to open wireless settings: ${e.message}", HotspotAutomatorManager.LogType.ERROR)
                        }
                    },
                    onTestOff = {
                        HotspotAutomatorManager.addLog("Manual test triggered: Turn Hotspot OFF", HotspotAutomatorManager.LogType.ACTION)
                        HotspotAutomatorManager.triggerHotspotToggle(false)
                        // Launch settings activity to trigger accessibility service
                        try {
                            val intent = Intent("android.settings.WIRELESS_SETTINGS").apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            HotspotAutomatorManager.addLog("Failed to open wireless settings: ${e.message}", HotspotAutomatorManager.LogType.ERROR)
                        }
                    }
                )
            }

            // Logging Monitor Card
            item {
                LogsMonitorCard(
                    logs = logs,
                    onClearLogs = { HotspotAutomatorManager.clearLogs() },
                    onCopyLogcat = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("adb_command", "adb logcat -s HotspotAutomator")
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Logcat command copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun AppHeader(isServiceRunning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Hotspot Automator",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("app_title")
            )
            Text(
                text = "Bluetooth Trigger for Tesla Hotspot",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Status Pulse Badge
        Surface(
            color = if (isServiceRunning) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.border(
                1.dp, 
                if (isServiceRunning) Color(0xFF81C784) else Color(0xFFEF5350), 
                RoundedCornerShape(12.dp)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (isServiceRunning) Color(0xFF4CAF50) else Color(0xFFF44336), CircleShape)
                )
                Text(
                    text = if (isServiceRunning) "ACTIVE" else "INACTIVE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isServiceRunning) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }
        }
    }
}

@Composable
fun PermissionNoticeCard(onClickRequest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Permission Required",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = "Starting with Android 12, Bluetooth Connect permissions are mandatory to discover paired devices and monitor connection logs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(
                onClick = onClickRequest,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.align(Alignment.End).testTag("grant_bt_permission_btn")
            ) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
fun AccessibilityStatusCard(isServiceRunning: Boolean, onOpenSettings: () -> Unit) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isServiceRunning) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (isServiceRunning) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = "Accessibility status icon",
                    tint = if (isServiceRunning) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Column {
                    Text(
                        text = "Hotspot Automator Service",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = if (isServiceRunning) "Running in background" else "Requires Activation",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Text(
                text = "Our helper accessibility service executes simulated clicks to turn on your hotspot without requiring root. Turn this SERVICE on in Android settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("on_open_accessibility_settings_btn")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings icon")
                    Text(if (isServiceRunning) "Configure Service Settings" else "Enable Accessibility Service")
                }
            }
        }
    }
}

@Composable
fun AutomationConfigCard(
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isAutoClose: Boolean,
    onAutoCloseChange: (Boolean) -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Preferences",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Automations Active",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Enable or disable trigger events globally",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.testTag("automation_enabled_switch")
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Close Settings",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Automatically press home/back after toggling the switch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isAutoClose,
                    onCheckedChange = onAutoCloseChange,
                    modifier = Modifier.testTag("autoclose_enabled_switch")
                )
            }
        }
    }
}

@Composable
fun TargetDeviceCard(
    pairedDevices: List<BluetoothDevice>,
    selectedAddress: String?,
    selectedName: String?,
    onSelectDevice: (String, String) -> Unit,
    onRefreshDevices: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Tesla Icon",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Trigger Bluetooth Device",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh paired list",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onRefreshDevices() }
                )
            }

            Text(
                text = "When your phone connects to this specific Bluetooth device (e.g. your Tesla), the hotspot turns on. When it disconnects, it turns off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Selected Device State UI
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Bluetooth Connected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = selectedName ?: "No Device Configured",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = selectedAddress ?: "Tap to configure device trigger",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = if (isExpanded) "CLOSE" else "CHANGE",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Expandable paired device selection list
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Paired Devices List:",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    if (pairedDevices.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No paired devices found or Bluetooth permission was not granted. Tap Refresh icon to find or check settings.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            pairedDevices.forEach { device ->
                                val deviceName = try { device.name ?: "Unknown Name" } catch (e: SecurityException) { "Unknown" }
                                val deviceAddress = device.address
                                val isSelected = deviceAddress == selectedAddress

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                                        )
                                        .border(
                                            1.dp,
                                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            onSelectDevice(deviceAddress, deviceName)
                                            isExpanded = false
                                        }
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = deviceName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text(
                                            text = deviceAddress,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TestingConsoleCard(
    isServiceActive: Boolean,
    onTestOn: () -> Unit,
    onTestOff: () -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Testing icon",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Automation Testing Center",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            Text(
                text = "Simulate state triggers instantly! This tests the Accessibility Service click simulation. (Make sure service has been turned ON)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onTestOn,
                    enabled = isServiceActive,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50), disabledContainerColor = Color.LightGray),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("simulate_on_btn")
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "On symbol", modifier = Modifier.size(16.dp))
                        Text("Simulate Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = onTestOff,
                    enabled = isServiceActive,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935), disabledContainerColor = Color.LightGray),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("simulate_off_btn")
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Off symbol", modifier = Modifier.size(16.dp))
                        Text("Simulate Disconnect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun LogsMonitorCard(
    logs: List<HotspotAutomatorManager.LogEntry>,
    onClearLogs: () -> Unit,
    onCopyLogcat: () -> Unit
) {
    val listState = rememberLazyListState()

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = "Logs Monitor",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Live Activity Logs",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CLEAR",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { onClearLogs() }
                            .padding(4.dp)
                    )
                }
            }

            Text(
                text = "These matching events trigger inside the background context. You can also monitor everything via Android ADB Console.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Logs Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No log messages tracked yet.\nTry triggering simulation buttons above!",
                            style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { entry ->
                            LogItemRow(entry = entry)
                        }
                    }
                }
            }

            Button(
                onClick = onCopyLogcat,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(imageVector = Icons.Default.Build, contentDescription = "Terminal symbol", modifier = Modifier.size(16.dp))
                    Text("Copy ADB Logcat Debug Command", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun LogItemRow(entry: HotspotAutomatorManager.LogEntry) {
    val color = when (entry.type) {
        HotspotAutomatorManager.LogType.INFO -> Color(0xFFB0BEC5)
        HotspotAutomatorManager.LogType.SUCCESS -> Color(0xFF81C784)
        HotspotAutomatorManager.LogType.WARNING -> Color(0xFFFFB74D)
        HotspotAutomatorManager.LogType.ERROR -> Color(0xFFE57373)
        HotspotAutomatorManager.LogType.ACTION -> Color(0xFF64B5F6)
    }

    val symbol = when (entry.type) {
        HotspotAutomatorManager.LogType.INFO -> "ℹ"
        HotspotAutomatorManager.LogType.SUCCESS -> "✓"
        HotspotAutomatorManager.LogType.WARNING -> "⚠"
        HotspotAutomatorManager.LogType.ERROR -> "✗"
        HotspotAutomatorManager.LogType.ACTION -> "▶"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "[${entry.timestamp}]",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            color = Color(0xFFAAAAAA)
        )
        Text(
            text = symbol,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            ),
            color = color
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = color,
            modifier = Modifier.weight(1f)
        )
    }
}
