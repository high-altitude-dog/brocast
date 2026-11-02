package org.beachsync.proto

/**
 * Which stream segment to fetch next. Plain Kotlin, so simtest can run it on a desktop.
 *
 * A segment that lands after its own second has passed is wasted, so after an outage longer than the buffer the
 * fetcher must not start from the second being heard: on a link only just faster than real time, it would stay
 * behind playback for a minute or more. [useful] is the segment that will be playing when a fetch started now lands.
 */
object FetchPlan {
    /** The first missing segment from [useful] to [last], then any earlier one in [first]..[last]. Null when all are here. */
    fun next(first: Long, last: Long, useful: Long, have: (Long) -> Boolean): Long? {
        var n = maxOf(first, useful)
        while (n <= last) {
            if (!have(n)) return n
            n++
        }
        n = first
        val end = minOf(useful - 1, last)
        while (n <= end) {
            if (!have(n)) return n
            n++
        }
        return null
    }
}
