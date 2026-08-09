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

/**
 * The full-screen dismissal, over the lock screen.
 *
 * Two shapes, depending on the alarm:
 *
 *  - **hard** — silenced by standing on the scale. The passphrase stays as an
 *    escape hatch, because being locked out by a flat scale battery is worse
 *    than the occasional cheat.
 *  - **soft** — a dismiss button. Used for ordinary reminders, and for hard
 *    alarms that fired away from home where there is no scale to step on.
 */
class AlarmDismissActivity : AppCompatActivity() {

    companion object {
        private const val PASSPHRASE =
            "The Industrial Revolution and its consequences have been a disaster for the human race."
    }

    private lateinit var inputField: EditText
    private lateinit var tryAgainText: TextView
    private lateinit var instructionText: TextView
    private lateinit var alarmNameText: TextView
    private lateinit var scaleStatusText: TextView
    private lateinit var passphraseLabel: TextView
    private lateinit var dismissButton: Button
    private var bellPlayer: MediaPlayer? = null

    private var isHard: Boolean = true

    private val alarmStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            goHome()
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

        isHard = AlarmService.activeAlarmIsHard
        alarmNameText.text = AlarmService.activeAlarmName

        applyMode()

        val filter = IntentFilter(AlarmService.ACTION_ALARM_STOPPED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(alarmStoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(alarmStoppedReceiver, filter)
        }

        AlarmService.instance?.dismissAlarmNotification()

        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val current = s?.toString() ?: return
                if (current == PASSPHRASE) {
                    AlarmService.instance?.stopAlarm()
                    goHome()
                    return
                }
                if (current.length >= PASSPHRASE.length) {
                    onWrongInput()
                }
            }
        })

        dismissButton.setOnClickListener {
            AlarmService.instance?.stopAlarm()
            goHome()
        }
    }

    private fun applyMode() {
        val downgradeReason = AlarmService.activeAlarmDowngradeReason

        if (isHard) {
            instructionText.text = "Step on the scale to stop the alarm"
            dismissButton.visibility = View.GONE
            passphraseLabel.visibility = View.VISIBLE
            inputField.visibility = View.VISIBLE
            scaleStatusText.text =
                if (AlarmService.instance?.isScanningScale() == true) {
                    "Listening for the scale…"
                } else {
                    "Scale listener not running — use the passphrase"
                }
        } else {
            instructionText.text = "Tap to dismiss"
            dismissButton.visibility = View.VISIBLE
            // No scale required, so the passphrase would be pointless friction.
            passphraseLabel.visibility = View.GONE
            inputField.visibility = View.GONE
            scaleStatusText.text = downgradeReason.orEmpty()
        }
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
            // bell.mp3 not yet provided by user
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
        // Block back button while alarm is active
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(alarmStoppedReceiver)
        } catch (_: IllegalArgumentException) {}
        bellPlayer?.release()
        bellPlayer = null
        super.onDestroy()
    }
}
