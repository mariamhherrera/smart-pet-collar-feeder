package com.example.pettracker

/**
 * COL-13.1: one notification when the pet leaves the safe zone,
 * then no more until the pet is back inside.
 */
object SafeZoneAlert {

    /** [notify]: send a notification now. [alerted]: remember that this trip outside was already notified. */
    data class Decision(val notify: Boolean, val alerted: Boolean)

    fun decide(status: SafeZone.Status, alreadyAlerted: Boolean): Decision = when (status) {
        SafeZone.Status.OUTSIDE -> Decision(notify = !alreadyAlerted, alerted = true)
        SafeZone.Status.INSIDE -> Decision(notify = false, alerted = false)   // back home: next exit alerts again
        else -> Decision(notify = false, alerted = alreadyAlerted)          // no GPS or no home set: wait
    }
}
