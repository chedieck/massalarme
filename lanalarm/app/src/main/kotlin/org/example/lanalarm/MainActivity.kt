package org.example.lanalarm

import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_OVERLAY_REQUESTED = "overlay_permission_requested"

        /** What the token field shows once a token is stored. Never the token. */
        private const val TOKEN_MASK = "••••••••••••"
    }

    private lateinit var tabSettings: ImageView
    private lateinit var tabAlarms: ImageView
    private lateinit var tabWeight: ImageView
    private lateinit var settingsContent: View
    private lateinit var alarmsContent: View
    private lateinit var weightContent: View

    // Alarms tab
    private lateinit var alarmsList: LinearLayout
    private lateinit var alarmsEmpty: TextView
    private lateinit var alarmsScroll: ScrollView
    private lateinit var alarmsLastSync: TextView
    private lateinit var alarmsAdd: Button

    // Settings: next alarm
    private lateinit var nextAlarmStatus: TextView
    private lateinit var nextAlarmDetail: TextView
    private lateinit var cancelSnoozeButton: Button

    // Settings: scale
    private lateinit var scaleModeWeight: TextView
    private lateinit var scaleModeBodyFat: TextView
    private lateinit var scaleModeCaption: TextView
    private lateinit var readingsStatus: TextView
    private lateinit var impedanceNote: TextView
    private lateinit var weighNowButton: Button
    private lateinit var liveReading: TextView

    // Settings: dismissal
    private lateinit var passphraseToggle: Switch
    private lateinit var passphraseField: EditText
    private lateinit var snoozeOptions: List<Pair<TextView, Int>>
    private lateinit var dismissalSave: Button

    // Settings: home
    private lateinit var homeWifiStatus: TextView
    private lateinit var homeWifiCaption: TextView
    private lateinit var setHomeWifiButton: Button

    // Settings: ontoplano
    private lateinit var ontoplanoToggle: Switch
    private lateinit var ontoplanoAccount: TextView
    private lateinit var ontoplanoDetail: TextView
    private lateinit var ontoplanoBaseUrl: EditText
    private lateinit var ontoplanoToken: EditText
    private lateinit var ontoplanoSave: Button
    private lateinit var scanSecretButton: Button
    private lateinit var ontoplanoStatus: TextView
    private lateinit var ontoplanoPattern: EditText
    private lateinit var ontoplanoKindHard: TextView
    private lateinit var ontoplanoKindSoft: TextView
    private lateinit var ontoplanoKindCaption: TextView
    private lateinit var ontoplanoRuleSave: Button

    // Settings: permissions and background
    private lateinit var permissionsStatus: TextView
    private lateinit var grantPermissionsButton: Button
    private lateinit var serviceStatus: TextView
    private lateinit var bootToggle: Switch

    // Weight tab
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

    /**
     * The schedule can change without the UI asking — a one-shot retiring after
     * it fires, or an ontoplano sync landing. Without this the list and the
     * "next alarm" headline keep showing whatever was true when the tab was
     * drawn, which reads as an edit that did not take.
     */
    private val alarmsChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderAlarms()
            renderSettings()
        }
    }

    /** Live scale readings, so "Listen for scale" proves the scale is heard. */
    private val scaleReadingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val weightKg = intent.getDoubleExtra(AlarmService.EXTRA_WEIGHT_KG, 0.0)
            if (weightKg <= 0) return
            val stabilized = intent.getBooleanExtra(AlarmService.EXTRA_STABILIZED, false)
            liveReading.visibility = View.VISIBLE
            liveReading.text = String.format(
                Locale.US,
                "%.2f kg %s",
                weightKg,
                if (stabilized) "· settled" else "· settling"
            )
        }
    }

    private enum class Tab { SETTINGS, ALARMS, WEIGHT }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        if (AppSettings.applyProvisioning(this, contents)) {
            Toast.makeText(this, "Connected to ontoplano", Toast.LENGTH_SHORT).show()
            testOntoplano()
        } else {
            Toast.makeText(this, "That QR code was not recognised", Toast.LENGTH_LONG).show()
        }
        renderSettings()
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchScanner() }

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.any { !it.value }) {
            Toast.makeText(
                this,
                "Without these, hard alarms fall back to a dismiss button",
                Toast.LENGTH_LONG
            ).show()
        }
        renderSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        wireUp()
        selectTab(Tab.SETTINGS)
    }

    private fun bindViews() {
        tabSettings = findViewById(R.id.tab_settings)
        tabAlarms = findViewById(R.id.tab_alarms)
        tabWeight = findViewById(R.id.tab_weight)
        settingsContent = findViewById(R.id.settings_content)
        alarmsContent = findViewById(R.id.alarms_content)
        weightContent = findViewById(R.id.weight_content)

        alarmsList = findViewById(R.id.alarms_list)
        alarmsEmpty = findViewById(R.id.alarms_empty)
        alarmsScroll = findViewById(R.id.alarms_scroll)
        alarmsLastSync = findViewById(R.id.alarms_last_sync)
        alarmsAdd = findViewById(R.id.alarms_add)

        nextAlarmStatus = findViewById(R.id.next_alarm_status)
        nextAlarmDetail = findViewById(R.id.next_alarm_detail)
        cancelSnoozeButton = findViewById(R.id.cancel_snooze)

        scaleModeWeight = findViewById(R.id.scale_mode_weight)
        scaleModeBodyFat = findViewById(R.id.scale_mode_bodyfat)
        scaleModeCaption = findViewById(R.id.scale_mode_caption)
        readingsStatus = findViewById(R.id.readings_status)
        impedanceNote = findViewById(R.id.impedance_note)
        weighNowButton = findViewById(R.id.weigh_now)
        liveReading = findViewById(R.id.live_reading)

        passphraseToggle = findViewById(R.id.passphrase_toggle)
        passphraseField = findViewById(R.id.passphrase_field)
        dismissalSave = findViewById(R.id.dismissal_save)
        snoozeOptions = listOf(
            findViewById<TextView>(R.id.snooze_0) to 0,
            findViewById<TextView>(R.id.snooze_5) to 5,
            findViewById<TextView>(R.id.snooze_9) to 9,
            findViewById<TextView>(R.id.snooze_15) to 15
        )

        homeWifiStatus = findViewById(R.id.home_wifi_status)
        homeWifiCaption = findViewById(R.id.home_wifi_caption)
        setHomeWifiButton = findViewById(R.id.set_home_wifi)

        ontoplanoToggle = findViewById(R.id.ontoplano_toggle)
        ontoplanoAccount = findViewById(R.id.ontoplano_account)
        ontoplanoDetail = findViewById(R.id.ontoplano_detail)
        ontoplanoBaseUrl = findViewById(R.id.ontoplano_base_url)
        ontoplanoToken = findViewById(R.id.ontoplano_token)
        ontoplanoSave = findViewById(R.id.ontoplano_save)
        scanSecretButton = findViewById(R.id.scan_secret)
        ontoplanoStatus = findViewById(R.id.ontoplano_status)
        ontoplanoPattern = findViewById(R.id.ontoplano_pattern)
        ontoplanoKindHard = findViewById(R.id.ontoplano_kind_hard)
        ontoplanoKindSoft = findViewById(R.id.ontoplano_kind_soft)
        ontoplanoKindCaption = findViewById(R.id.ontoplano_kind_caption)
        ontoplanoRuleSave = findViewById(R.id.ontoplano_rule_save)

        permissionsStatus = findViewById(R.id.permissions_status)
        grantPermissionsButton = findViewById(R.id.grant_permissions)
        serviceStatus = findViewById(R.id.service_status)
        bootToggle = findViewById(R.id.boot_toggle)

        weightChart = findViewById(R.id.weight_chart)
        weightLatest = findViewById(R.id.weight_latest)
        weightLatestWhen = findViewById(R.id.weight_latest_when)
        weightTrend = findViewById(R.id.weight_trend)
        weightSource = findViewById(R.id.weight_source)
        weightList = findViewById(R.id.weight_list)
        weightRange30 = findViewById(R.id.weight_range_30)
        weightRange90 = findViewById(R.id.weight_range_90)
        weightRangeAll = findViewById(R.id.weight_range_all)
    }

    private fun wireUp() {
        tabSettings.setOnClickListener { selectTab(Tab.SETTINGS) }
        tabAlarms.setOnClickListener { selectTab(Tab.ALARMS) }
        tabWeight.setOnClickListener { selectTab(Tab.WEIGHT) }

        alarmsAdd.setOnClickListener { showAddAlarmDialog() }
        cancelSnoozeButton.setOnClickListener {
            AlarmScheduler.cancelSnooze(this)
            renderSettings()
        }

        scaleModeWeight.setOnClickListener { setScaleMode(AppSettings.FLAG_WEIGHT_ONLY) }
        scaleModeBodyFat.setOnClickListener { setScaleMode(AppSettings.FLAG_BODY_FAT) }
        weighNowButton.setOnClickListener { toggleScaleListening() }

        snoozeOptions.forEach { (view, minutes) ->
            view.setOnClickListener {
                AppSettings.setSnoozeMinutes(this, minutes)
                renderDismissal()
            }
        }
        dismissalSave.setOnClickListener { saveDismissalSettings() }

        setHomeWifiButton.setOnClickListener { captureHomeNetwork() }
        grantPermissionsButton.setOnClickListener { requestRuntimePermissions() }

        ontoplanoToggle.setOnClickListener {
            AppSettings.setOntoplanoEnabled(this, ontoplanoToggle.isChecked)
            renderOntoplano()
            if (ontoplanoToggle.isChecked) testOntoplano()
        }
        ontoplanoSave.setOnClickListener { saveOntoplanoConnection() }
        scanSecretButton.setOnClickListener { requestScan() }
        ontoplanoKindHard.setOnClickListener { selectOntoplanoKind(AlarmSchedule.KIND_HARD) }
        ontoplanoKindSoft.setOnClickListener { selectOntoplanoKind(AlarmSchedule.KIND_SOFT) }
        ontoplanoRuleSave.setOnClickListener { applyOntoplanoRule() }

        weightRange30.setOnClickListener { setWeightRange(30) }
        weightRange90.setOnClickListener { setWeightRange(90) }
        weightRangeAll.setOnClickListener { setWeightRange(0) }

        bootToggle.setOnCheckedChangeListener { _, isChecked ->
            packageManager.setComponentEnabledSetting(
                ComponentName(this, BootReceiver::class.java),
                if (isChecked) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    override fun onResume() {
        super.onResume()
        renderSettings()
        renderAlarms()
        ensureOverlayPermission()

        registerNotExported(alarmsChangedReceiver, AlarmService.ACTION_ALARMS_CHANGED)
        registerNotExported(scaleReadingReceiver, AlarmService.ACTION_SCALE_READING)

        // Opening the app is the one moment the user is definitely present and
        // definitely has a network, so it is the cheapest possible sync trigger
        // — no polling, no wakeups, no background process.
        syncOntoplanoInBackground()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(alarmsChangedReceiver) }
        runCatching { unregisterReceiver(scaleReadingReceiver) }
    }

    private fun registerNotExported(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    // ─── Settings rendering ──────────────────────────────────────────

    private fun renderSettings() {
        nextAlarmStatus.text = AlarmScheduler.nextAlarmDescription(this)
        nextAlarmDetail.text = AlarmScheduler.nextAlarmDetail(this)
        cancelSnoozeButton.visibility =
            if (AlarmScheduler.pendingSnooze(this) != null) View.VISIBLE else View.GONE

        renderScaleMode()
        renderReadings()
        renderDismissal()
        renderHomeNetwork()
        renderOntoplano()
        renderPermissions()

        serviceStatus.text = when {
            AlarmService.instance?.isScanningScale() == true -> "Listening for the scale"
            AlarmService.instance != null -> "Working"
            else -> "Idle"
        }

        val bootState = packageManager.getComponentEnabledSetting(
            ComponentName(this, BootReceiver::class.java)
        )
        // DEFAULT means the manifest value, which is enabled.
        bootToggle.isChecked = bootState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            bootState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }

    private fun renderScaleMode() {
        val bodyFat = AppSettings.requiresBodyFat(this)
        scaleModeWeight.isSelected = !bodyFat
        scaleModeBodyFat.isSelected = bodyFat
        scaleModeCaption.text = if (bodyFat) {
            "The scale reports your body fat. It only manages that with bare feet, " +
                "so the alarm keeps going until the measurement lands — and stays " +
                "going if you stand there in socks."
        } else {
            "The scale gives up on body fat and reports the weight alone, which is " +
                "what happens when you step off. Socks are fine; no body-fat reading."
        }
    }

    private fun setScaleMode(flag: Int) {
        AppSettings.setStableFlag(this, flag)
        renderScaleMode()
        renderReadings()
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

        readingsStatus.text = buildString {
            if (lastWeight > 0f && lastWeightAt != null) {
                append("Last weigh-in %.1f kg".format(lastWeight))
                WeightHistory.parseUtc(lastWeightAt)?.let {
                    append(" · ").append(WeightHistory.formatWhen(it))
                }
            } else {
                append("No weigh-ins recorded yet")
            }
            if (pending > 0) append("\n$pending waiting to publish")
        }

        impedanceNote.text = if (AppSettings.requiresBodyFat(this)) {
            "Records impedance in ohms (typically 300–800 Ω)."
        } else {
            "Records no impedance, so the Weight tab shows none."
        }

        weighNowButton.text =
            if (AlarmService.instance?.isScanningScale() == true) "Stop listening"
            else "Listen for scale"
    }

    private fun renderDismissal() {
        passphraseToggle.isChecked = AppSettings.passphraseEnabled(this)
        if (!passphraseField.hasFocus()) passphraseField.setText(AppSettings.passphrase(this))
        passphraseField.isEnabled = passphraseToggle.isChecked

        val minutes = AppSettings.snoozeMinutes(this)
        snoozeOptions.forEach { (view, value) -> view.isSelected = value == minutes }
    }

    private fun saveDismissalSettings() {
        val phrase = passphraseField.text.toString().trim()
        val enabled = passphraseToggle.isChecked

        if (enabled && phrase.length < 8) {
            // A three-character passphrase is not an escape hatch, it is an off
            // switch you will use half-asleep without deciding to.
            Toast.makeText(
                this,
                "Make it at least 8 characters, or turn the passphrase off",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        AppSettings.setPassphrase(this, phrase, enabled)
        renderDismissal()
        Toast.makeText(this, "Dismissal settings saved", Toast.LENGTH_SHORT).show()
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

    // ─── ontoplano ───────────────────────────────────────────────────

    private fun renderOntoplano() {
        val enabled = AppSettings.ontoplanoEnabled(this)
        ontoplanoToggle.isChecked = enabled

        if (!ontoplanoBaseUrl.hasFocus()) {
            ontoplanoBaseUrl.setText(AppSettings.ontoplanoBaseUrl(this).orEmpty())
        }
        if (!ontoplanoToken.hasFocus()) {
            // The token itself is never rendered back. A field that shows the
            // secret is a field that ends up in a screenshot.
            ontoplanoToken.setText(if (AppSettings.ontoplanoToken(this) != null) TOKEN_MASK else "")
        }
        if (!ontoplanoPattern.hasFocus()) {
            ontoplanoPattern.setText(AppSettings.ontoplanoPattern(this))
        }

        val kind = AppSettings.ontoplanoKind(this)
        ontoplanoKindHard.isSelected = kind == AlarmSchedule.KIND_HARD
        ontoplanoKindSoft.isSelected = kind == AlarmSchedule.KIND_SOFT
        ontoplanoKindCaption.text = if (kind == AlarmSchedule.KIND_HARD) {
            "Planned tasks ring the siren and need the scale. Snooze still works."
        } else {
            "Planned tasks ring gently and dismiss with one tap."
        }

        ontoplanoAccount.text = when {
            !enabled -> "Off"
            AppSettings.ontoplanoClient(this) == null -> "Not configured"
            else -> AppSettings.ontoplanoBaseUrl(this).orEmpty()
        }

        val error = AppSettings.prefs(this).getString(AppSettings.KEY_LAST_UPLOAD_ERROR, null)
        val lastOk = AppSettings.prefs(this).getString(AppSettings.KEY_LAST_UPLOAD_OK, null)
        ontoplanoDetail.text = buildString {
            if (!enabled) {
                append("Publishes weigh-ins and turns planned tasks into alarms. ")
                append("The app works fully without it.")
                return@buildString
            }
            append(Ontoplano.REQUIRED_SCOPES.joinToString(", "))
            if (AppSettings.ontoplanoToken(this@MainActivity) != null &&
                !AppSettings.ontoplanoTokenProtected(this@MainActivity)
            ) {
                // Worth saying out loud rather than pretending.
                append("\nToken stored unencrypted — this device's keystore refused it.")
            }
            lastOk?.let { append("\nLast published ").append(it) }
            error?.let { append("\n").append(it) }
        }
    }

    private fun saveOntoplanoConnection() {
        val baseUrl = ontoplanoBaseUrl.text.toString().trim()
        if (baseUrl.isEmpty()) {
            ontoplanoStatus.text = "Server address is required"
            return
        }

        val typed = ontoplanoToken.text.toString().trim()
        // The mask means "leave the stored token alone", not "the token is dots".
        val token = typed.takeIf { it.isNotEmpty() && it != TOKEN_MASK }
        if (token == null && AppSettings.ontoplanoToken(this) == null) {
            ontoplanoStatus.text = "A token is required"
            return
        }

        AppSettings.setOntoplano(this, baseUrl, token, enabled = true)
        renderOntoplano()
        testOntoplano()
    }

    /**
     * Prove the connection works, and say who it belongs to.
     *
     * "Saved" is not the answer to the question a user is actually asking here,
     * which is whether their alarm will still work tomorrow. `/me` needs no
     * scope, so it is the honest first call.
     */
    private fun testOntoplano() {
        ontoplanoStatus.text = "Checking…"
        background.execute {
            val client = AppSettings.ontoplanoClient(this)
            if (client == null) {
                runOnUiThread { ontoplanoStatus.text = "Not configured" }
                return@execute
            }
            val message = try {
                val me = client.whoami()
                val user = listOf("email", "name", "username")
                    .firstNotNullOfOrNull { me.optString(it).takeIf { v -> v.isNotBlank() } }
                    ?: "connected"
                client.declarePlugin()
                client.declareStream()
                "Connected as $user"
            } catch (e: Ontoplano.Failure) {
                OntoplanoSync.describe(e)
            } catch (e: Exception) {
                e.message ?: "could not reach the server"
            }
            runOnUiThread {
                ontoplanoStatus.text = message
                renderOntoplano()
            }
        }
    }

    private fun selectOntoplanoKind(kind: String) {
        AppSettings.setOntoplanoRule(this, ontoplanoPattern.text.toString(), kind)
        renderOntoplano()
    }

    private fun applyOntoplanoRule() {
        val pattern = ontoplanoPattern.text.toString().trim()
        if (pattern.isNotEmpty() && runCatching { Regex(pattern) }.isFailure) {
            ontoplanoStatus.text = "That is not a valid pattern"
            return
        }

        AppSettings.setOntoplanoRule(this, pattern, AppSettings.ontoplanoKind(this))
        if (pattern.isEmpty()) {
            ontoplanoStatus.text = "Cleared — no planned task will become an alarm."
            return
        }
        syncOntoplanoInBackground(announce = true)
    }

    /**
     * Run a full sync without the service.
     *
     * The activity is alive and the user is watching, so there is no reason to
     * start a background process to do it — that was the old shape, and it is
     * what kept a service running all day.
     */
    private fun syncOntoplanoInBackground(announce: Boolean = false) {
        if (AppSettings.ontoplanoClient(this) == null) return
        if (announce) ontoplanoStatus.text = "Syncing…"

        background.execute {
            val outcome = OntoplanoSync.run(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (announce) {
                    ontoplanoStatus.text = if (outcome.ok) {
                        "Synced · ${outcome.uploaded} published · " +
                            "${outcome.alarmsDerived} alarm(s) from tasks"
                    } else {
                        outcome.error.orEmpty()
                    }
                }
                renderAlarms()
                renderSettings()
            }
        }
    }

    // ─── Permissions and provisioning ────────────────────────────────

    /**
     * The permissions the phone needs to do the job the PC used to do: BLE
     * scanning for the scale, and location (which is what Android makes you ask
     * for to read the wifi SSID and, below API 31, to see scan results).
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
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRuntimePermissions() {
        val missing = missingRuntimePermissions()
        if (missing.isEmpty()) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            return
        }
        runtimePermissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestScan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchScanner()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
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
        renderSettings()
    }

    /**
     * Manual scale listening, outside an alarm — useful for a midday weigh-in
     * and for checking the scale is reachable at all.
     *
     * This and a ringing hard alarm are the only two things that ever turn the
     * Bluetooth radio on. Everything else in the app is asleep.
     */
    private fun toggleScaleListening() {
        val service = AlarmService.instance
        if (service != null && service.isScanningScale()) {
            startService(AlarmService.ACTION_STOP_LISTENING)
            liveReading.visibility = View.GONE
            Toast.makeText(this, "Stopped listening", Toast.LENGTH_SHORT).show()
        } else {
            liveReading.visibility = View.GONE
            startService(AlarmService.ACTION_LISTEN_SCALE)
            Toast.makeText(this, "Listening for the scale — step on it", Toast.LENGTH_LONG).show()
        }
        weighNowButton.postDelayed({ renderReadings(); renderSettings() }, 600)
    }

    private fun startService(action: String) {
        val intent = Intent(this, AlarmService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ─── Tabs ────────────────────────────────────────────────────────

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
            Tab.SETTINGS -> renderSettings()
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
                if (isFinishing || isDestroyed) return@runOnUiThread
                weightEntries = result.entries
                weightSource.text = when {
                    result.error != null -> result.error
                    result.entries.isEmpty() -> "No weigh-ins recorded yet"
                    else -> "${result.entries.size} weigh-ins"
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

    // ─── Alarms ──────────────────────────────────────────────────────

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
            !AppSettings.ontoplanoEnabled(this) -> ""
            lastSync <= 0L -> "Never synced with ontoplano"
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

    /** Persist one alarm and rebook the next AlarmManager slot. */
    private fun saveAlarm(alarm: AlarmSchedule.Alarm) {
        val prefs = AppSettings.prefs(this)
        val root = loadAlarmsRoot(prefs.getString(AppSettings.KEY_ALARMS, null))
        val updated = AlarmSchedule.pruneTombstones(AlarmSchedule.upsert(root, alarm))

        prefs.edit().putString(AppSettings.KEY_ALARMS, updated.toString()).apply()
        AlarmScheduler.rescheduleNext(this)
        renderAlarms()
        renderSettings()
    }

    private fun deleteAlarm(alarm: AlarmSchedule.Alarm) {
        AlertDialog.Builder(this, R.style.MassalarmeDialog)
            .setTitle("Delete \"${alarm.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                // Tombstone rather than drop, so an ontoplano re-sync does not
                // resurrect an alarm the user deleted.
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
        val root = runCatching { if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw) }
            .getOrDefault(JSONObject())
        root.put("version", 2)
        if (root.optJSONArray("alarms") == null) root.put("alarms", JSONArray())
        return root
    }

    // ─── Odds and ends ───────────────────────────────────────────────

    private fun dpToPx(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun ensureOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (Settings.canDrawOverlays(this)) return
        val prefs = AppSettings.prefs(this)
        if (prefs.getBoolean(KEY_OVERLAY_REQUESTED, false)) return
        prefs.edit().putBoolean(KEY_OVERLAY_REQUESTED, true).apply()
        Toast.makeText(this, "Allow overlay to show the alarm screen", Toast.LENGTH_LONG).show()
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
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
