package org.example.lanalarm

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The full-screen dismissal, over the lock screen.
 *
 * Two shapes, depending on the alarm:
 *
 *  - **hard** — silenced by standing on the scale. The passphrase is an escape
 *    hatch the user configures and can switch off entirely; being locked out by
 *    a flat scale battery is worse than the occasional cheat, but that is a
 *    judgement they get to make rather than one compiled into the app.
 *  - **soft** — a dismiss button. Used for ordinary reminders, and for hard
 *    alarms that fired away from home where there is no scale to step on.
 *
 * Either can be snoozed, when the user has left snooze switched on.
 */
class AlarmDismissActivity : AppCompatActivity() {

    private lateinit var inputField: EditText
    private lateinit var tryAgainText: TextView
    private lateinit var instructionText: TextView
    private lateinit var alarmNameText: TextView
    private lateinit var scaleStatusText: TextView
    private lateinit var passphraseLabel: TextView
    private lateinit var dismissButton: Button
    private lateinit var snoozeButton: Button
    private lateinit var liveWeight: TextView
    private lateinit var liveWeightCaption: TextView
    private var bellPlayer: MediaPlayer? = null

    private var isHard: Boolean = true
    private var passphrase: String = AppSettings.DEFAULT_PASSPHRASE
    private var passphraseAvailable: Boolean = true

    private val alarmStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            goHome()
        }
    }

    /**
     * The scale, talking. It broadcasts continuously while someone is standing
     * on it, not just once at the end, and showing that is the difference
     * between a screen that looks alive and one that looks broken.
     */
    private val scaleReadingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val weightKg = intent.getDoubleExtra(AlarmService.EXTRA_WEIGHT_KG, 0.0)
            if (weightKg <= 0) return
            showLiveWeight(
                weightKg,
                stabilized = intent.getBooleanExtra(AlarmService.EXTRA_STABILIZED, false),
                hasImpedance = intent.getBooleanExtra(AlarmService.EXTRA_HAS_IMPEDANCE, false)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alarm_dismiss)
        inputField = findViewById(R.id.passphrase_input)
        tryAgainText = findViewById(R.id.try_again_text)
        instructionText = findViewById(R.id.instruction_text)
        alarmNameText = findViewById(R.id.alarm_name_text)
        scaleStatusText = findViewById(R.id.scale_status_text)
        passphraseLabel = findViewById(R.id.passphrase_label)
        dismissButton = findViewById(R.id.dismiss_button)
        snoozeButton = findViewById(R.id.snooze_button)
        liveWeight = findViewById(R.id.live_weight)
        liveWeightCaption = findViewById(R.id.live_weight_caption)

        isHard = AlarmService.activeAlarmIsHard
        passphrase = AppSettings.passphrase(this)
        passphraseAvailable = AppSettings.passphraseEnabled(this)
        alarmNameText.text = AlarmService.activeAlarmName

        applyMode()

        registerNotExported(alarmStoppedReceiver, AlarmService.ACTION_ALARM_STOPPED)
        registerNotExported(scaleReadingReceiver, AlarmService.ACTION_SCALE_READING)

        AlarmService.instance?.dismissAlarmNotification()

        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val current = s?.toString() ?: return
                if (current == passphrase) {
                    AlarmService.instance?.stopAlarm()
                    goHome()
                    return
                }
                if (current.length >= passphrase.length) onWrongInput()
            }
        })

        dismissButton.setOnClickListener {
            AlarmService.instance?.stopAlarm()
            goHome()
        }

        snoozeButton.setOnClickListener { snooze() }
    }

    private fun registerNotExported(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun applyMode() {
        val downgradeReason = AlarmService.activeAlarmDowngradeReason

        snoozeButton.visibility =
            if (AppSettings.snoozeEnabled(this)) View.VISIBLE else View.GONE
        snoozeButton.text = "SNOOZE ${AppSettings.snoozeMinutes(this)} MIN"

        if (isHard) {
            instructionText.text = if (AppSettings.requiresBodyFat(this)) {
                "Stand on the scale, bare feet, until it has your body fat"
            } else {
                "Step on the scale to stop the alarm"
            }
            dismissButton.visibility = View.GONE
            scaleStatusText.text =
                if (AlarmService.instance?.isScanningScale() == true) {
                    "Listening for the scale…"
                } else if (passphraseAvailable) {
                    "Scale listener not running — use the passphrase"
                } else {
                    "Scale listener not running"
                }
        } else {
            instructionText.text = "Tap to dismiss"
            dismissButton.visibility = View.VISIBLE
            scaleStatusText.text = downgradeReason.orEmpty()
        }

        // The passphrase is pointless friction on a soft alarm, and absent
        // entirely when the user has turned it off.
        val showPassphrase = isHard && passphraseAvailable
        passphraseLabel.visibility = if (showPassphrase) View.VISIBLE else View.GONE
        inputField.visibility = if (showPassphrase) View.VISIBLE else View.GONE
    }

    /**
     * Render one advertisement.
     *
     * The caption is the useful half: the number alone does not say whether the
     * alarm is about to stop. In body-fat mode a settled weight is not yet
     * enough, and saying so is what stops the user stepping off one second early
     * and starting the whole thing again.
     */
    private fun showLiveWeight(weightKg: Double, stabilized: Boolean, hasImpedance: Boolean) {
        liveWeight.visibility = View.VISIBLE
        liveWeightCaption.visibility = View.VISIBLE
        liveWeight.text = String.format(Locale.US, "%.2f kg", weightKg)

        val wantsBodyFat = AppSettings.requiresBodyFat(this)
        liveWeightCaption.text = when {
            !stabilized -> "Hold still…"
            hasImpedance -> "Body fat measured"
            wantsBodyFat -> "Weight settled — waiting for body fat, keep your feet bare"
            else -> "Weight settled"
        }
    }

    private fun snooze() {
        val at = AlarmService.instance?.snoozeAlarm()
        if (at == null) {
            // Snooze is off, or the service died under us. Either way, saying
            // nothing would look like a dead button.
            scaleStatusText.text = "Snooze is switched off in Settings"
            return
        }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
        instructionText.text = "Snoozed until $time"
        goHome()
    }

    private fun goHome() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        finish()
    }

    private fun onWrongInput() {
        inputField.setText("")
        shakeView(inputField)
        playBell()
        tryAgainText.visibility = View.VISIBLE
        tryAgainText.postDelayed({ tryAgainText.visibility = View.GONE }, 2000)
    }

    private fun shakeView(view: View) {
        ObjectAnimator.ofFloat(view, "translationX", 0f, 25f, -25f, 20f, -20f, 10f, -10f, 0f)
            .apply {
                duration = 400
                start()
            }
    }

    private fun playBell() {
        bellPlayer?.release()
        try {
            val afd = assets.openFd("bell.mp3")
            bellPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                prepare()
                start()
                setOnCompletionListener { mp -> mp.release() }
            }
            afd.close()
        } catch (_: Exception) {
            // bell.mp3 not provided.
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE -> true
            else -> super.dispatchKeyEvent(event)
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        // Block back button while the alarm is active.
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(alarmStoppedReceiver) }
        runCatching { unregisterReceiver(scaleReadingReceiver) }
        bellPlayer?.release()
        bellPlayer = null
        super.onDestroy()
    }
}
