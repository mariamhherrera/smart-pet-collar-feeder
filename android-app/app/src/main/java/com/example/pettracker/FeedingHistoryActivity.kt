package com.example.pettracker

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * FD-6.2 Feeding history page, opened from the Feeder page.
 * Every meal (scheduled or Feed Now) with its time, portion and whether it worked,
 * newest first and grouped by day. Failed meals are marked in red.
 */
class FeedingHistoryActivity : Activity() {

    private val auth = FirebaseAuth.getInstance()
    private var cloud: PetCloudRepository? = null
    private var listener: ValueEventListener? = null

    private lateinit var list: LinearLayout
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feeding_history)
        BottomNav.setUpDetail(this, "Feeding history")
        list = findViewById(R.id.layoutMeals)
        summary = findViewById(R.id.textHistorySummary)
    }

    override fun onResume() {
        super.onResume()
        val user = auth.currentUser ?: return
        val cloud = PetCloudRepository(user.uid)
        this.cloud = cloud
        listener = cloud.listenToFeedings(MEALS_TO_LOAD,
            onValue = { rows -> runOnUiThread { show(FeedingHistory.fromRows(rows, System.currentTimeMillis())) } },
            onError = { message -> runOnUiThread { summary.text = "Firebase error: $message" } }
        )
    }

    override fun onPause() {
        super.onPause()
        listener?.let { cloud?.removeFeedingsListener(MEALS_TO_LOAD, it) }
        listener = null
        cloud = null
    }

    private fun show(meals: List<FeedingHistory.Meal>) {
        list.removeAllViews()
        if (meals.isEmpty()) {
            summary.text = "No meals recorded yet. Meals from Feed Now and the schedule will show here."
            return
        }
        val given = meals.count { it.state == FeedingHistory.State.GIVEN }
        summary.text = "${meals.size} meals · $given confirmed by the feeder"

        for ((day, dayMeals) in FeedingHistory.byDay(meals)) {
            list.addView(dayLabel(day))
            val card = LinearLayout(this, null, 0, R.style.Pt_Card)
            card.orientation = LinearLayout.VERTICAL
            card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
            dayMeals.forEachIndexed { i, meal ->
                if (i > 0) card.addView(divider())
                card.addView(mealRow(meal))
            }
            list.addView(card)
        }
    }

    /** "Today", "Yesterday", or the date. */
    private fun dayLabel(day: Long): TextView {
        val today = MealLimit.startOfDay(System.currentTimeMillis())
        val yesterday = Calendar.getInstance().apply { timeInMillis = today; add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        val label = TextView(this, null, 0, R.style.Pt_Label)
        label.text = when (day) {
            today -> "Today"
            yesterday -> "Yesterday"
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(day))
        }
        label.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20); bottomMargin = dp(8); marginStart = dp(4) }
        return label
    }

    /** One meal: time and portion on the left, the status on the right. */
    private fun mealRow(meal: FeedingHistory.Meal): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(8), 0, dp(8))

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val time = TextView(this)
        time.text = android.text.format.DateFormat.getTimeFormat(this).format(Date(meal.time))
        time.textSize = 16f
        time.setTypeface(time.typeface, Typeface.BOLD)
        time.setTextColor(getColor(R.color.text_primary))
        val detail = TextView(this, null, 0, R.style.Pt_Body)
        detail.text = "${FeedingHistory.portionName(meal.portion)} portion · ${FeedingHistory.sourceName(meal.source)}"
        left.addView(time)
        left.addView(detail)

        val (textColor, background) = when (meal.state) {
            FeedingHistory.State.GIVEN -> 0xFF15803D.toInt() to 0xFFDCFCE7.toInt()
            FeedingHistory.State.WAITING -> 0xFFB45309.toInt() to 0xFFFEF3C7.toInt()
            FeedingHistory.State.NO_REPLY -> getColor(R.color.accent_dark) to getColor(R.color.accent_light)
            FeedingHistory.State.FAILED -> getColor(R.color.heart) to getColor(R.color.heart_light)
        }
        val status = TextView(this)
        status.text = FeedingHistory.stateName(meal.state)
        status.textSize = 12f
        status.setTypeface(status.typeface, Typeface.BOLD)
        status.setTextColor(textColor)
        status.setPadding(dp(10), dp(4), dp(10), dp(4))
        status.background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(background) }

        row.addView(left)
        row.addView(status)
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.card_border))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MEALS_TO_LOAD = 100
    }
}
