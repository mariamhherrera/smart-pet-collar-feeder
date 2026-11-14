package com.example.pettracker

import android.app.Activity
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.TextView
import com.example.pettracker.HistoryRange.Range
import com.example.pettracker.charts.ChartData
import com.example.pettracker.charts.SimpleChartView
import com.google.firebase.auth.FirebaseAuth
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * UI-6 History graphs page.
 *  - Week / Month / Year (UI-6.2): meals and activity graphs for the chosen range.
 *    Activity comes from the day summaries saved with each reading (UI-6.1).
 *  - Pick a day (UI-6.1): loads that day's saved readings and meals by date,
 *    then shows its movement graph. Sleep (COL-5) fills in once the collar sends
 *    its 1-minute summaries.
 * Readings are saved with their time while the app is open (Home or Collar page).
 */
class HistoryActivity : Activity() {

    private val auth = FirebaseAuth.getInstance()
    private var cloud: PetCloudRepository? = null

    /** The range shown in the meals and activity graphs. */
    private var range = Range.WEEK

    /** Midnight of the day shown in the movement and sleep graphs. */
    private var selectedDay = MealLimit.startOfDay(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        BottomNav.setUp(this, BottomNav.HISTORY)

        findViewById<SimpleChartView>(R.id.chartMeals).emptyText = "No meals in this period"
        findViewById<SimpleChartView>(R.id.chartActivity).emptyText = "Not enough collar data for this period"
        findViewById<SimpleChartView>(R.id.chartMovement).emptyText = "No collar readings on this day"
        findViewById<SimpleChartView>(R.id.chartSleep).emptyText = "No sleep data yet (coming with COL-5)"

        // Week / Month / Year buttons
        findViewById<RadioGroup>(R.id.rangeGroup).setOnCheckedChangeListener { _, checkedId ->
            range = when (checkedId) {
                R.id.rangeMonth -> Range.MONTH
                R.id.rangeYear -> Range.YEAR
                else -> Range.WEEK
            }
            loadRange()
        }

        findViewById<android.view.View>(R.id.buttonPrevDay).setOnClickListener { moveDay(-1) }
        findViewById<android.view.View>(R.id.buttonNextDay).setOnClickListener { moveDay(1) }
    }

    override fun onResume() {
        super.onResume()
        val user = auth.currentUser ?: return
        cloud = PetCloudRepository(user.uid)
        loadRange()
        loadDay()
    }

    override fun onPause() {
        super.onPause()
        cloud = null
    }

    /** UI-6.2: loads the meals and the day summaries for the chosen range, then draws both graphs. */
    private fun loadRange() {
        val cloud = cloud ?: return
        val range = this.range
        val now = System.currentTimeMillis()
        val start = HistoryRange.rangeStart(range, now)
        val labels = HistoryRange.barLabels(range, HistoryRange.barKeys(range, now))

        setText(R.id.textMealsTitle, "Meals, " + HistoryRange.title(range))
        setText(R.id.textActivityTitle, "Activity, " + HistoryRange.title(range))

        cloud.loadFeedingsBetween(start, now,
            onValue = { rows ->
                runOnUiThread {
                    // Skip it if the user already picked another range
                    if (range == this.range) showMeals(HistoryRange.mealsPerBar(rows, range, now), labels)
                }
            },
            onError = { message -> runOnUiThread { setText(R.id.textMealsSummary, "Error: $message") } }
        )

        cloud.loadDaySummaries(HistoryRange.dayKey(start), HistoryRange.dayKey(now),
            onValue = { days ->
                runOnUiThread {
                    if (range == this.range) showActivity(HistoryRange.activityPerBar(days, range, now), labels)
                }
            },
            onError = { message -> runOnUiThread { setText(R.id.textActivitySummary, "Error: $message") } }
        )
    }

    /** Bar graph: meals given in each day (or month), oldest on the left. */
    private fun showMeals(counts: IntArray, labels: Array<String>) {
        val total = counts.sum()
        val chart = findViewById<SimpleChartView>(R.id.chartMeals)
        if (total == 0) {
            setText(R.id.textMealsSummary, "--")
            chart.setBars(FloatArray(0), arrayOf(), getColor(R.color.accent))
        } else {
            setText(R.id.textMealsSummary, if (total == 1) "1 meal" else "$total meals")
            chart.setBars(FloatArray(counts.size) { counts[it].toFloat() }, labels, getColor(R.color.accent))
        }
    }

    /** Bar graph: percent of the time active in each day (or month). Null = not enough data. */
    private fun showActivity(bars: HistoryRange.ActivityBars?, labels: Array<String>) {
        val chart = findViewById<SimpleChartView>(R.id.chartActivity)
        if (bars == null) {
            setText(R.id.textActivitySummary, "--")
            chart.setBars(FloatArray(0), arrayOf(), getColor(R.color.primary))
        } else {
            setText(R.id.textActivitySummary, "Active ${bars.overallPercent}% of the time")
            chart.setBars(bars.percents, labels, getColor(R.color.primary), "%")
        }
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

    /** Line graph for the selected day: movement. */
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

        // Sleep (COL-5): waits for the collar's 1-minute summaries
        setText(R.id.textSleepSummary, "--")
    }

    private fun setText(id: Int, text: String) {
        findViewById<TextView>(id).text = text
    }

    private companion object {
        const val MAX_POINTS = 288 // one point per 5 minutes over a whole day
    }
}
