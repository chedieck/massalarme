package org.example.lanalarm

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var serviceStatus: TextView
    private lateinit var secretStatus: TextView
    private lateinit var serviceToggle: Button
    private lateinit var scanSecretButton: Button
    private lateinit var bootToggle: Switch

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
    }

    override fun onResume() {
        super.onResume()
        updateUI()
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
