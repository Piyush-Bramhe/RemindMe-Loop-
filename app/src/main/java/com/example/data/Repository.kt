package com.example.data

import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

class UserRepository(private val userDao: UserDao) {
    suspend fun getUserByUsername(username: String): User? {
        return userDao.getUserByUsername(username)
    }

    suspend fun getUserById(id: Int): User? {
        return userDao.getUserById(id)
    }

    suspend fun registerUser(username: String, passwordRaw: String): Result<User> {
        if (username.isBlank() || passwordRaw.isBlank()) {
            return Result.failure(IllegalArgumentException("Username and password cannot be blank"))
        }
        val existing = userDao.getUserByUsername(username)
        if (existing != null) {
            return Result.failure(IllegalArgumentException("Username already exists"))
        }
        val hashedPassword = hashPassword(passwordRaw)
        val user = User(username = username, passwordHash = hashedPassword)
        val id = userDao.insertUser(user)
        return Result.success(user.copy(id = id.toInt()))
    }

    suspend fun loginUser(username: String, passwordRaw: String): Result<User> {
        val user = userDao.getUserByUsername(username) ?: return Result.failure(IllegalArgumentException("User does not exist"))
        val hashedPassword = hashPassword(passwordRaw)
        return if (user.passwordHash == hashedPassword) {
            Result.success(user)
        } else {
            Result.failure(IllegalArgumentException("Incorrect password"))
        }
    }

    private fun hashPassword(password: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            password // Fallback (should never happen)
        }
    }
}

class ReminderRepository(private val reminderDao: ReminderDao) {
    fun getRemindersForUser(userId: Int): Flow<List<Reminder>> {
        return reminderDao.getRemindersForUser(userId)
    }

    suspend fun getReminderById(id: Int): Reminder? {
        return reminderDao.getReminderById(id)
    }

    suspend fun getActiveReminders(): List<Reminder> {
        return reminderDao.getActiveReminders()
    }

    suspend fun insertReminder(reminder: Reminder): Long {
        return reminderDao.insertReminder(reminder)
    }

    suspend fun updateReminder(reminder: Reminder) {
        reminderDao.updateReminder(reminder)
    }

    suspend fun deleteReminder(reminder: Reminder) {
        reminderDao.deleteReminder(reminder)
    }
}
