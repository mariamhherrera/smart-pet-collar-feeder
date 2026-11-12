package com.example.pettracker

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.example.pettracker.charts.ChartData
import com.example.pettracker.charts.SimpleChartView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * UI-6 History graphs page.
 *  - Meals, last 7 days: from the feeding history in Firebase (updates live).
 *  - Pick a day (UI-6.1): loads that day's saved readings and meals by date,
 *    then shows its movement and heart rate graphs (UI-6.2).
 * Readings are saved with their time while the app is open (Home or Collar page).
 */
class HistoryActivity : Activity() {

    private val auth = FirebaseAuth.getInstance()
    private var cloud: PetCloudRepository? = null
    private var feedingsListener: ValueEventListener? = null

    /** Midnight of the day shown in the movement and heart rate graphs. */
    private var selectedDay = MealLimit.startOfDay(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        BottomNav.setUp(this, BottomNav.HISTORY)

        findViewById<SimpleChartView>(R.id.chartMeals).emptyText = "No meals recorded yet"
        findViewById<SimpleChartView>(R.id.chartMovement).emptyText = "No collar readings on this day"
        findViewById<SimpleChartView>(R.id.chartHeart).emptyText = "No heart rate readings on this day"

        findViewById<android.view.View>(R.id.buttonPrevDay).setOnClickListener { moveDay(-1) }
        findViewById<android.view.View>(R.id.buttonNextDay).setOnClickListener { moveDay(1) }
    }

    override fun onResume() {
        super.onResume()
        val user = auth.currentUser ?: return
        val cloud = PetCloudRepository(user.uid)
        this.cloud = cloud

        feedingsListener = cloud.listenToFeedings(FEEDINGS_TO_LOAD,
            onValue = { rows -> runOnUiThread { showWeekOfMeals(rows) } },
            onError = { message -> runOnUiThread { setText(R.id.textMealsSummary, "Error: $message") } }
        )
        loadDay()
    }

    override fun onPause() {
        super.onPause()
        feedingsListener?.let { cloud?.removeFeedingsListener(FEEDINGS_TO_LOAD, it) }
        feedingsListener = null
        cloud = null
    }

    /** Previous or next day. You can't go past today. */
    private fun moveDay(days: Int) {
        val day = Calendar.getInstance()
        day.timeInMillis = selectedDay
        day.add(Calendar.DAY_OF_YEAR, days)
        val today = MealLimit.startOfDay(System.currentTimeMillis())
        if (day.timeInMillis > today) return
        selectedDay = MealLimit.startOfDay(day.timeInMillis)
        loadDay()
    }

    /** UI-6.1: loads the readings and meals saved on the selected day. */
    private fun loadDay() {
        val cloud = cloud ?: return
        val today = MealLimit.startOfDay(System.currentTimeMillis())
        setText(R.id.textDay,
            if (selectedDay == today) "Today" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(selectedDay)))
        findViewById<android.view.View>(R.id.buttonNextDay).alpha = if (selectedDay == today) 0.4f else 1f

        val next = Calendar.getInstance()
        next.timeInMillis = selectedDay
        next.add(Calendar.DAY_OF_YEAR, 1)
        val dayEnd = next.timeInMillis - 1
        val day = selectedDay

        cloud.loadReadingsBetween(day, dayEnd,
            onValue = { readings -> runOnUiThread { if (day == selectedDay) showDayOfReadings(readings) } },
            onError = { message -> runOnUiThread { setText(R.id.textMovementSummary, "Error: $message") } }
        )
        cloud.loadFeedingsBetween(day, dayEnd,
            onValue = { rows ->
                runOnUiThread {
                    if (day != selectedDay) return@runOnUiThread
                    val meals = MealLimit.mealsToday(rows, dayEnd)
                    setText(R.id.textDayMeals, if (meals == 1) "1 meal" else "$meals meals")
                }
            },
            onError = { }
        )
    }

    /** Bar graph: meals given on each of the last 7 days, today on the right. */
    private fun showWeekOfMeals(rows: List<Map<String, Any>>) {
        val counts = MealLimit.mealsPerDay(rows, 7)
        val dayName = SimpleDateFormat("EEE", Locale.getDefault())
        val labels = Array(7) { i ->
            val day = Calendar.getInstance()
            day.add(Calendar.DAY_OF_YEAR, i - 6)
            if (i == 6) "Today" else dayName.format(day.time)
        }
        val total = counts.sum()
        setText(R.id.textMealsSummary, "$total meals this week")
        val chart = findViewById<SimpleChartView>(R.id.chartMeals)
        if (total == 0) chart.setBars(FloatArray(0), arrayOf(), getColor(R.color.accent))
        else chart.setBars(FloatArray(7) { counts[it].toFloat() }, labels, getColor(R.color.accent))
    }

    /** Line graphs for the selected day: movement and heart rate. */
    private fun showDayOfReadings(all: List<CollarReading>) {
        val readings = all.sortedBy { it.timeMillis }
        val clock = android.text.format.DateFormat.getTimeFormat(this)

        // Movement: 0 g when still, higher when moving
        val movement = findViewById<SimpleChartView>(R.id.chartMovement)
        if (readings.isEmpty()) {
            setText(R.id.textMovementSummary, "--")
            movement.setLine(FloatArray(0), arrayOf(), getColor(R.color.primary))
        } else {
            val values = FloatArray(readings.size) { readings[it].movement().toFloat() }
            setText(R.id.textMovementSummary, "Average %.2f g".format(values.average()))
            movement.setLine(ChartData.averageDown(values, MAX_POINTS),
                arrayOf(clock.format(Date(readings.first().timeMillis)), clock.format(Date(readings.last().timeMillis))),
                getColor(R.color.primary), " g")
        }

        // Heart rate: only the readings that have one
        val withHeart = readings.filter { (it.heartRate ?: 0) > 0 }
        val heart = findViewById<SimpleChartView>(R.id.chartHeart)
        if (withHeart.isEmpty()) {
            setText(R.id.textHeartSummary, "--")
            heart.setLine(FloatArray(0), arrayOf(), getColor(R.color.heart))
        } else {
            val values = FloatArray(withHeart.size) { withHeart[it].heartRate!!.toFloat() }
            setText(R.id.textHeartSummary, "Average %d BPM".format(values.average().toInt()))
            heart.setLine(ChartData.averageDown(values, MAX_POINTS),
                arrayOf(clock.format(Date(withHeart.first().timeMillis)), clock.format(Date(withHeart.last().timeMillis))),
                getColor(R.color.heart))
        }
    }

    private fun setText(id: Int, text: String) {
        findViewById<TextView>(id).text = text
    }

    private companion object {
        const val FEEDINGS_TO_LOAD = 100
        const val MAX_POINTS = 288 // one point per 5 minutes over a whole day
    }
}
