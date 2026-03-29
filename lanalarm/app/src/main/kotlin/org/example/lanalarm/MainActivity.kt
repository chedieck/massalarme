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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_OVERLAY_REQUESTED = "overlay_permission_requested"
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

        val bootEnabled = packageManager.getComponentEnabledSetting(
            ComponentName(this, BootReceiver::class.java)
        ) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        bootToggle.isChecked = bootEnabled
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

        val items = if (alarmsJson.isNullOrBlank()) {
            emptyList()
        } else {
            parseAlarmsJson(alarmsJson)
        }

        if (items.isEmpty()) {
            alarmsEmpty.visibility = View.VISIBLE
            alarmsScroll.visibility = View.GONE
        } else {
            var lastLabel: String? = null
            for (item in items) {
                val sectionLabel = when (item.category) {
                    "date" -> "Date"
                    "next" -> "Next"
                    else -> item.category.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                }
                if (lastLabel != sectionLabel) {
                    alarmsList.addView(buildSectionHeader(sectionLabel, lastLabel == null))
                    lastLabel = sectionLabel
                }
                alarmsList.addView(buildAlarmRow(item))
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
        val label: String,
        val time: String,
        val name: String,
        val category: String,
        val index: Int
    )

    private fun parseAlarmsJson(raw: String): List<AlarmItem> {
        return try {
            val root = JSONObject(raw)
            val items = mutableListOf<AlarmItem>()
            val days = listOf(
                "monday",
                "tuesday",
                "wednesday",
                "thursday",
                "friday",
                "saturday",
                "sunday"
            )

            for (day in days) {
                val label = day.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                addAlarmItems(items, root.optJSONArray(day), label, day) { label }
            }

            addAlarmItems(items, root.optJSONArray("date"), "Date", "date") { obj ->
                obj.optString("date", "Date")
            }

            addAlarmItems(items, root.optJSONArray("next"), "Next", "next") { _ -> "Next" }

            items
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to parse alarms", Toast.LENGTH_SHORT).show()
            emptyList()
        }
    }

    private fun addAlarmItems(
        items: MutableList<AlarmItem>,
        arr: JSONArray?,
        fallbackLabel: String,
        category: String,
        labelProvider: (JSONObject) -> String
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val time = obj.optString("time", "")
            val name = obj.optString("name", "")
            val label = labelProvider(obj).ifBlank { fallbackLabel }
            if (time.isBlank() && name.isBlank()) continue
            items.add(
                AlarmItem(
                    label,
                    time,
                    if (name.isNotBlank()) name else label,
                    category,
                    i
                )
            )
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
        }

        content.addView(nameInput)
        content.addView(timeInput)

        if (item.category == "date") {
            val prefs = getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
            val raw = prefs.getString(AlarmService.KEY_ALARMS, "{}") ?: "{}"
            val root = JSONObject(raw)
            val arr = root.optJSONArray("date")
            val obj = arr?.optJSONObject(item.index)
            dateInput.setText(obj?.optString("date", "") ?: "")
            content.addView(dateInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit alarm")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                val prefs = getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
                val raw = prefs.getString(AlarmService.KEY_ALARMS, "{}") ?: "{}"
                val root = JSONObject(raw)
                val arr = root.optJSONArray(item.category) ?: JSONArray()
                val obj = arr.optJSONObject(item.index) ?: JSONObject()
                obj.put("name", nameInput.text.toString().trim())
                obj.put("time", timeInput.text.toString().trim())
                if (item.category == "date") {
                    obj.put("date", dateInput.text.toString().trim())
                }
                if (arr.length() <= item.index) {
                    arr.put(obj)
                } else {
                    arr.put(item.index, obj)
                }
                root.put(item.category, arr)
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
                val raw = prefs.getString(AlarmService.KEY_ALARMS, "{}") ?: "{}"
                val root = JSONObject(raw)
                val arr = root.optJSONArray(item.category)
                if (arr != null && item.index in 0 until arr.length()) {
                    arr.remove(item.index)
                    root.put(item.category, arr)
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

        val categories = listOf(
            "Monday",
            "Tuesday",
            "Wednesday",
            "Thursday",
            "Friday",
            "Saturday",
            "Sunday",
            "Date",
            "Next"
        )

        val categorySpinner = Spinner(this)
        categorySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            categories
        )

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

        categorySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                dateInput.visibility = if (categories[position] == "Date") View.VISIBLE else View.GONE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        content.addView(categorySpinner)
        content.addView(nameInput)
        content.addView(timeInput)
        content.addView(dateInput)

        AlertDialog.Builder(this)
            .setTitle("Add alarm")
            .setView(content)
            .setPositiveButton("Add") { _, _ ->
                val prefs = getSharedPreferences(AlarmService.PREFS_NAME, MODE_PRIVATE)
                val raw = prefs.getString(AlarmService.KEY_ALARMS, "{}") ?: "{}"
                val root = JSONObject(raw)
                val selected = categories[categorySpinner.selectedItemPosition]
                val categoryKey = selected.lowercase(Locale.getDefault())
                val arr = root.optJSONArray(categoryKey) ?: JSONArray()
                val obj = JSONObject().apply {
                    put("name", nameInput.text.toString().trim())
                    put("time", timeInput.text.toString().trim())
                    if (categoryKey == "date") {
                        put("date", dateInput.text.toString().trim())
                    }
                }
                arr.put(obj)
                root.put(categoryKey, arr)
                prefs.edit().putString(AlarmService.KEY_ALARMS, root.toString()).apply()
                sendAlarmsToPC(root.toString())
                renderAlarms()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
