package org.example.lanalarm

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_OVERLAY_REQUESTED = "overlay_permission_requested"
        private val WEEKDAY_KEYS = listOf(
            "monday",
            "tuesday",
            "wednesday",
            "thursday",
            "friday",
            "saturday",
            "sunday"
        )
        private val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }

    private lateinit var serviceStatus: TextView
    private lateinit var secretStatus: TextView
    private lateinit var serviceToggle: Button
    private lateinit var scanSecretButton: Button
    private lateinit var bootToggle: Switch
    private lateinit var tabSettings: ImageView
    private lateinit var tabAlarms: ImageView
    private lateinit var settingsContent: View
    private lateinit var alarmsContent: View
    private lateinit var alarmsList: LinearLayout
    private lateinit var alarmsEmpty: TextView
    private lateinit var alarmsScroll: ScrollView
    private lateinit var alarmsLastSync: TextView
    private lateinit var alarmsAdd: Button
    private lateinit var wsStatus: TextView
    private lateinit var nextAlarmStatus: TextView
    private lateinit var nextAlarmDetail: TextView
    private lateinit var impedanceNote: TextView
    private lateinit var homeWifiStatus: TextView
    private lateinit var setHomeWifiButton: Button
    private lateinit var permissionsStatus: TextView
    private lateinit var grantPermissionsButton: Button
    private lateinit var readingsStatus: TextView
    private lateinit var uploadNowButton: Button
    private lateinit var weighNowButton: Button
    private lateinit var ontoplanoPattern: EditText
    private lateinit var ontoplanoKindHard: TextView
    private lateinit var ontoplanoKindSoft: TextView
    private lateinit var ontoplanoSave: Button
    private lateinit var ontoplanoStatus: TextView
    private lateinit var ontoplanoAccount: TextView
    private lateinit var ontoplanoDetail: TextView
    private lateinit var pcLinkStatus: TextView
    private lateinit var homeWifiCaption: TextView
    private lateinit var scaleModeWeight: TextView
    private lateinit var scaleModeBodyFat: TextView
    private lateinit var scaleModeCaption: TextView
    private lateinit var tabWeight: ImageView
    private lateinit var weightContent: View
    private lateinit var weightChart: WeightChartView
    private lateinit var weightLatest: TextView
    private lateinit var weightLatestWhen: TextView
    private lateinit var weightTrend: TextView
    private lateinit var weightSource: TextView
    private lateinit var weightList: LinearLayout
    private lateinit var weightRange30: TextView
    private lateinit var weightRange90: TextView
    private lateinit var weightRangeAll: TextView

    private var weightEntries: List<WeightHistory.Entry> = emptyList()
    private var weightRangeDays: Int = 90
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val wsStatusRunnable = object : Runnable {
        override fun run() {
            updateWsStatus()
            wsStatus.postDelayed(this, 3000)
        }
    }

    private enum class Tab {
        SETTINGS,
        ALARMS,
        WEIGHT
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        // The QR now carries the PC's address and the scale parameters as well as
        // the secret, because the phone has to work without the PC prompting it.
        if (AppSettings.applyProvisioning(this, contents)) {
            Toast.makeText(this, "Paired with PC", Toast.LENGTH_SHORT).show()
            AlarmService.instance?.reconnectWebSocketNow()
            AlarmService.instance?.uploadReadings()
        } else {
            Toast.makeText(this, "That QR code was not recognised", Toast.LENGTH_LONG).show()
        }
        updateUI()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScanner()
    }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val denied = grants.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(
                this,
                "Without these, hard alarms fall back to a dismiss button",
                Toast.LENGTH_LONG
            ).show()
        }
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceStatus = findViewById(R.id.service_status)
        secretStatus = findViewById(R.id.secret_status)
        serviceToggle = findViewById(R.id.service_toggle)
        scanSecretButton = findViewById(R.id.scan_secret)
        bootToggle = findViewById(R.id.boot_toggle)
        tabSettings = findViewById(R.id.tab_settings)
        tabAlarms = findViewById(R.id.tab_alarms)
        settingsContent = findViewById(R.id.settings_content)
        alarmsContent = findViewById(R.id.alarms_content)
        alarmsList = findViewById(R.id.alarms_list)
        alarmsEmpty = findViewById(R.id.alarms_empty)
        alarmsScroll = findViewById(R.id.alarms_scroll)
        alarmsLastSync = findViewById(R.id.alarms_last_sync)
        alarmsAdd = findViewById(R.id.alarms_add)
        wsStatus = findViewById(R.id.ws_status)
        nextAlarmStatus = findViewById(R.id.next_alarm_status)
        nextAlarmDetail = findViewById(R.id.next_alarm_detail)
        impedanceNote = findViewById(R.id.impedance_note)
        homeWifiStatus = findViewById(R.id.home_wifi_status)
        setHomeWifiButton = findViewById(R.id.set_home_wifi)
        permissionsStatus = findViewById(R.id.permissions_status)
        grantPermissionsButton = findViewById(R.id.grant_permissions)
        readingsStatus = findViewById(R.id.readings_status)
        uploadNowButton = findViewById(R.id.upload_now)
        weighNowButton = findViewById(R.id.weigh_now)
        ontoplanoPattern = findViewById(R.id.ontoplano_pattern)
        ontoplanoKindHard = findViewById(R.id.ontoplano_kind_hard)
        ontoplanoKindSoft = findViewById(R.id.ontoplano_kind_soft)
        ontoplanoSave = findViewById(R.id.ontoplano_save)
        ontoplanoStatus = findViewById(R.id.ontoplano_status)
        ontoplanoAccount = findViewById(R.id.ontoplano_account)
        ontoplanoDetail = findViewById(R.id.ontoplano_detail)
        pcLinkStatus = findViewById(R.id.pc_link_status)
        homeWifiCaption = findViewById(R.id.home_wifi_caption)
        scaleModeWeight = findViewById(R.id.scale_mode_weight)
        scaleModeBodyFat = findViewById(R.id.scale_mode_bodyfat)
        scaleModeCaption = findViewById(R.id.scale_mode_caption)
        tabWeight = findViewById(R.id.tab_weight)
        weightContent = findViewById(R.id.weight_content)
        weightChart = findViewById(R.id.weight_chart)
        weightLatest = findViewById(R.id.weight_latest)
        weightLatestWhen = findViewById(R.id.weight_latest_when)
        weightTrend = findViewById(R.id.weight_trend)
        weightSource = findViewById(R.id.weight_source)
        weightList = findViewById(R.id.weight_list)
        weightRange30 = findViewById(R.id.weight_range_30)
        weightRange90 = findViewById(R.id.weight_range_90)
        weightRangeAll = findViewById(R.id.weight_range_all)

        tabWeight.setOnClickListener { selectTab(Tab.WEIGHT) }
        scaleModeWeight.setOnClickListener { setScaleMode(AppSettings.FLAG_WEIGHT_ONLY) }
        scaleModeBodyFat.setOnClickListener { setScaleMode(AppSettings.FLAG_BODY_FAT) }
        weightRange30.setOnClickListener { setWeightRange(30) }
        weightRange90.setOnClickListener { setWeightRange(90) }
        weightRangeAll.setOnClickListener { setWeightRange(0) }

        setHomeWifiButton.setOnClickListener { captureHomeNetwork() }
        grantPermissionsButton.setOnClickListener { requestRuntimePermissions() }
        uploadNowButton.setOnClickListener {
            AlarmService.instance?.uploadReadings()
            Toast.makeText(this, "Uploading…", Toast.LENGTH_SHORT).show()
            uploadNowButton.postDelayed({ updateUI() }, 1500)
        }
        weighNowButton.setOnClickListener { toggleScaleListening() }

        ontoplanoKindHard.setOnClickListener { selectOntoplanoKind(AlarmSchedule.KIND_HARD) }
        ontoplanoKindSoft.setOnClickListener { selectOntoplanoKind(AlarmSchedule.KIND_SOFT) }
        ontoplanoSave.setOnClickListener { applyOntoplanoRule() }

        tabSettings.setOnClickListener { selectTab(Tab.SETTINGS) }
        tabAlarms.setOnClickListener { selectTab(Tab.ALARMS) }
        alarmsAdd.setOnClickListener { showAddAlarmDialog() }

        serviceToggle.setOnClickListener {
            val serviceIntent = Intent(this, AlarmService::class.java)
            if (AlarmService.instance != null) {
                stopService(serviceIntent)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
            serviceToggle.postDelayed({ updateUI() }, 500)
        }

        scanSecretButton.setOnClickListener {
            if (checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchScanner()
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }

        bootToggle.setOnCheckedChangeListener { _, isChecked ->
            val state = if (isChecked)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            packageManager.setComponentEnabledSetting(
                ComponentName(this, BootReceiver::class.java),
                state,
                PackageManager.DONT_KILL_APP
            )
        }

        ensureServiceRunning()
        selectTab(Tab.SETTINGS)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
        renderAlarms()
        ensureOverlayPermission()

        // The service now backs off to a quarter of an hour between reconnect
        // attempts rather than retrying every fifteen seconds, so opening the
        // app is the user's way of saying "try now" — which is exactly what
        // someone does when they want to see the PC link come up.
        AlarmService.instance?.let { if (!AlarmService.wsConnected) it.reconnectWebSocketNow() }

        updateWsStatus()
        wsStatus.post(wsStatusRunnable)
    }

    override fun onPause() {
        super.onPause()
        wsStatus.removeCallbacks(wsStatusRunnable)
    }

    private fun ensureServiceRunning() {
        if (AlarmService.instance == null) {
            val serviceIntent = Intent(this, AlarmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    private fun updateUI() {
        val running = AlarmService.instance != null
        serviceStatus.text = if (running) "Running" else "Stopped"
        serviceToggle.text = if (running) "Stop service" else "Start service"

        val pcAddress = AppSettings.pcBaseUrl(this)
        secretStatus.text = when {
            AppSettings.secret(this) == null -> "Not paired"
            pcAddress == null -> "Paired, address unknown"
            else -> pcAddress
        }
        pcLinkStatus.text = if (AlarmService.wsConnected) "Connected" else "Disconnected"
        refreshOntoplanoStatus()

        // Don't clobber what the user is part-way through typing.
        if (!ontoplanoPattern.hasFocus()) {
            ontoplanoPattern.setText(AppSettings.ontoplanoPattern(this))
        }
        renderOntoplanoRule()

        updateSensingStatus()

        val bootState = packageManager.getComponentEnabledSetting(
            ComponentName(this, BootReceiver::class.java)
        )
        // DEFAULT means manifest value (now true), so treat as enabled
        bootToggle.isChecked = bootState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || bootState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }

    /**
     * The permissions the phone needs to do the job the PC used to do:
     * BLE scanning for the scale, and location (which is what Android makes you
     * ask for to read the wifi SSID and, below API 31, to see scan results).
     */
    private fun missingRuntimePermissions(): List<String> {
        val wanted = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += android.Manifest.permission.BLUETOOTH_SCAN
            wanted += android.Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += android.Manifest.permission.POST_NOTIFICATIONS
        }
        return wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRuntimePermissions() {
        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            return
        }
        runtimePermissionLauncher.launch(missing.toTypedArray())
    }

    private fun captureHomeNetwork() {
        if (!HomeNetwork.hasPermission(this)) {
            Toast.makeText(
                this,
                "Location permission is needed to read the wifi name",
                Toast.LENGTH_LONG
            ).show()
            requestRuntimePermissions()
            return
        }

        val ssid = HomeNetwork.currentSsid(this)
        if (ssid == null) {
            Toast.makeText(
                this,
                "Not connected to wifi (or the name is unavailable)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        AppSettings.setHomeNetwork(this, ssid, HomeNetwork.currentBssid(this))
        Toast.makeText(this, "Home wifi set to $ssid", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    /**
     * Manual scale listening, outside an alarm — useful for a midday weigh-in
     * and for checking the scale is reachable at all.
     */
    private fun toggleScaleListening() {
        val service = AlarmService.instance
        if (service == null) {
            Toast.makeText(this, "Service is not running", Toast.LENGTH_SHORT).show()
            return
        }

        if (service.isScanningScale()) {
            service.stopScaleScan()
            Toast.makeText(this, "Stopped listening", Toast.LENGTH_SHORT).show()
        } else if (service.startScaleScan()) {
            Toast.makeText(this, "Listening for the scale — step on it", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(
                this,
                "Could not start scanning — check Bluetooth and permissions",
                Toast.LENGTH_LONG
            ).show()
        }
        updateUI()
    }

    /**
     * Which ontoplano tasks become alarms.
     *
     * The rule is chosen here but enforced on the PC — that is the side holding
     * the ontoplano token and doing the fetching — so applying it sends the rule
     * over the same WebSocket the schedule already uses.
     */
    private fun selectOntoplanoKind(kind: String) {
        AppSettings.setOntoplanoRule(this, ontoplanoPattern.text.toString(), kind)
        renderOntoplanoRule()
    }

    private fun applyOntoplanoRule() {
        val pattern = ontoplanoPattern.text.toString().trim()
        val kind = AppSettings.ontoplanoKind(this)

        if (pattern.isNotEmpty()) {
            // Fail here rather than silently sending the PC something it will
            // log a warning about and drop.
            val valid = runCatching { Regex(pattern) }.isSuccess
            if (!valid) {
                ontoplanoStatus.text = "That is not a valid pattern"
                return
            }
        }

        AppSettings.setOntoplanoRule(this, pattern, kind)

        val service = AlarmService.instance
        if (service == null || !AlarmService.wsConnected) {
            ontoplanoStatus.text = "Saved. Will reach the PC when it reconnects."
            return
        }

        service.pushOntoplanoRule(pattern, kind)
        ontoplanoStatus.text = if (pattern.isEmpty()) {
            "Cleared — no ontoplano tasks will become alarms."
        } else {
            "Sent to the PC. Matching tasks appear in Alarms shortly."
        }
        renderOntoplanoRule()
    }

    private fun renderOntoplanoRule() {
        val kind = AppSettings.ontoplanoKind(this)
        ontoplanoKindHard.isSelected = kind == AlarmSchedule.KIND_HARD
        ontoplanoKindSoft.isSelected = kind == AlarmSchedule.KIND_SOFT
    }

    private fun updateSensingStatus() {
        nextAlarmStatus.text = AlarmScheduler.nextAlarmDescription(this)
        nextAlarmDetail.text = AlarmScheduler.nextAlarmDetail(this)
        renderScaleMode()
        renderHomeNetwork()
        renderPermissions()
        renderReadings()
    }

    private fun renderHomeNetwork() {
        val configured = AppSettings.homeSsid(this)
        val home = HomeNetwork.status(this)

        if (configured == null) {
            homeWifiStatus.text = "Not set"
            homeWifiCaption.text =
                "Hard alarms apply everywhere until you set a home network."
            setHomeWifiButton.visibility = View.VISIBLE
            return
        }

        homeWifiStatus.text = configured
        if (home.isHome) {
            homeWifiCaption.text = "You are on it now. Hard alarms apply here."
            // Nothing to do from here, so the button would only be noise.
            setHomeWifiButton.visibility = View.GONE
        } else {
            homeWifiCaption.text = "Currently ${home.reason}. Hard alarms fall back to a button."
            setHomeWifiButton.visibility = View.VISIBLE
        }
    }

    private fun renderPermissions() {
        val missing = missingRuntimePermissions()
        permissionsStatus.text = if (missing.isEmpty()) {
            "All granted"
        } else {
            "${missing.size} missing — hard alarms will fall back to a button"
        }
        grantPermissionsButton.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderReadings() {
        val store = ReadingStore(this)
        val pending = try {
            store.pendingCount()
        } finally {
            store.close()
        }

        val prefs = AppSettings.prefs(this)
        val lastWeight = prefs.getFloat(AppSettings.KEY_LAST_WEIGHT, 0f)
        val lastWeightAt = prefs.getString(AppSettings.KEY_LAST_WEIGHT_AT, null)
        val lastError = prefs.getString(AppSettings.KEY_LAST_UPLOAD_ERROR, null)

        readingsStatus.text = buildString {
            if (lastWeight > 0f && lastWeightAt != null) {
                val at = WeightHistory.parseUtc(lastWeightAt)
                append("Last weigh-in %.1f kg".format(lastWeight))
                if (at != null) append(" · ").append(WeightHistory.formatWhen(at))
            } else {
                append("No weigh-ins recorded yet")
            }
            if (pending > 0) append("\n$pending waiting to upload")
            if (!lastError.isNullOrBlank()) append("\n").append(lastError)
        }

        impedanceNote.text = if (AppSettings.requiresBodyFat(this)) {
            "Body-fat mode records impedance in ohms (typically 300–800 Ω)."
        } else {
            "Weight mode records no impedance, so the Weight tab shows none."
        }

        weighNowButton.text =
            if (AlarmService.instance?.isScanningScale() == true) "Stop listening"
            else "Listen for scale"
    }

    /**
     * Ask the PC who its ontoplano token belongs to.
     *
     * "Connected" alone is not much use when the question is whether it is
     * pointing at the right account — which is exactly what went wrong after a
     * token swap.
     */
    private fun refreshOntoplanoStatus() {
        background.execute {
            val status = fetchSyncStatus()
            runOnUiThread { renderOntoplanoStatus(status) }
        }
    }

    private fun fetchSyncStatus(): JSONObject? {
        val secret = AppSettings.secret(this) ?: return null
        val baseUrl = AppSettings.pcBaseUrl(this) ?: return null
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/sync-status?key=$secret").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else JSONObject(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A JSON null read through optString() comes back as the literal "null",
     * which is how a stray "null" ended up rendered in the status card.
     */
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun renderOntoplanoStatus(status: JSONObject?) {
        if (status == null) {
            ontoplanoAccount.text = "Unknown"
            ontoplanoDetail.text = "Could not reach the PC to ask."
            return
        }

        val enabled = status.optBoolean("ontoplano_enabled", false)
        val user = status.stringOrNull("ontoplano_user")
        val timezone = status.stringOrNull("ontoplano_timezone")
        val baseUrl = status.stringOrNull("ontoplano_base_url")
        val scopes = status.optJSONArray("ontoplano_scopes")
        val pending = status.optInt("pending", 0)
        val synced = status.optInt("synced", 0)
        val lastError = status.stringOrNull("last_error")

        ontoplanoAccount.text = when {
            !enabled -> "Sync is off"
            user != null -> user
            else -> "Connected"
        }

        ontoplanoDetail.text = buildString {
            if (!enabled) {
                append("Set ontoplano.enabled: true in the PC's config.yaml.")
                return@buildString
            }
            baseUrl?.let { append(it) }
            timezone?.let { if (isNotEmpty()) append(" · "); append(it) }
            if (scopes != null && scopes.length() > 0) {
                val list = (0 until scopes.length()).joinToString(", ") { scopes.optString(it) }
                append("\n").append(list)
            }
            append("\n$synced published")
            if (pending > 0) append(", $pending pending")
            if (status.optBoolean("halted", false)) append(" · sync halted")
            lastError?.let { append("\n").append(it) }
        }

        // The PC is authoritative for the rule; mirror it back so the two agree.
        val pattern = status.optString("ontoplano_pattern")
        if (pattern.isNotBlank() && !ontoplanoPattern.hasFocus() &&
            ontoplanoPattern.text.toString() != pattern
        ) {
            ontoplanoPattern.setText(pattern)
            AppSettings.setOntoplanoRule(
                this, pattern, status.optString("ontoplano_kind", "hard")
            )
            renderOntoplanoRule()
        }
    }

    private fun updateWsStatus() {
        val connected = AlarmService.wsConnected
        if (connected) {
            wsStatus.text = "● PC: connected"
            wsStatus.setTextColor(ContextCompat.getColor(this, R.color.ws_connected))
        } else {
            wsStatus.text = "● PC: disconnected"
            wsStatus.setTextColor(ContextCompat.getColor(this, R.color.ws_disconnected))
        }
    }

    private fun selectTab(tab: Tab) {
        val active = ContextCompat.getColor(this, R.color.secondary_accent)
        val inactive = ContextCompat.getColor(this, R.color.text_muted)

        settingsContent.visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE
        alarmsContent.visibility = if (tab == Tab.ALARMS) View.VISIBLE else View.GONE
        weightContent.visibility = if (tab == Tab.WEIGHT) View.VISIBLE else View.GONE

        tabSettings.setColorFilter(if (tab == Tab.SETTINGS) active else inactive)
        tabAlarms.setColorFilter(if (tab == Tab.ALARMS) active else inactive)
        tabWeight.setColorFilter(if (tab == Tab.WEIGHT) active else inactive)

        when (tab) {
            Tab.ALARMS -> renderAlarms()
            Tab.WEIGHT -> loadWeightHistory()
            Tab.SETTINGS -> updateUI()
        }
    }

    // ─── Weight history ──────────────────────────────────────────────

    private fun setWeightRange(days: Int) {
        weightRangeDays = days
        renderWeightHistory()
    }

    private fun loadWeightHistory() {
        background.execute {
            val result = WeightHistory.load(this)
            runOnUiThread {
                weightEntries = result.entries
                weightSource.text = when {
                    result.error != null -> result.error
                    result.entries.isEmpty() -> "No weigh-ins recorded yet"
                    result.fromPc -> "${result.entries.size} weigh-ins from the PC"
                    else -> "${result.entries.size} weigh-ins from this phone"
                }
                renderWeightHistory()
            }
        }
    }

    private fun renderWeightHistory() {
        weightRange30.isSelected = weightRangeDays == 30
        weightRange90.isSelected = weightRangeDays == 90
        weightRangeAll.isSelected = weightRangeDays == 0

        val cutoff = if (weightRangeDays == 0) 0L
        else System.currentTimeMillis() - weightRangeDays * 86_400_000L
        val visible = weightEntries.filter { it.atMillis >= cutoff }

        weightChart.setPoints(visible.map { WeightChartView.Point(it.atMillis, it.weightKg) })
        weightTrend.text = WeightHistory.describeTrend(visible)

        val newest = weightEntries.maxByOrNull { it.atMillis }
        if (newest == null) {
            weightLatest.text = "—"
            weightLatestWhen.text = "No weigh-ins yet"
        } else {
            weightLatest.text = String.format(Locale.US, "%.1f kg", newest.weightKg)
            weightLatestWhen.text = WeightHistory.formatWhen(newest.atMillis)
        }

        weightList.removeAllViews()
        visible.sortedByDescending { it.atMillis }.take(60).forEach { entry ->
            weightList.addView(buildWeightRow(entry))
        }
    }

    private fun buildWeightRow(entry: WeightHistory.Entry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(8) }
        }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(this).apply {
            text = String.format(Locale.US, "%.2f kg", entry.weightKg)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 18f
        })
        left.addView(TextView(this).apply {
            text = buildString {
                append(WeightHistory.formatWhen(entry.atMillis))
                entry.alarmName?.let { append(" · ").append(it) }
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            textSize = 12f
        })
        row.addView(left)

        // Impedance present means the body-fat reading actually completed. It is
        // raw electrical impedance in ohms, not a body-fat percentage — turning
        // it into one needs height, age and sex, which massalarme does not hold.
        row.addView(TextView(this).apply {
            if (entry.impedance != null) {
                text = String.format(Locale.US, "%.0f Ω", entry.impedance)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.kind_hard))
            } else {
                text = "no Ω"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            }
            textSize = 12f
        })

        return row
    }

    // ─── Scale mode ──────────────────────────────────────────────────

    private fun setScaleMode(flag: Int) {
        AppSettings.setStableFlag(this, flag)
        renderScaleMode()
        Toast.makeText(
            this,
            if (flag == AppSettings.FLAG_BODY_FAT) "Hard alarms now wait for the body-fat reading"
            else "Hard alarms now stop as soon as your weight settles",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun renderScaleMode() {
        val bodyFat = AppSettings.requiresBodyFat(this)
        scaleModeWeight.isSelected = !bodyFat
        scaleModeBodyFat.isSelected = bodyFat
        scaleModeCaption.text = if (bodyFat) {
            "Waits for the impedance reading, so the alarm keeps going until the " +
                "scale has your body fat. Needs bare feet."
        } else {
            "Stops as soon as your weight settles. Socks are fine, but no body-fat reading."
        }
    }

    private fun renderAlarms() {
        val prefs = AppSettings.prefs(this)
        val alarms = AlarmSchedule.forDisplay(
            AlarmSchedule.parse(prefs.getString(AppSettings.KEY_ALARMS, null))
        )
        val lastSync = prefs.getLong(AppSettings.KEY_LAST_SYNC, 0L)

        alarmsList.removeAllViews()
        alarmsAdd.visibility = View.VISIBLE

        if (alarms.isEmpty()) {
            alarmsEmpty.visibility = View.VISIBLE
            alarmsScroll.visibility = View.GONE
        } else {
            alarmsEmpty.visibility = View.GONE
            alarmsScroll.visibility = View.VISIBLE
            alarms.forEach { alarmsList.addView(buildAlarmRow(it)) }
        }

        alarmsLastSync.text = when {
            lastSync <= 0L -> "Never synced with the PC"
            else -> "Synced " + SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                .format(Date(lastSync))
        }
    }

    private fun buildAlarmRow(alarm: AlarmSchedule.Alarm): View {
        val row = layoutInflater.inflate(R.layout.item_alarm, alarmsList, false)

        val time: TextView = row.findViewById(R.id.alarm_time)
        val name: TextView = row.findViewById(R.id.alarm_name)
        val days: TextView = row.findViewById(R.id.alarm_days)
        val kindBadge: TextView = row.findViewById(R.id.alarm_kind_badge)
        val originBadge: TextView = row.findViewById(R.id.alarm_origin_badge)
        val enabled: Switch = row.findViewById(R.id.alarm_enabled)

        time.text = alarm.time
        name.text = alarm.name
        days.text = alarm.describeRepeat()
        kindBadge.text = if (alarm.isHard) "HARD" else "SOFT"
        kindBadge.setTextColor(
            ContextCompat.getColor(
                this,
                if (alarm.isHard) R.color.kind_hard else R.color.kind_soft
            )
        )

        // Disabled alarms stay legible but visibly inactive.
        row.alpha = if (alarm.enabled) 1f else 0.45f

        enabled.setOnCheckedChangeListener(null)
        enabled.isChecked = alarm.enabled
        enabled.isEnabled = !alarm.isReadOnly
        enabled.setOnCheckedChangeListener { _, checked ->
            saveAlarm(alarm.copy(enabled = checked, updatedAt = System.currentTimeMillis()))
        }

        if (alarm.isReadOnly) {
            originBadge.visibility = View.VISIBLE
            row.setOnClickListener {
                Toast.makeText(
                    this,
                    "Set by ontoplano. Change the task there, or the match pattern in Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            originBadge.visibility = View.GONE
            row.setOnClickListener { editAlarm(alarm) }
        }

        (row.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )).let {
            it.bottomMargin = dpToPx(10)
            row.layoutParams = it
        }

        return row
    }

    private fun editAlarm(alarm: AlarmSchedule.Alarm) {
        AlarmEditor(this).show(
            existing = alarm,
            onSave = { saveAlarm(it) },
            onDelete = { deleteAlarm(it) }
        )
    }

    private fun showAddAlarmDialog() {
        AlarmEditor(this).show(existing = null, onSave = { saveAlarm(it) })
    }

    /** Persist one alarm, push it to the PC, and rebook the next AlarmManager slot. */
    private fun saveAlarm(alarm: AlarmSchedule.Alarm) {
        val prefs = AppSettings.prefs(this)
        val root = loadAlarmsRoot(prefs.getString(AppSettings.KEY_ALARMS, null))
        val updated = AlarmSchedule.upsert(root, alarm)

        prefs.edit().putString(AppSettings.KEY_ALARMS, updated.toString()).apply()
        sendAlarmsToPC(updated.toString())
        AlarmScheduler.rescheduleNext(this)
        renderAlarms()
        updateSensingStatus()
    }

    private fun deleteAlarm(alarm: AlarmSchedule.Alarm) {
        AlertDialog.Builder(this, R.style.MassalarmeDialog)
            .setTitle("Delete \"${alarm.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                // Tombstone rather than drop, so the PC propagates the deletion
                // instead of syncing the alarm back.
                saveAlarm(
                    alarm.copy(
                        deleted = true,
                        enabled = false,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadAlarmsRoot(raw: String?): JSONObject {
        val root = try {
            if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
        root.put("version", 2)
        if (root.optJSONArray("alarms") == null) {
            root.put("alarms", JSONArray())
        }
        return root
    }


    private fun sendAlarmsToPC(alarmsJson: String) {
        val payload = JSONObject()
            .put("type", "update_alarms")
            .put("data", JSONObject(alarmsJson))
        AlarmService.instance?.sendWsMessage(payload.toString())
    }

    private fun dpToPx(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (Settings.canDrawOverlays(this)) return
        val prefs = getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
        val requested = prefs.getBoolean(KEY_OVERLAY_REQUESTED, false)
        if (requested) return
        prefs.edit().putBoolean(KEY_OVERLAY_REQUESTED, true).apply()
        Toast.makeText(this, "Allow overlay to show alarm dismiss screen", Toast.LENGTH_LONG)
            .show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun launchScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("")
            setBeepEnabled(false)
            setOrientationLocked(false)
            setCaptureActivity(QrScannerActivity::class.java)
        }
        scanLauncher.launch(options)
    }
}
