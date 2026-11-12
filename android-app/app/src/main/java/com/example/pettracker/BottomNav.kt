package com.example.pettracker

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.ImageView
import android.widget.TextView
import com.example.pettracker.feeder.ScheduleActivity

/**
 * Bottom bar with the 4 main screens: Home, Feeder, Collar, Profile.
 *
 * Every main screen includes layout/bottom_nav.xml and layout/top_bar.xml,
 * then calls BottomNav.setUp(this, BottomNav.FEEDER) (with its own tab) in onCreate.
 *
 * Home always stays open underneath. Opening another tab closes the tab you
 * were on, so the phone's Back button always goes back to Home.
 */
object BottomNav {

    const val HOME = 0
    const val FEEDER = 1
    const val COLLAR = 2
    const val PROFILE = 3

    private val titles = arrayOf("Home", "Feeder", "Collar", "Profile")

    @JvmStatic
    fun setUp(activity: Activity, current: Int) {
        activity.findViewById<TextView>(R.id.topBarTitle)?.text = titles[current]

        setUpTab(activity, current, HOME, R.id.navHome, R.id.navHomeIcon, R.id.navHomeText)
        setUpTab(activity, current, FEEDER, R.id.navFeeder, R.id.navFeederIcon, R.id.navFeederText)
        setUpTab(activity, current, COLLAR, R.id.navCollar, R.id.navCollarIcon, R.id.navCollarText)
        setUpTab(activity, current, PROFILE, R.id.navProfile, R.id.navProfileIcon, R.id.navProfileText)
    }

    private fun setUpTab(activity: Activity, current: Int, tab: Int,
                         tabId: Int, iconId: Int, textId: Int) {
        val icon = activity.findViewById<ImageView>(iconId)
        val text = activity.findViewById<TextView>(textId)

        // The open screen's tab is teal, with a light pill behind the icon
        if (tab == current) {
            val teal = activity.getColor(R.color.primary)
            icon.imageTintList = ColorStateList.valueOf(teal)
            icon.setBackgroundResource(R.drawable.bg_nav_pill)
            text.setTextColor(teal)
        }

        activity.findViewById<android.view.View>(tabId).setOnClickListener {
            if (tab != current) open(activity, current, tab)
        }
    }

    private fun open(activity: Activity, current: Int, tab: Int) {
        val screen = when (tab) {
            FEEDER -> ScheduleActivity::class.java
            COLLAR -> CollarActivity::class.java
            PROFILE -> ProfileActivity::class.java
            else -> HomeActivity::class.java
        }
        val intent = Intent(activity, screen)
        // Going to Home: go back to the Home that is already open, don't make a new one
        if (tab == HOME) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        activity.startActivity(intent)

        // Close the tab we were on (but never Home), so tabs don't pile up
        if (current != HOME) activity.finish()

        // Switch instantly, like real tabs, instead of sliding
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}
