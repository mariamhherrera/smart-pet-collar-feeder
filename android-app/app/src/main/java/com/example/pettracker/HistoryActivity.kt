package com.example.pettracker

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.example.pettracker.charts.SimpleChartView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * UI-6 History graphs page.
 *  - Meals, last 7 days: from the feeding history in Firebase.
 *  - Movement and heart rate, last hour: from the collar readings saved with their time (UI-6.1).
 * Readings are saved while the app is open (Home or Collar page), one every 5 seconds.
 */
class HistoryActivity : Activity() {

    private val auth = FirebaseAuth.getInstance()
    private var cloud: PetCloudRepository? = null
    private var readingsListener: ValueEventListener? = null
    private var feedingsListener: ValueEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        BottomNav.setUp(this, BottomNav.HISTORY)

        findViewById<SimpleChartView>(R.id.chartMeals).emptyText = "No meals recorded yet"
        findViewById<SimpleChartView>(R.id.chartMovement).emptyText = "No collar readings in the last hour"
        findViewById<SimpleChartView>(R.id.chartHeart).emptyText = "No heart rate readings yet"
    }

    override fun onResume() {
        super.onResume()
        val user = auth.currentUser ?: return
        val cloud = PetCloudRepository(user.uid)
        this.cloud = cloud

        feedingsListener = cloud.listenToFeedings(FEEDINGS_TO_LOAD,
            onValue = { rows -> runOnUiThread { showMeals(rows) } },
            onError = { message -> runOnUiThread { setSummary(R.id.textMealsSummary, "Error: $message") } }
        )
        readingsListener = cloud.listenToReadings(READINGS_TO_LOAD,
            onValue = { readings -> runOnUiThread { showReadings(readings) } },
            onError = { message -> runOnUiThread { setSummary(R.id.textMovementSummary, "Error: $message") } }
        )
    }

    override fun onPause() {
        super.onPause()
        feedingsListener?.let { cloud?.removeFeedingsListener(FEEDINGS_TO_LOAD, it) }
        readingsListener?.let { cloud?.removeReadingsListener(READINGS_TO_LOAD, it) }
        feedingsListener = null
        readingsListener = null
        cloud = null
    }

    /** Bar graph: meals given on each of the last 7 days, today on the right. */
    private fun showMeals(rows: List<Map<String, Any>>) {
        val counts = MealLimit.mealsPerDay(rows, 7)
        val dayName = SimpleDateFormat("EEE", Locale.getDefault())
        val labels = Array(7) { i ->
            val day = Calendar.getInstance()
            day.add(Calendar.DAY_OF_YEAR, i - 6)
            if (i == 6) "Today" else dayName.format(day.time)
        }
        val total = counts.sum()
        setSummary(R.id.textMealsSummary, "$total meals this week")
        val chart = findViewById<SimpleChartView>(R.id.chartMeals)
        if (total == 0) chart.setBars(FloatArray(0), arrayOf(), getColor(R.color.accent))
        else chart.setBars(FloatArray(7) { counts[it].toFloat() }, labels, getColor(R.color.accent))
    }

    /** Line graphs: movement and heart rate over the last hour. */
    private fun showReadings(all: List<CollarReading>) {
        val hourAgo = System.currentTimeMillis() - 60 * 60 * 1000L
        val recent = all.filter { it.timeMillis >= hourAgo }.sortedBy { it.timeMillis }
        val clock = android.text.format.DateFormat.getTimeFormat(this)

        // Movement: 0 g when still, higher when moving
        val movement = findViewById<SimpleChartView>(R.id.chartMovement)
        if (recent.isEmpty()) {
            setSummary(R.id.textMovementSummary, "--")
            movement.setLine(FloatArray(0), arrayOf(), getColor(R.color.primary))
        } else {
            val values = FloatArray(recent.size) { recent[it].movement().toFloat() }
            setSummary(R.id.textMovementSummary, "Average %.2f g".format(values.average()))
            movement.setLine(values, arrayOf(clock.format(Date(recent.first().timeMillis)), "Now"),
                getColor(R.color.primary), " g")
        }

        // Heart rate: only the readings that have one
        val withHeart = recent.filter { (it.heartRate ?: 0) > 0 }
        val heart = findViewById<SimpleChartView>(R.id.chartHeart)
        if (withHeart.isEmpty()) {
            setSummary(R.id.textHeartSummary, "--")
            heart.setLine(FloatArray(0), arrayOf(), getColor(R.color.heart))
        } else {
            val values = FloatArray(withHeart.size) { withHeart[it].heartRate!!.toFloat() }
            setSummary(R.id.textHeartSummary, "Average %d BPM".format(values.average().toInt()))
            heart.setLine(values, arrayOf(clock.format(Date(withHeart.first().timeMillis)), "Now"),
                getColor(R.color.heart))
        }
    }

    private fun setSummary(id: Int, text: String) {
        findViewById<TextView>(id).text = text
    }

    private companion object {
        const val FEEDINGS_TO_LOAD = 100
        const val READINGS_TO_LOAD = 720 // one hour of readings, one every 5 seconds
    }
}
