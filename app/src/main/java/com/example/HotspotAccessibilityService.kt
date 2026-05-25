package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.text.SimpleDateFormat
import java.util.Locale

class HotspotAccessibilityService : AccessibilityService() {

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val deviceAddress = device.address
            val deviceName = try { device.name } catch (e: SecurityException) { null } ?: "Unknown Device"

            HotspotAutomatorManager.addLog(
                "Bluetooth event received: $action from device: $deviceName ($deviceAddress)",
                HotspotAutomatorManager.LogType.INFO
            )

            // Get target device settings
            val targetAddress = HotspotAutomatorManager.getTargetDeviceAddress()
            val isEnabled = HotspotAutomatorManager.isAutomationEnabled()

            if (!isEnabled) {
                HotspotAutomatorManager.addLog("Automation is disabled in settings. Ignoring bluetooth event.", HotspotAutomatorManager.LogType.WARNING)
                return
            }

            if (targetAddress == null) {
                HotspotAutomatorManager.addLog("No target Bluetooth device configured. Ignoring event.", HotspotAutomatorManager.LogType.WARNING)
                return
            }

            // Check if this is our configured device
            if (deviceAddress.equals(targetAddress, ignoreCase = true)) {
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        HotspotAutomatorManager.addLog("Target device connected! Preparing to enable Wi-Fi Hotspot.", HotspotAutomatorManager.LogType.SUCCESS)
                        triggerHotspotAutomation(true)
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        HotspotAutomatorManager.addLog("Target device disconnected! Preparing to disable Wi-Fi Hotspot.", HotspotAutomatorManager.LogType.SUCCESS)
                        triggerHotspotAutomation(false)
                    }
                }
            } else {
                HotspotAutomatorManager.addLog("Connected device does not match configured target ($targetAddress). Ignoring.", HotspotAutomatorManager.LogType.INFO)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        HotspotAutomatorManager.init(this)
        HotspotAutomatorManager.addLog("Accessibility Service onCreate", HotspotAutomatorManager.LogType.INFO)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        HotspotAutomatorManager.setServiceRunning(true)
        HotspotAutomatorManager.addLog("Accessibility Service successfully connected and active", HotspotAutomatorManager.LogType.SUCCESS)

        // Register the Bluetooth status receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(bluetoothReceiver, filter)
        HotspotAutomatorManager.addLog("Bluetooth ACL connection receiver registered inside Accessibility Service context.", HotspotAutomatorManager.LogType.INFO)
    }

    private var lastActionTime = 0L

    private fun isTargetSettingsPackage(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.getDefault())
        if (lower.isEmpty()) return false
        if (lower == "com.android.systemui" || lower == "android" || lower.contains("launcher") || lower.contains("keyguard")) {
            return false
        }
        if (lower == packageName || lower.contains("com.example") || lower.contains("hotspotautomator")) {
            return false
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPkg = event.packageName?.toString() ?: ""
        if (eventPkg == packageName || eventPkg == "com.example" || eventPkg.contains("hotspotautomator")) {
            return
        }

        val targetState = HotspotAutomatorManager.targetHotspotState.value
        val isPending = HotspotAutomatorManager.isHotspotPendingToggle.value

        if (!isPending || targetState == null) {
            return
        }

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Rate limit to allow window animations and screens to fully render (1.8 seconds)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastActionTime < 1800) {
                return
            }
            lastActionTime = currentTime
            
            triggerScreenAnalysis()
        }
    }

    private fun triggerScreenAnalysis() {
        val targetState = HotspotAutomatorManager.targetHotspotState.value
        val isPending = HotspotAutomatorManager.isHotspotPendingToggle.value
        
        if (!isPending || targetState == null) {
            return
        }

        var rootNode: AccessibilityNodeInfo? = null
        
        // 1. Scan across all interactive application windows for the settings app
        val windowList = try { windows } catch (e: Exception) { null }
        if (windowList != null && windowList.isNotEmpty()) {
            val appWindows = windowList.filter { 
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root != null 
            }
            val targetWindow = appWindows.find { win ->
                val root = win.root
                if (root != null) {
                    val pkg = root.packageName?.toString() ?: ""
                    isTargetSettingsPackage(pkg)
                } else false
            } ?: appWindows.firstOrNull()
            
            if (targetWindow != null) {
                rootNode = targetWindow.root
            }
        }
        
        // 2. Fallback to active window
        if (rootNode == null) {
            rootNode = rootInActiveWindow
        }
        
        if (rootNode != null) {
            processCurrentScreen(rootNode)
        } else {
            HotspotAutomatorManager.addLog("Could not find any active screen layout root node (refresh scan pending...)", HotspotAutomatorManager.LogType.INFO)
        }
    }

    private fun processCurrentScreen(rootNode: AccessibilityNodeInfo) {
        val activePkg = rootNode.packageName?.toString() ?: ""
        if (!isTargetSettingsPackage(activePkg)) {
            return
        }

        // Double Layer of Defense: If our own app's unique UI text is on screen, bypass it!
        if (findAnyNodeWithText(rootNode, listOf("hotspot automator", "automation testing center"))) {
            return
        }

        // Dump screen contents recursively in a structured layout tree
        val treeLines = mutableListOf<String>()
        collectScreenTree(rootNode, treeLines)
        val screenDump = treeLines.joinToString("\n")
        HotspotAutomatorManager.addLog("Settings Screen Layout (Package: $activePkg):\n$screenDump", HotspotAutomatorManager.LogType.INFO)

        val targetState = HotspotAutomatorManager.targetHotspotState.value ?: return

        // Normalize search across screens using robust patterns supporting both English and Chinese
        val wifiHotspotNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByPatterns(
            rootNode,
            listOf(
                "wi-fi hotspot", "wifi hotspot", "wi‑fi hotspot", 
                "use wi-fi hotspot", "use wifi hotspot",
                "portable hotspot", "portable wifi hotspot", "portable wi-fi hotspot",
                "mobile hotspot", "personal hotspot", "hotspot setting", "share phone's internet",
                "無線基地台", "可攜式無線基地台", "可攜式熱點", "行動熱點", "個人熱點", 
                "wlan熱點", "wifi熱點", "wlan热点", "wifi热点", "便携式wlan热点", "便携式热点", "移动热点"
            ),
            wifiHotspotNodes
        )

        val hotspotTetheringNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByPatterns(
            rootNode, 
            listOf(
                "hotspot & tethering", "hotspot and tethering", "hotspot & tether", "tethering & portable hotspot",
                "無線基地台與網路共用", "熱點與網路共用", "熱點和網絡共享", "熱點和網路共享", "網路共用", 
                "網絡共享", "網路共享", "綁定與可攜式熱點", "连接与共享"
            ), 
            hotspotTetheringNodes
        )

        val networkInternetNodes = mutableListOf<AccessibilityNodeInfo>()
        findNodesByPatterns(
            rootNode, 
            listOf(
                "network & internet", "network and internet", "connections",
                "網路和網際網路", "網路與網際網路", "連線", "連接", "网络和互联网"
            ), 
            networkInternetNodes
        )

        val allSwitches = mutableListOf<AccessibilityNodeInfo>()
        findAllSwitches(rootNode, allSwitches)

        // ==========================================
        // ACTION SELECTION (Most specific to least specific)
        // ==========================================

        // Rule 1: We found the actual Wi-Fi Hotspot Switch
        var targetSwitchNode: AccessibilityNodeInfo? = null
        for (wifiNode in wifiHotspotNodes) {
            var parent = wifiNode.parent
            for (level in 0..4) {
                if (parent == null) break
                val childSwitch = findSwitchChild(parent)
                if (childSwitch != null) {
                    targetSwitchNode = childSwitch
                    break
                }
                parent = parent.parent
            }
            if (targetSwitchNode != null) break
        }

        // Rule 1b: Fallback for subscreen detail switch (usually the only switch on screen)
        if (targetSwitchNode == null && wifiHotspotNodes.isNotEmpty()) {
            if (allSwitches.size == 1) {
                targetSwitchNode = allSwitches[0]
                HotspotAutomatorManager.addLog("Subscreen fallback: Exactly 1 switch found, assuming Wi-Fi Hotspot toggle", HotspotAutomatorManager.LogType.INFO)
            }
        }

        // If we located the toggle switch, let's act on it!
        if (targetSwitchNode != null) {
            val isChecked = targetSwitchNode.isChecked
            HotspotAutomatorManager.addLog("Target Switch found! Checked: $isChecked, Target: $targetState", HotspotAutomatorManager.LogType.SUCCESS)
            
            if (isChecked != targetState) {
                HotspotAutomatorManager.addLog("Toggling switch to " + (if (targetState) "ON" else "OFF"), HotspotAutomatorManager.LogType.ACTION)
                val clicked = clickNode(targetSwitchNode)
                if (clicked) {
                    HotspotAutomatorManager.addLog("Switch clicked successfully!", HotspotAutomatorManager.LogType.SUCCESS)
                    saveLastAutomationExecutionTime()
                    HotspotAutomatorManager.clearToggleRequest()
                    
                    if (HotspotAutomatorManager.isAutoCloseSettingsEnabled()) {
                        performAutoClose()
                    }
                } else {
                    HotspotAutomatorManager.addLog("Failed to click hotspot switch element.", HotspotAutomatorManager.LogType.ERROR)
                }
            } else {
                HotspotAutomatorManager.addLog("Switch is already in desired state ($targetState). Completing logic.", HotspotAutomatorManager.LogType.SUCCESS)
                HotspotAutomatorManager.clearToggleRequest()
                if (HotspotAutomatorManager.isAutoCloseSettingsEnabled()) {
                    performAutoClose()
                }
            }
            return
        }

        // Rule 2: We see "Wi-Fi Hotspot" category row but NO switch is associated. Click to open subpage.
        if (wifiHotspotNodes.isNotEmpty()) {
            val wifiNode = wifiHotspotNodes[0]
            HotspotAutomatorManager.addLog("Found 'Wi-Fi Hotspot' row. Clicking to open subpage...", HotspotAutomatorManager.LogType.ACTION)
            if (clickNode(wifiNode)) {
                HotspotAutomatorManager.addLog("Clicked 'Wi-Fi Hotspot' row successfully.", HotspotAutomatorManager.LogType.SUCCESS)
                // Schedule analysis in 1.2s to process detail subpage
                Handler(Looper.getMainLooper()).postDelayed({
                    triggerScreenAnalysis()
                }, 1200)
            } else {
                HotspotAutomatorManager.addLog("Failed to click 'Wi-Fi Hotspot' row element.", HotspotAutomatorManager.LogType.ERROR)
            }
            return
        }

        // Rule 3: We see "Hotspot & tethering" navigation item. click it to open!
        if (hotspotTetheringNodes.isNotEmpty()) {
            val nodeToClick = hotspotTetheringNodes[0]
            HotspotAutomatorManager.addLog("Found 'Hotspot & Tethering' navigation row. Clicking to open...", HotspotAutomatorManager.LogType.ACTION)
            if (clickNode(nodeToClick)) {
                HotspotAutomatorManager.addLog("Clicked 'Hotspot & Tethering' row successfully.", HotspotAutomatorManager.LogType.SUCCESS)
                // Schedule analysis in 1.2s to process subpage
                Handler(Looper.getMainLooper()).postDelayed({
                    triggerScreenAnalysis()
                }, 1200)
            } else {
                HotspotAutomatorManager.addLog("Failed to click 'Hotspot & Tethering' row element.", HotspotAutomatorManager.LogType.ERROR)
            }
            return
        }

        // Rule 4: We see "Network & Internet" homepage navigation item. click it!
        if (networkInternetNodes.isNotEmpty()) {
            val nodeToClick = networkInternetNodes[0]
            HotspotAutomatorManager.addLog("Found 'Network & Internet' navigation row. Clicking to open...", HotspotAutomatorManager.LogType.ACTION)
            if (clickNode(nodeToClick)) {
                HotspotAutomatorManager.addLog("Clicked 'Network & Internet' row successfully.", HotspotAutomatorManager.LogType.SUCCESS)
                // Schedule analysis in 1.2s to process subpage
                Handler(Looper.getMainLooper()).postDelayed({
                    triggerScreenAnalysis()
                }, 1200)
            } else {
                HotspotAutomatorManager.addLog("Failed to click 'Network & Internet' row element.", HotspotAutomatorManager.LogType.ERROR)
            }
            return
        }
    }

    override fun onInterrupt() {
        HotspotAutomatorManager.addLog("Accessibility Service was interrupted", HotspotAutomatorManager.LogType.WARNING)
    }

    override fun onDestroy() {
        super.onDestroy()
        HotspotAutomatorManager.setServiceRunning(false)
        try {
            unregisterReceiver(bluetoothReceiver)
            HotspotAutomatorManager.addLog("Bluetooth ACL connection receiver unregistered.", HotspotAutomatorManager.LogType.INFO)
        } catch (e: Exception) {
            HotspotAutomatorManager.addLog("Error unregistering receiver during destroy: ${e.message}", HotspotAutomatorManager.LogType.ERROR)
        }
        HotspotAutomatorManager.addLog("Accessibility Service destroyed", HotspotAutomatorManager.LogType.INFO)
    }

    private fun triggerHotspotAutomation(turnOn: Boolean) {
        HotspotAutomatorManager.triggerHotspotToggle(turnOn)
        
        // Rate-limit the very first tick as well, to give settings screen 2 seconds to load
        lastActionTime = System.currentTimeMillis()
        
        // Open Network & Internet (WIRELESS_SETTINGS) settings directly first
        try {
            val intent = Intent().apply {
                action = "android.settings.WIRELESS_SETTINGS"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            HotspotAutomatorManager.addLog("Launched Wireless (Network & Internet) Settings screen", HotspotAutomatorManager.LogType.ACTION)
        } catch (e: Exception) {
            HotspotAutomatorManager.addLog("Error launching Wireless settings: ${e.message}. Attempting fallback launch.", HotspotAutomatorManager.LogType.WARNING)
            try {
                val intent = Intent().apply {
                    action = "android.settings.SETTINGS"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (ex: Exception) {
                HotspotAutomatorManager.addLog("All settings Intents failed to launch: ${ex.message}", HotspotAutomatorManager.LogType.ERROR)
            }
        }

        // Schedule automated check after 2.2 seconds to guarantee action regardless of event fires
        Handler(Looper.getMainLooper()).postDelayed({
            HotspotAutomatorManager.addLog("Triggering scheduled post-launch screen analysis...", HotspotAutomatorManager.LogType.INFO)
            triggerScreenAnalysis()
        }, 2200)
    }

    private fun collectScreenTree(node: AccessibilityNodeInfo, list: MutableList<String>, depth: Int = 0) {
        if (list.size >= 80) {
            if (list.last() != "... (truncated)") {
                list.add("... (truncated)")
            }
            return
        }
        if (depth > 15) return
        val text = node.text?.toString()?.trim() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val className = node.className?.toString() ?: ""
        val cleanClass = className.substringAfterLast('.')
        val resId = node.viewIdResourceName?.toString()?.substringAfterLast(':') ?: ""
        
        val props = mutableListOf<String>()
        if (node.isCheckable) props.add("checkable")
        if (node.isChecked) props.add("checked")
        if (node.isClickable) props.add("clickable")
        
        val propsStr = if (props.isNotEmpty()) " [${props.joinToString(",")}]" else ""
        val idStr = if (resId.isNotEmpty()) " id:$resId" else ""
        val textStr = if (text.isNotEmpty()) " text=\"$text\"" else ""
        val descStr = if (contentDesc.isNotEmpty()) " desc=\"$contentDesc\"" else ""
        
        val indent = "  ".repeat(depth)
        list.add("$indent- $cleanClass$idStr$textStr$descStr$propsStr")
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectScreenTree(child, list, depth + 1)
        }
    }

    private fun findNodesByPatterns(
        node: AccessibilityNodeInfo,
        patterns: List<String>,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val combined = (text + " " + contentDesc).lowercase(Locale.getDefault()).replace("‑", "-")
        
        for (pat in patterns) {
            if (combined.contains(pat)) {
                results.add(node)
                break
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByPatterns(child, patterns, results)
        }
    }

    private fun findAllSwitches(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString() ?: ""
        if (className.contains("Switch") || className.contains("ToggleButton") || node.isCheckable) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllSwitches(child, results)
        }
    }

    private fun findSwitchChild(parent: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val className = child.className?.toString() ?: ""
            if (className.contains("Switch") || className.contains("ToggleButton") || child.isCheckable) {
                return child
            }
            val subChild = findSwitchChild(child)
            if (subChild != null) {
                return subChild
            }
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var tempNode: AccessibilityNodeInfo? = node
        while (tempNode != null) {
            if (tempNode.isClickable) {
                val success = tempNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    return true
                }
            }
            tempNode = tempNode.parent
        }
        
        // Fallback: Perform simulated screen hardware gesture click
        HotspotAutomatorManager.addLog("Accessibility mechanical click failed or row not marked clickable. Attempting simulated hardware tap gesture...", HotspotAutomatorManager.LogType.WARNING)
        return gestureClick(node)
    }

    private fun gestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            return false
        }
        
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()
        
        val path = Path().apply {
            moveTo(x, y)
        }
        
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        val success = dispatchGesture(gesture, null, null)
        if (success) {
            HotspotAutomatorManager.addLog("Dispatched tap gesture at screen coordinates ($x, $y)", HotspotAutomatorManager.LogType.SUCCESS)
        } else {
            HotspotAutomatorManager.addLog("Failed to dispatch tap gesture at screen coordinates ($x, $y)", HotspotAutomatorManager.LogType.ERROR)
        }
        return success
    }

    private fun findAnyNodeWithText(node: AccessibilityNodeInfo, substrings: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase(Locale.getDefault()) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.getDefault()) ?: ""
        for (sub in substrings) {
            if (text.contains(sub) || desc.contains(sub)) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAnyNodeWithText(child, substrings)) {
                return true
            }
        }
        return false
    }

    private fun performAutoClose() {
        HotspotAutomatorManager.addLog("Scheduling auto-close settings in 1.5 seconds...", HotspotAutomatorManager.LogType.INFO)
        Handler(Looper.getMainLooper()).postDelayed({
            HotspotAutomatorManager.addLog("Performing system BACK to hide Settings navigation stack", HotspotAutomatorManager.LogType.ACTION)
            val closed = performGlobalAction(GLOBAL_ACTION_BACK)
            if (!closed) {
                HotspotAutomatorManager.addLog("BACK key action rejected. Executing HOME key action fallback", HotspotAutomatorManager.LogType.ACTION)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, 1500)
    }

    private fun saveLastAutomationExecutionTime() {
        val prefs = getSharedPreferences("hotspot_automator_prefs", Context.MODE_PRIVATE)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        prefs.edit().putString("last_run_time", dateFormat.format(java.util.Date())).apply()
    }
}
