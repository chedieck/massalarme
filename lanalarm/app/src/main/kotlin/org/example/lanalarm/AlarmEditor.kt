package org.example.lanalarm

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * The add/edit alarm sheet.
 *
 * The old version asked you to type "HH:MM" into a text box, pick a type from a
 * spinner, and tick seven default checkboxes laid out in a row that ran off the
 * side of the screen. This one:
 *
 *  - opens the system clock picker for the time,
 *  - lays the seven days out as equal-weight circles, so none can fall off,
 *  - infers the repeat type from what you selected instead of asking separately
 *    (no days = once, days = weekly, a date = that date),
 *  - offers the three repeats people actually want as one-tap presets,
 *  - states in words what the alarm will do, so there is nothing to guess.
 */
class AlarmEditor(private val activity: AppCompatActivity) {

    private data class Draft(
        var hour: Int,
        var minute: Int,
        var days: MutableSet<String>,
        var date: String?,
        var kind: String
    )

    fun show(
        existing: AlarmSchedule.Alarm?,
        onSave: (AlarmSchedule.Alarm) -> Unit,
        onDelete: ((AlarmSchedule.Alarm) -> Unit)? = null
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_alarm_edit, null)

        val timeView: TextView = view.findViewById(R.id.edit_time)
        val nameInput: EditText = view.findViewById(R.id.edit_name)
        val daysRow: LinearLayout = view.findViewById(R.id.edit_days)
        val presetsRow: LinearLayout = view.findViewById(R.id.edit_presets)
        val repeatSummary: TextView = view.findViewById(R.id.edit_repeat_summary)
        val hardChip: TextView = view.findViewById(R.id.edit_kind_hard)
        val softChip: TextView = view.findViewById(R.id.edit_kind_soft)
        val kindSummary: TextView = view.findViewById(R.id.edit_kind_summary)

        val parsed = existing?.let { AlarmSchedule.parseTime(it.time) }
        val draft = Draft(
            hour = parsed?.first ?: 7,
            minute = parsed?.second ?: 0,
            days = existing?.days?.toMutableSet() ?: mutableSetOf(),
            date = existing?.date,
            kind = existing?.kind ?: AlarmSchedule.KIND_HARD
        )

        nameInput.setText(existing?.name.orEmpty())

        val dayViews = buildDayToggles(daysRow)
        lateinit var refresh: () -> Unit

        fun renderDays() {
            AlarmSchedule.WEEKDAYS.forEachIndexed { index, day ->
                val selected = day in draft.days && draft.date == null
                dayViews[index].isSelected = selected
                dayViews[index].setTextColor(
                    ContextCompat.getColor(
                        activity,
                        if (selected) R.color.bg_dark else R.color.text_muted
                    )
                )
            }
        }

        refresh = {
            timeView.text = String.format(Locale.US, "%02d:%02d", draft.hour, draft.minute)
            renderDays()

            hardChip.isSelected = draft.kind == AlarmSchedule.KIND_HARD
            softChip.isSelected = draft.kind == AlarmSchedule.KIND_SOFT
            kindSummary.text = if (draft.kind == AlarmSchedule.KIND_HARD) {
                "Only stops once you stand on the scale. Away from your home wifi " +
                    "it falls back to a button, since the scale is not there."
            } else {
                "Stops with one tap, and uses the gentler tone."
            }

            repeatSummary.text = describe(draft)
        }

        dayViews.forEachIndexed { index, chip ->
            chip.setOnClickListener {
                val day = AlarmSchedule.WEEKDAYS[index]
                // Choosing a weekday means this is not a one-off date any more.
                draft.date = null
                if (!draft.days.add(day)) draft.days.remove(day)
                refresh()
            }
        }

        timeView.setOnClickListener {
            TimePickerDialog(
                activity,
                R.style.MassalarmePicker,
                { _, hour, minute ->
                    draft.hour = hour
                    draft.minute = minute
                    refresh()
                },
                draft.hour,
                draft.minute,
                true // massalarme shows 24h everywhere else
            ).show()
        }

        buildPresets(presetsRow, draft) { refresh() }

        hardChip.setOnClickListener { draft.kind = AlarmSchedule.KIND_HARD; refresh() }
        softChip.setOnClickListener { draft.kind = AlarmSchedule.KIND_SOFT; refresh() }

