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
    private lateinit var tabSettings: TextView
    private lateinit var tabAlarms: TextView
    private lateinit var settingsContent: View
    private lateinit var alarmsContent: View
    private lateinit var alarmsList: LinearLayout
    private lateinit var alarmsEmpty: TextView
    private lateinit var alarmsScroll: ScrollView
    private lateinit var alarmsLastSync: TextView
    private lateinit var alarmsAdd: Button
    private lateinit var wsStatus: TextView

    private val wsStatusRunnable = object : Runnable {
        override fun run() {
            updateWsStatus()
            wsStatus.postDelayed(this, 3000)
        }
    }

    private enum class Tab {
        SETTINGS,
        ALARMS
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(AlarmService.KEY_SECRET, result.contents)
                .apply()
            Toast.makeText(this, "Secret saved", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScanner()
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
        serviceStatus.text = if (running) "Service: Running" else "Service: Stopped"
        serviceToggle.text = if (running) "Stop Service" else "Start Service"

        val hasSecret = !getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
            .getString(AlarmService.KEY_SECRET, null).isNullOrEmpty()
        secretStatus.text = if (hasSecret) "Secret: Configured" else "Secret: Not set"

        val bootState = packageManager.getComponentEnabledSetting(
            ComponentName(this, BootReceiver::class.java)
        )
        // DEFAULT means manifest value (now true), so treat as enabled
        bootToggle.isChecked = bootState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || bootState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
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
        val activeColor = ContextCompat.getColor(this, R.color.secondary_accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_muted)
        if (tab == Tab.SETTINGS) {
            settingsContent.visibility = View.VISIBLE
            alarmsContent.visibility = View.GONE
            tabSettings.setTextColor(activeColor)
            tabAlarms.setTextColor(inactiveColor)
        } else {
            settingsContent.visibility = View.GONE
            alarmsContent.visibility = View.VISIBLE
            tabSettings.setTextColor(inactiveColor)
            tabAlarms.setTextColor(activeColor)
            renderAlarms()
        }
    }

    private fun renderAlarms() {
        val prefs = getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
        val alarmsJson = prefs.getString(AlarmService.KEY_ALARMS, null)
        val lastSync = prefs.getLong(AlarmService.KEY_LAST_SYNC, 0L)

        alarmsList.removeAllViews()
        alarmsEmpty.visibility = View.GONE
        alarmsScroll.visibility = View.VISIBLE
        alarmsAdd.visibility = if (AlarmService.wsConnected) View.VISIBLE else View.GONE

        val items = if (alarmsJson.isNullOrBlank()) {
            emptyList()
        } else {
            parseAlarmsJson(alarmsJson)
        }

        if (items.isEmpty()) {
            alarmsEmpty.visibility = View.VISIBLE
            alarmsScroll.visibility = View.GONE
        } else {
            val grouped = items.groupBy { it.alarmType }
            val order = listOf("weekly", "date", "next")
            var isFirstSection = true
            for (type in order) {
                val sectionItems = grouped[type].orEmpty().let { current ->
                    if (type == "weekly") current.sortedBy { it.time } else current
                }
                if (sectionItems.isEmpty()) continue
                val sectionLabel = when (type) {
                    "date" -> "Date"
                    "next" -> "Next"
                    else -> "Weekly"
                }
                alarmsList.addView(buildSectionHeader(sectionLabel, isFirstSection))
                isFirstSection = false
                for (item in sectionItems) {
                    alarmsList.addView(buildAlarmRow(item))
                }
            }
        }

        alarmsLastSync.text = if (lastSync > 0L) {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            "Last synced: ${fmt.format(Date(lastSync))}"
        } else {
            "Last synced: —"
        }
    }

    private data class AlarmItem(
        val id: String,
        val time: String,
        val name: String,
        val alarmType: String,
        val days: List<String> = emptyList(),
        val date: String? = null,
        val enabled: Boolean = true,
        val updatedAt: Long = 0L
    ) {
        val label: String
            get() = when (alarmType) {
                "date" -> date.orEmpty().ifBlank { "Date" }
                "next" -> "Next"
                else -> days
                    .sortedBy { WEEKDAY_KEYS.indexOf(it) }
                    .map { day ->
                        val index = WEEKDAY_KEYS.indexOf(day)
                        if (index >= 0) WEEKDAY_LABELS[index]
                        else day.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                    }
                    .ifEmpty { listOf("Weekly") }
                    .joinToString(", ")
            }

        val category: String
            get() = alarmType
    }

    private fun parseAlarmsJson(raw: String): List<AlarmItem> {
        return try {
            val root = JSONObject(raw)
            val items = mutableListOf<AlarmItem>()
            val alarms = root.optJSONArray("alarms") ?: JSONArray()

            for (i in 0 until alarms.length()) {
                val obj = alarms.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val time = obj.optString("time", "")
                val name = obj.optString("name", "")
                val days = jsonArrayToStringList(obj.optJSONArray("days"))
                    .map { it.lowercase(Locale.getDefault()) }
                    .filter { it in WEEKDAY_KEYS }
                val date = obj.optString("date", "").ifBlank { null }
                val alarmType = when {
                    obj.optString("type") == "next" -> "next"
                    date != null -> "date"
                    else -> "weekly"
                }
                if (id.isBlank() || (time.isBlank() && name.isBlank())) continue
                items.add(
                    AlarmItem(
                        id = id,
                        time = time,
                        name = if (name.isNotBlank()) name else when (alarmType) {
                            "date" -> date ?: "Date"
                            "next" -> "Next"
                            else -> "Weekly"
                        },
                        alarmType = alarmType,
                        days = if (alarmType == "weekly") days else emptyList(),
                        date = date,
                        enabled = obj.optBoolean("enabled", true),
                        updatedAt = obj.optLong("updated_at", 0L)
                    )
                )
            }

            items
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to parse alarms", Toast.LENGTH_SHORT).show()
            emptyList()
        }
    }

    private fun buildAlarmRow(item: AlarmItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_surface))
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(12)
            }
        }

        val left = TextView(this).apply {
            text = item.label
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }

        val time = TextView(this).apply {
            text = item.time
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.secondary_accent))
            textSize = 22f
        }

        val name = TextView(this).apply {
            text = item.name
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 14f
        }

        right.addView(time)
        right.addView(name)
        row.addView(left)
        row.addView(right)
        row.setOnClickListener { showEditAlarmDialog(item) }
        row.setOnLongClickListener {
            showDeleteAlarmDialog(item)
            true
        }
        return row
    }

    private fun buildSectionHeader(label: String, isFirst: Boolean): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (!isFirst) {
                    topMargin = dpToPx(16)
                }
                bottomMargin = dpToPx(8)
            }
        }

        val text = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.secondary_accent))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val divider = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(1)
            ).apply {
                topMargin = dpToPx(6)
            }
        }

        container.addView(text)
        container.addView(divider)
        return container
    }

    private fun showEditAlarmDialog(item: AlarmItem) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(8))
        }

        val typeOptions = listOf("Weekly", "Date", "Next")
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                typeOptions
            )
            setSelection(
                when (item.alarmType) {
                    "date" -> 1
                    "next" -> 2
                    else -> 0
                }
            )
        }

        val nameInput = EditText(this).apply {
            hint = "Name"
            setText(item.name)
        }

        val timeInput = EditText(this).apply {
            hint = "HH:MM or HH:MM:SS"
            setText(item.time)
        }

        val dateInput = EditText(this).apply {
            hint = "DD-MM-YYYY"
            setText(item.date.orEmpty())
        }

        val weekdaysLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val dayCheckboxes = WEEKDAY_KEYS.mapIndexed { index, day ->
            CheckBox(this).apply {
                text = WEEKDAY_LABELS[index]
                isChecked = day in item.days
            }.also { weekdaysLayout.addView(it) }
        }

        fun updateTypeVisibility(position: Int) {
            weekdaysLayout.visibility = if (position == 0) View.VISIBLE else View.GONE
            dateInput.visibility = if (position == 1) View.VISIBLE else View.GONE
        }

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                updateTypeVisibility(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        content.addView(typeSpinner)
        content.addView(nameInput)
        content.addView(timeInput)
        content.addView(weekdaysLayout)
        content.addView(dateInput)
        updateTypeVisibility(typeSpinner.selectedItemPosition)

        AlertDialog.Builder(this)
            .setTitle("Edit alarm")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                val prefs = getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
                val root = loadAlarmsRoot(
                    prefs.getString(AlarmService.KEY_ALARMS, null)
                )
                val arr = root.optJSONArray("alarms") ?: JSONArray()
                val selectedType = typeOptions[typeSpinner.selectedItemPosition].lowercase(Locale.getDefault())
                val updatedAlarm = buildAlarmObject(
                    id = item.id,
                    type = selectedType,
                    name = nameInput.text.toString().trim(),
                    time = timeInput.text.toString().trim(),
                    selectedDays = selectedWeekdays(dayCheckboxes),
                    date = dateInput.text.toString().trim(),
                    enabled = item.enabled,
                    updatedAt = System.currentTimeMillis()
                )

                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (obj.optString("id") == item.id) {
                        arr.put(i, updatedAlarm)
                        break
                    }
                }
                root.put("alarms", arr)
                prefs.edit().putString(AlarmService.KEY_ALARMS, root.toString()).apply()
                sendAlarmsToPC(root.toString())
                renderAlarms()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAlarmDialog(item: AlarmItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete alarm")
            .setMessage("Delete alarm '${item.name}' at ${item.time}?")
            .setPositiveButton("Delete") { _, _ ->
                val prefs = getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
                val root = loadAlarmsRoot(prefs.getString(AlarmService.KEY_ALARMS, null))
                val arr = root.optJSONArray("alarms")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (obj.optString("id") == item.id) {
                            arr.remove(i)
                            break
                        }
                    }
                    root.put("alarms", arr)
                    prefs.edit().putString(AlarmService.KEY_ALARMS, root.toString()).apply()
                    sendAlarmsToPC(root.toString())
                    renderAlarms()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddAlarmDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(12), dpToPx(20), dpToPx(8))
        }

        val typeOptions = listOf("Weekly", "Date", "Next")
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                typeOptions
            )
        }

        val nameInput = EditText(this).apply {
            hint = "Name"
        }

        val timeInput = EditText(this).apply {
            hint = "HH:MM or HH:MM:SS"
        }

        val dateInput = EditText(this).apply {
            hint = "DD-MM-YYYY"
            visibility = View.GONE
        }

        val weekdaysLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val dayCheckboxes = WEEKDAY_KEYS.mapIndexed { index, _ ->
            CheckBox(this).apply {
                text = WEEKDAY_LABELS[index]
            }.also { weekdaysLayout.addView(it) }
        }

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                weekdaysLayout.visibility = if (position == 0) View.VISIBLE else View.GONE
                dateInput.visibility = if (position == 1) View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        content.addView(typeSpinner)
        content.addView(nameInput)
        content.addView(timeInput)
        content.addView(weekdaysLayout)
        content.addView(dateInput)

        AlertDialog.Builder(this)
            .setTitle("Add alarm")
            .setView(content)
            .setPositiveButton("Add") { _, _ ->
                val prefs = getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
                val root = loadAlarmsRoot(prefs.getString(AlarmService.KEY_ALARMS, null))
                val arr = root.optJSONArray("alarms") ?: JSONArray()
                val selectedType = typeOptions[typeSpinner.selectedItemPosition].lowercase(Locale.getDefault())
                arr.put(
                    buildAlarmObject(
                        id = UUID.randomUUID().toString().take(8),
                        type = selectedType,
                        name = nameInput.text.toString().trim(),
                        time = timeInput.text.toString().trim(),
                        selectedDays = selectedWeekdays(dayCheckboxes),
                        date = dateInput.text.toString().trim(),
                        enabled = true,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                root.put("alarms", arr)
                prefs.edit().putString(AlarmService.KEY_ALARMS, root.toString()).apply()
                sendAlarmsToPC(root.toString())
                renderAlarms()
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

    private fun buildAlarmObject(
        id: String,
        type: String,
        name: String,
        time: String,
        selectedDays: List<String>,
        date: String,
        enabled: Boolean,
        updatedAt: Long
    ): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("time", time)
            put("enabled", enabled)
            put("updated_at", updatedAt)
            when (type) {
                "date" -> put("date", date)
                "next" -> put("type", "next")
                else -> put("days", JSONArray(selectedDays))
            }
        }
    }

    private fun selectedWeekdays(dayCheckboxes: List<CheckBox>): List<String> {
        return dayCheckboxes.mapIndexedNotNull { index, checkBox ->
            if (checkBox.isChecked) WEEKDAY_KEYS[index] else null
        }
    }

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val value = arr.optString(i, "")
                if (value.isNotBlank()) add(value)
            }
        }
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
