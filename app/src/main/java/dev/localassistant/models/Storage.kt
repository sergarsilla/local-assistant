package dev.localassistant.models

/** Whether a download fits, leaving room for the phone to keep working. */
object Storage {
    /** Android slows down and apps start failing well before storage is literally full. */
    const val RESERVE_BYTES = 1_000_000_000L

    fun fits(sizeBytes: Long, freeBytes: Long): Boolean = sizeBytes <= 0 || sizeBytes + RESERVE_BYTES <= freeBytes
}
