package com.taptransfer

/** A launch target offered by Android for this particular UPI intent. */
data class UpiTarget(val pkg: String, val component: String, val label: String)

object UpiRouting {
    // 1.2 stored a package. Newer preferences store the precise exported activity.
    fun resolve(saved: String, candidates: List<UpiTarget>): UpiTarget? {
        if (saved.isBlank()) return null
        return candidates.firstOrNull { it.component == saved }
            ?: candidates.firstOrNull { it.pkg == saved.substringBefore('/') }
    }
}
