package com.photosapp.ui.home

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.photosapp.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.*
import java.util.Date

class EditDateTimeDialog : DialogFragment() {
    private val vm: HomeViewModel by activityViewModels { HomeViewModel.Factory }
    private var chosen = LocalDateTime.now()
    private var picker: Dialog? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val seconds = state?.getLong("chosen") ?: requireArguments().getLong("date")
        chosen = Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }
    override fun onCreateDialog(state: Bundle?): Dialog {
        val context = requireContext()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        val date = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { contentDescription = getString(R.string.date_choose) }
        val time = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply { contentDescription = getString(R.string.time_choose) }
        fun render() {
            val instant = Date.from(chosen.atZone(ZoneId.systemDefault()).toInstant())
            date.text = DateFormat.getMediumDateFormat(context).format(instant)
            time.text = DateFormat.getTimeFormat(context).format(instant)
        }
        date.setOnClickListener {
            picker?.dismiss()
            picker = DatePickerDialog(context, { _, year, month, day -> chosen = chosen.withYear(year).withMonth(1).withDayOfMonth(1).withMonth(month + 1).withDayOfMonth(day); render() },
                chosen.year, chosen.monthValue - 1, chosen.dayOfMonth).apply {
                datePicker.minDate = LocalDate.of(1900, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                datePicker.maxDate = LocalDate.of(2100, 12, 31).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                show()
            }
        }
        time.setOnClickListener {
            picker?.dismiss()
            picker = TimePickerDialog(context, { _, hour, minute -> chosen = chosen.withHour(hour).withMinute(minute).withSecond(0).withNano(0); render() },
                chosen.hour, chosen.minute, DateFormat.is24HourFormat(context)).apply { show() }
        }
        root.addView(date, LinearLayout.LayoutParams(-1, root.dp(56)))
        root.addView(time, LinearLayout.LayoutParams(-1, root.dp(56)))
        root.addView(TextView(context).apply { text = getString(R.string.date_zone, ZoneId.systemDefault().id); setTextColor(context.color(R.color.text_secondary)); setPadding(0, dp(8), 0, dp(8)) })
        root.addView(TextView(context).apply { setText(R.string.date_edit_scope); setTextColor(context.color(R.color.text_secondary)) })
        render()
        val result = MaterialAlertDialogBuilder(context).setTitle(R.string.edit_date_time).setView(root)
            .setNegativeButton(R.string.date_cancel, null).setPositiveButton(R.string.date_save, null).create()
        result.setOnShowListener {
            val save = result.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val zone = ZoneId.systemDefault()
                if (zone.rules.getValidOffsets(chosen).isEmpty()) { Toast.makeText(context, R.string.date_invalid, Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val uri = requireArguments().getString("uri")
                val photo = vm.photos.value.firstOrNull { it.uri.toString() == uri } ?: return@setOnClickListener
                save.isEnabled = false
                lifecycleScope.launch {
                    try {
                        com.photosapp.data.MediaDatesRepository().edit(photo, chosen.atZone(zone).toEpochSecond())
                        if (isAdded) dismiss()
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        if (isAdded) { save.isEnabled = true; Toast.makeText(context, R.string.date_save_failed, Toast.LENGTH_LONG).show() }
                    }
                }
            }
        }
        return result
    }
    override fun onSaveInstanceState(state: Bundle) { state.putLong("chosen", chosen.atZone(ZoneId.systemDefault()).toEpochSecond()); super.onSaveInstanceState(state) }
    override fun onDestroyView() { picker?.dismiss(); picker = null; super.onDestroyView() }
    companion object {
        const val TAG = "edit_date_time"
        fun newInstance(photo: LocalPhoto) = EditDateTimeDialog().apply { arguments = Bundle().apply { putString("uri", photo.uri.toString()); putLong("date", photo.dateAdded) } }
    }
}
