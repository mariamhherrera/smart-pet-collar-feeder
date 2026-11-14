package com.example.pettracker

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.example.pettracker.feeder.ScheduleActivity

/**
 * UI-3.3 Page navigation: bottom bar with the 5 main pages (Home, Feeder, Collar, History,
 * Settings), and the round profile icon in the top bar (UI-7.1).
 *
 * Every main page includes layout/top_bar.xml and layout/bottom_nav.xml,
 * then calls BottomNav.setUp(this, BottomNav.FEEDER) (with its own tab) in onCreate.
 *
 * Home always stays open underneath. Opening another tab closes the tab you
 * were on, so the phone's Back button always goes back to Home.
 */
object BottomNav {

    const val HOME = 0
    const val FEEDER = 1
    const val COLLAR = 2
    const val HISTORY = 3
    const val SETTINGS = 4

    private val titles = arrayOf("Home", "Feeder", "Collar", "History", "Settings")

    private val tabs = arrayOf(
        intArrayOf(HOME, R.id.navHome, R.id.navHomeIcon, R.id.navHomeText),
        intArrayOf(FEEDER, R.id.navFeeder, R.id.navFeederIcon, R.id.navFeederText),
        intArrayOf(COLLAR, R.id.navCollar, R.id.navCollarIcon, R.id.navCollarText),
        intArrayOf(HISTORY, R.id.navHistory, R.id.navHistoryIcon, R.id.navHistoryText),
        intArrayOf(SETTINGS, R.id.navSettings, R.id.navSettingsIcon, R.id.navSettingsText)
    )

    /** For the 5 main pages: page name, profile icon, and the bottom bar. */
    @JvmStatic
    fun setUp(activity: Activity, current: Int) {
        activity.findViewById<TextView>(R.id.topBarTitle)?.text = titles[current]

        // Profile icon: opens the pet profile on top of this page (Back returns here)
        activity.findViewById<View>(R.id.topBarProfile)?.setOnClickListener {
            activity.startActivity(Intent(activity, ProfileActivity::class.java))
        }

        for (tab in tabs) setUpTab(activity, current, tab[0], tab[1], tab[2], tab[3])
    }

    /** For a page opened from a main page (the pet profile): back arrow instead of the logo. */
    @JvmStatic
    fun setUpDetail(activity: Activity, title: String) {
        activity.findViewById<TextView>(R.id.topBarTitle)?.text = title
        activity.findViewById<View>(R.id.topBarLogo)?.visibility = View.GONE
        activity.findViewById<View>(R.id.topBarProfile)?.visibility = View.GONE
        activity.findViewById<View>(R.id.topBarBack)?.apply {
            visibility = View.VISIBLE
            setOnClickListener { activity.finish() }
        }
    }

    private fun setUpTab(activity: Activity, current: Int, tab: Int,
                         tabId: Int, iconId: Int, textId: Int) {
        val icon = activity.findViewById<ImageView>(iconId) ?: return
        val text = activity.findViewById<TextView>(textId)

        // The open page's tab is teal, with a light pill behind the icon
        if (tab == current) {
            val teal = activity.getColor(R.color.primary)
            icon.imageTintList = ColorStateList.valueOf(teal)
            icon.setBackgroundResource(R.drawable.bg_nav_pill)
            text.setTextColor(teal)
        }

        activity.findViewById<View>(tabId).setOnClickListener {
            if (tab != current) open(activity, current, tab)
        }
    }

    private fun open(activity: Activity, current: Int, tab: Int) {
        val screen = when (tab) {
            FEEDER -> ScheduleActivity::class.java
            COLLAR -> CollarActivity::class.java
            HISTORY -> HistoryActivity::class.java
            SETTINGS -> SettingsActivity::class.java
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
