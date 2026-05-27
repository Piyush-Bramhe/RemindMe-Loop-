package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "users", indices = [Index(value = ["username"], unique = true)])
data class User(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val username: String,
    val passwordHash: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val description: String,
    val createdByUserId: Int,
    val dueDate: Long,              // Absolute target timestamp for notification
    val isCompleted: Boolean = false,
    val repeatIntervalMinutes: Int = 0, // 0 means no repetition; values like 5, 10, 15, 30, etc.
    val completedAt: Long? = null,
    val isSnoozed: Boolean = false,
    val snoozeTime: Long = 0,       // Snooze target timestamp
    val targetTime: Long = 0,       // Dynamic timestamp keeping track of next scheduled bell (increases for repeats)
    val updatedAt: Long = System.currentTimeMillis()
)
