package com.hermes.mediacontrol

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.*
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Entry-point activity. Its sole job is permission gating and service start.
 * The app has no ongoing UI — once the bridge is running, the user can leave.
 *
 * Permission flow:
 *   1. READ_PHONE_STATE         — runtime request (cellular generation detection)
 *   2. Notification access      — Settings deep-link (needed for MediaSession)
 *   3. Battery optimization off — Settings deep-link (keeps service alive)
 *
 * After all three are satisfied the "Start Bridge" button starts
 * MediaBridgeService as a foreground service and shows the bridge URL.
 */
class MainActivity : AppCompatActivity() {

    // ── Views (created programmatically — no layout XML required) ─────────────

    private lateinit var rowPhoneState:    PermissionRow
    private lateinit var rowNotification:  PermissionRow
    private lateinit var rowBattery:       PermissionRow
    private lateinit var tvBridgeUrl:      TextView
    private lateinit var btnStart:         Button
    private lateinit var btnStop:          Button

    // ── Runtime permission launcher ───────────────────────────────────────────

    private val phoneStatePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        rowPhoneState.setState(granted)
        refreshUi()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        title = "Hermes Media Bridge"
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate every time — user may have come back from a Settings screen
        refreshUi()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission checks
    // ─────────────────────────────────────────────────────────────────────────

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val target = ComponentName(this, HermesNotificationListener::class.java).flattenToString()
        return flat.split(":").any { it == target }
    }

    private fun isBatteryOptimizationExempt(): Boolean =
        getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)

    private fun isServiceRunning(): Boolean {
        // On API 26+ we can't enumerate services easily; use a shared flag instead
        return ServiceStateHolder.isRunning
    }

    private fun allPermissionsGranted(): Boolean =
        hasPhoneStatePermission() && hasNotificationAccess()

    // ─────────────────────────────────────────────────────────────────────────
    // UI state sync
    // ─────────────────────────────────────────────────────────────────────────

    private fun refreshUi() {
        val phoneState   = hasPhoneStatePermission()
        val notification = hasNotificationAccess()
        val battery      = isBatteryOptimizationExempt()
        val running      = isServiceRunning()
        val allGood      = phoneState && notification && battery

        rowPhoneState.setState(phoneState)
        rowNotification.setState(notification)
        rowBattery.setState(battery)

        btnStart.isEnabled = allGood && !running
        btnStop.isEnabled  = running

        if (running) {
            tvBridgeUrl.text = "Bridge active  —  http://${localIpAddress() ?: "?.?.?.?"}:${MediaBridgeService.PORT}"
            tvBridgeUrl.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvBridgeUrl.text = if (allGood) "Ready to start" else "Grant all permissions to continue"
            tvBridgeUrl.setTextColor(Color.GRAY)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────────────────────

    private fun onStartBridge() {
        val intent = Intent(this, MediaBridgeService::class.java)
        ContextCompat.startForegroundService(this, intent)
        ServiceStateHolder.isRunning = true
        refreshUi()
    }

    private fun onStopBridge() {
        stopService(Intent(this, MediaBridgeService::class.java))
        ServiceStateHolder.isRunning = false
        refreshUi()
    }

    private fun requestPhoneStatePermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.READ_PHONE_STATE)) {
            AlertDialog.Builder(this)
                .setTitle("Phone State Permission")
                .setMessage(
                    "Read Phone State lets the bridge detect whether you're on " +
                    "LTE, 5G, or 3G so the G2 HUD can show the correct network label."
                )
                .setPositiveButton("Grant") { _, _ ->
                    phoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
                }
                .setNegativeButton("Skip", null)
                .show()
        } else {
            phoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }

    private fun openNotificationAccessSettings() {
        AlertDialog.Builder(this)
            .setTitle("Notification Access")
            .setMessage(
                "Hermes needs Notification Access to read the active media session " +
                "(track name, artist, playback state). Tap Open, then enable " +
                "\"Hermes Media Bridge\" in the list."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("BatteryLife")
    private fun requestBatteryExemption() {
        AlertDialog.Builder(this)
            .setTitle("Battery Optimization")
            .setMessage(
                "To keep the bridge running in the background without Android killing it, " +
                "please set battery optimization to Unrestricted for this app."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Network — get the device's local Wi-Fi IP for display
    // ─────────────────────────────────────────────────────────────────────────

    private fun localIpAddress(): String? =
        try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Programmatic layout
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density

        fun Int.dp() = (this * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 32.dp(), 24.dp(), 24.dp())
        }

        // ── Header ────────────────────────────────────────────────────────────

        root.addView(TextView(this).apply {
            text      = "Hermes Media Bridge"
            textSize  = 22f
            typeface  = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 4.dp())
        })

        root.addView(TextView(this).apply {
            text      = "G2 glasses media controller companion"
            textSize  = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 24.dp())
        })

        // ── Permission rows ───────────────────────────────────────────────────

        root.addView(sectionLabel("Permissions"))

        rowPhoneState = PermissionRow(
            context     = this,
            label       = "Read Phone State",
            description = "Cellular network type (LTE / 5G / 3G)",
            onClick     = ::requestPhoneStatePermission
        ).also { root.addView(it) }

        rowNotification = PermissionRow(
            context     = this,
            label       = "Notification Access",
            description = "Read active media session (track, artist)",
            onClick     = ::openNotificationAccessSettings
        ).also { root.addView(it) }

        rowBattery = PermissionRow(
            context     = this,
            label       = "Battery Unrestricted",
            description = "Keeps bridge alive in background",
            onClick     = ::requestBatteryExemption
        ).also { root.addView(it) }

        // ── Divider ───────────────────────────────────────────────────────────

        root.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#22000000"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1.dp()
            ).apply { setMargins(0, 24.dp(), 0, 24.dp()) }
        })

        // ── Bridge URL ────────────────────────────────────────────────────────

        root.addView(sectionLabel("Bridge Status"))

        tvBridgeUrl = TextView(this).apply {
            text      = "Grant all permissions to continue"
            textSize  = 14f
            typeface  = Typeface.MONOSPACE
            setTextColor(Color.GRAY)
            setPadding(0, 8.dp(), 0, 24.dp())
        }
        root.addView(tvBridgeUrl)

        // ── Buttons ───────────────────────────────────────────────────────────

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnStart = Button(this).apply {
            text      = "Start Bridge"
            isEnabled = false
            setOnClickListener { onStartBridge() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, 8.dp(), 0) }
        }

        btnStop = Button(this).apply {
            text      = "Stop Bridge"
            isEnabled = false
            setOnClickListener { onStopBridge() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        buttonRow.addView(btnStart)
        buttonRow.addView(btnStop)
        root.addView(buttonRow)

        // ── Footer note ───────────────────────────────────────────────────────

        root.addView(TextView(this).apply {
            text      = "\nPut the bridge URL into PHONE_API in the G2 plugin source."
            textSize  = 12f
            setTextColor(Color.GRAY)
        })

        return root
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text.uppercase()
        textSize  = 11f
        typeface  = Typeface.DEFAULT_BOLD
        setTextColor(Color.parseColor("#888888"))
        val dp = resources.displayMetrics.density
        setPadding(0, 0, 0, (8 * dp).toInt())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PermissionRow — a single permission status row with a tap action
// ─────────────────────────────────────────────────────────────────────────────

class PermissionRow(
    context: Context,
    private val label: String,
    description: String,
    onClick: () -> Unit,
) : LinearLayout(context) {

    private val statusDot: TextView

    init {
        orientation = HORIZONTAL
        val dp = context.resources.displayMetrics.density
        setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        isClickable = true
        isFocusable = true
        setBackgroundResource(android.R.attr.selectableItemBackground.let { attr ->
            val arr = context.obtainStyledAttributes(intArrayOf(attr))
            arr.getResourceId(0, 0).also { arr.recycle() }
        })
        setOnClickListener { onClick() }

        statusDot = TextView(context).apply {
            text     = "●"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            layoutParams = LayoutParams(
                (28 * dp).toInt(), LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, (8 * dp).toInt(), 0) }
        }
        addView(statusDot)

        val textCol = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(context).apply {
            text     = label
            textSize = 15f
        })
        textCol.addView(TextView(context).apply {
            text     = description
            textSize = 12f
            setTextColor(Color.GRAY)
        })
        addView(textCol)

        addView(TextView(context).apply {
            text     = "›"
            textSize = 20f
            setTextColor(Color.LTGRAY)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        })
    }

    fun setState(granted: Boolean) {
        statusDot.setTextColor(
            if (granted) Color.parseColor("#4CAF50") else Color.parseColor("#F44336")
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ServiceStateHolder — lightweight in-process flag to track service state.
// A BoundService or sticky Intent would be more robust; this is sufficient
// for a single-activity utility app where the process lifecycle matches.
// ─────────────────────────────────────────────────────────────────────────────

object ServiceStateHolder {
    @Volatile var isRunning: Boolean = false
}
