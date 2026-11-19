package dev.romcon.media

/** How long the lock screen media link stays connected once the app is closed, as chosen in Settings. */
object BackgroundLimit {
    /** No limit: the link stays for as long as the phone is on, as it always did. */
    const val NONE = 0

    /** The choices Settings offers, in minutes. */
    val CHOICES = listOf(30, 60, 120, 240, 480, NONE)

    fun label(minutes: Int): String = when {
        minutes == NONE -> "No limit"
        minutes == 60 -> "1 hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "$minutes minutes"
    }

    /** When the link must stop, by the wall clock, for an app closed at [closedAt]; null if there's no limit. */
    fun deadline(minutes: Int, closedAt: Long): Long? = if (minutes == NONE) null else closedAt + minutes * 60_000L
}
