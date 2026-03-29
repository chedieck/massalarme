package org.example.lanalarm

import android.animation.ObjectAnimator
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AlarmDismissActivity : AppCompatActivity() {

    companion object {
        private const val PASSPHRASE =
            "The Industrial Revolution and its consequences have been a disaster for the human race."
    }

    private lateinit var inputField: EditText
    private lateinit var tryAgainText: TextView
    private var bellPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_alarm_dismiss)
        inputField = findViewById(R.id.passphrase_input)
        tryAgainText = findViewById(R.id.try_again_text)

        AlarmService.instance?.dismissAlarmNotification()

        inputField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val current = s?.toString() ?: return
                if (current == PASSPHRASE) {
                    AlarmService.instance?.stopAlarm()
                    finish()
                    return
                }
                if (current.isNotEmpty() && !PASSPHRASE.startsWith(current)) {
                    onWrongInput()
                }
            }
        })
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
        bellPlayer?.release()
        bellPlayer = null
        super.onDestroy()
    }
}