        refresh()

        val builder = AlertDialog.Builder(activity, R.style.MassalarmeDialog)
            .setTitle(if (existing == null) "New alarm" else "Edit alarm")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                onSave(
                    AlarmSchedule.Alarm(
                        id = existing?.id ?: UUID.randomUUID().toString().take(8),
                        name = name.ifBlank { defaultName(draft) },
                        time = String.format(Locale.US, "%02d:%02d", draft.hour, draft.minute),
                        days = draft.days.sortedBy { AlarmSchedule.WEEKDAYS.indexOf(it) },
                        date = draft.date,
                        type = if (draft.days.isEmpty() && draft.date == null) "next" else null,
                        enabled = existing?.enabled ?: true,
                        deleted = false,
                        kind = draft.kind,
                        updatedAt = System.currentTimeMillis(),
                        origin = existing?.origin,
                        originId = existing?.originId
                    )
                )
            }
            .setNegativeButton("Cancel", null)

        if (existing != null && onDelete != null) {
            builder.setNeutralButton("Delete") { _, _ -> onDelete(existing) }
        }

        builder.show()
    }

    // ─── Pieces ──────────────────────────────────────────────────────

    private fun buildDayToggles(row: LinearLayout): List<TextView> {
        row.removeAllViews()
        return AlarmSchedule.WEEKDAY_INITIALS.mapIndexed { index, initial ->
            TextView(activity).apply {
                text = initial
                gravity = Gravity.CENTER
                textSize = 15f
                setBackgroundResource(R.drawable.day_toggle)
                // Equal weight is what guarantees Sunday stays on screen.
                layoutParams = LinearLayout.LayoutParams(
                    0, dp(44), 1f
                ).apply {
                    marginStart = if (index == 0) 0 else dp(6)
                }
            }.also { row.addView(it) }
        }
    }

    private fun buildPresets(row: LinearLayout, draft: Draft, onChange: () -> Unit) {
        row.removeAllViews()

        data class Preset(val label: String, val apply: () -> Unit)

        val presets = listOf(
            Preset("Once") { draft.days.clear(); draft.date = null },
            Preset("Every day") {
                draft.days = AlarmSchedule.WEEKDAYS.toMutableSet(); draft.date = null
            },
            Preset("Weekdays") {
                draft.days = AlarmSchedule.WEEKDAYS.take(5).toMutableSet(); draft.date = null
            },
            Preset("Date") { pickDate(draft, onChange) }
        )

        presets.forEachIndexed { index, preset ->
            TextView(activity).apply {
                text = preset.label
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                setBackgroundResource(R.drawable.chip_outline)
                setPadding(0, dp(9), 0, dp(9))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = if (index == 0) 0 else dp(6) }
                setOnClickListener {
                    preset.apply()
                    onChange()
                }
            }.also { row.addView(it) }
        }
    }

    private fun pickDate(draft: Draft, onChange: () -> Unit) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            activity,
            R.style.MassalarmePicker,
            { _, year, month, day ->
                draft.date = String.format(Locale.US, "%02d-%02d-%04d", day, month + 1, year)
                draft.days.clear()
                onChange()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = System.currentTimeMillis()
        }.show()
    }

    private fun describe(draft: Draft): String {
        val time = String.format(Locale.US, "%02d:%02d", draft.hour, draft.minute)
        return when {
            draft.date != null -> "Rings once, on ${draft.date} at $time"
            draft.days.isEmpty() -> "Rings once, at the next $time"
            draft.days.size == 7 -> "Rings every day at $time"
            draft.days.size == 5 &&
                AlarmSchedule.WEEKDAYS.take(5).all { it in draft.days } ->
                "Rings on weekdays at $time"
            else -> {
                val names = draft.days.sortedBy { AlarmSchedule.WEEKDAYS.indexOf(it) }
                    .joinToString(", ") {
                        AlarmSchedule.WEEKDAY_LABELS[AlarmSchedule.WEEKDAYS.indexOf(it)]
                    }
                "Rings $names at $time"
            }
        }
    }

    private fun defaultName(draft: Draft): String =
        if (draft.kind == AlarmSchedule.KIND_HARD) "Weigh-in" else "Reminder"

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private fun View.dp(value: Int): Int = this@AlarmEditor.dp(value)
}
