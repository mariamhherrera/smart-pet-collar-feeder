package com.example.pettracker.charts

/** Helpers to prepare values for SimpleChartView. */
object ChartData {

    /**
     * A full day of readings (one every 5 seconds) is about 17,000 values, too many for a
     * small graph. This averages them into at most [maxPoints] values, keeping the shape.
     */
    @JvmStatic
    fun averageDown(values: FloatArray, maxPoints: Int): FloatArray {
        if (values.size <= maxPoints) return values
        val result = FloatArray(maxPoints)
        for (i in 0 until maxPoints) {
            val from = (i.toLong() * values.size / maxPoints).toInt()
            val to = ((i + 1).toLong() * values.size / maxPoints).toInt()
            var sum = 0f
            for (j in from until to) sum += values[j]
            result[i] = sum / (to - from)
        }
        return result
    }
}
