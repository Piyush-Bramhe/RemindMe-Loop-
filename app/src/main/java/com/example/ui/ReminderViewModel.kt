package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.receiver.ReminderScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

sealed class Screen {
    object Login : Screen()
    object SignUp : Screen()
    object Dashboard : Screen()
    object TaskHistory : Screen()
    data class TaskForm(val reminderId: Int? = null) : Screen()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val userRepository = UserRepository(db.userDao())
    private val reminderRepository = ReminderRepository(db.reminderDao())

    private val _currentUserId = MutableStateFlow<Int?>(null)
    val currentUserId: StateFlow<Int?> = _currentUserId.asStateFlow()

    private val _currentScreen = MutableStateFlow<Screen>(Screen.Login)
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _loggedInUser = MutableStateFlow<User?>(null)
    val loggedInUser: StateFlow<User?> = _loggedInUser.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    // Load active reminders reactively for the logged in user
    val reminders: StateFlow<List<Reminder>> = _currentUserId
        .flatMapLatest { userId ->
            if (userId != null) {
                reminderRepository.getRemindersForUser(userId).map { list ->
                    list.filter { !it.isCompleted }
                }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Load completed history reactively for the logged in user
    val completedHistory: StateFlow<List<Reminder>> = _currentUserId
        .flatMapLatest { userId ->
            if (userId != null) {
                reminderRepository.getRemindersForUser(userId).map { list ->
                    list.filter { it.isCompleted }
                }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Editing reminder state
    private val _editingReminder = MutableStateFlow<Reminder?>(null)
    val editingReminder: StateFlow<Reminder?> = _editingReminder.asStateFlow()

    init {
        // Simple session recovery: check users table if anyone is active
        // But for offline reliability, start fresh at login.
    }

    fun navigateTo(screen: Screen) {
        _authError.value = null
        if (screen is Screen.TaskForm) {
            viewModelScope.launch {
                val reminderId = screen.reminderId
                if (reminderId != null) {
                    _editingReminder.value = reminderRepository.getReminderById(reminderId)
                } else {
                    _editingReminder.value = null
                }
                _currentScreen.value = screen
            }
        } else {
            _currentScreen.value = screen
        }
    }

    fun login(username: String, passwordRaw: String) {
        if (username.isBlank() || passwordRaw.isBlank()) {
            _authError.value = "Credentials cannot be blank"
            return
        }
        _authError.value = null
        viewModelScope.launch {
            userRepository.loginUser(username.trim(), passwordRaw)
                .onSuccess { user ->
                    _loggedInUser.value = user
                    _currentUserId.value = user.id
                    _currentScreen.value = Screen.Dashboard
                }
                .onFailure { exception ->
                    _authError.value = exception.message ?: "Authentication failed"
                }
        }
    }

    fun signUp(username: String, passwordRaw: String) {
        if (username.isBlank() || passwordRaw.isBlank()) {
            _authError.value = "Credentials cannot be blank"
            return
        }
        _authError.value = null
        viewModelScope.launch {
            userRepository.registerUser(username.trim(), passwordRaw)
                .onSuccess { user ->
                    _loggedInUser.value = user
                    _currentUserId.value = user.id
                    _currentScreen.value = Screen.Dashboard
                }
                .onFailure { exception ->
                    _authError.value = exception.message ?: "Signup failed"
                }
        }
    }

    fun logout() {
        _loggedInUser.value = null
        _currentUserId.value = null
        _editingReminder.value = null
        _currentScreen.value = Screen.Login
    }

    fun saveReminder(
        title: String,
        description: String,
        dueTimeMillis: Long,
        repeatIntervalMinutes: Int
    ) {
        val userId = _currentUserId.value ?: return
        if (title.isBlank()) return

        viewModelScope.launch {
            val isEditing = _editingReminder.value
            val reminder = if (isEditing != null) {
                // Cancel old alarm schedule
                ReminderScheduler.cancel(getApplication(), isEditing)

                isEditing.copy(
                    title = title.trim(),
                    description = description.trim(),
                    dueDate = dueTimeMillis,
                    targetTime = dueTimeMillis,
                    repeatIntervalMinutes = repeatIntervalMinutes,
                    isCompleted = false,
                    isSnoozed = false,
                    snoozeTime = 0,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                Reminder(
                    title = title.trim(),
                    description = description.trim(),
                    createdByUserId = userId,
                    dueDate = dueTimeMillis,
                    targetTime = dueTimeMillis,
                    repeatIntervalMinutes = repeatIntervalMinutes,
                    isCompleted = false,
                    isSnoozed = false,
                    snoozeTime = 0,
                    updatedAt = System.currentTimeMillis()
                )
            }

            val savedId = if (reminder.id != 0) {
                reminderRepository.updateReminder(reminder)
                reminder.id
            } else {
                reminderRepository.insertReminder(reminder).toInt()
            }

            val newlySaved = reminder.copy(id = savedId)
            // Schedule the alarm trigger dynamically in system
            ReminderScheduler.schedule(getApplication(), newlySaved)

            _editingReminder.value = null
            _currentScreen.value = Screen.Dashboard
        }
    }

    fun toggleReminderCompleted(reminderId: Int) {
        viewModelScope.launch {
            val reminder = reminderRepository.getReminderById(reminderId) ?: return@launch
            val updated = reminder.copy(
                isCompleted = !reminder.isCompleted,
                completedAt = if (!reminder.isCompleted) System.currentTimeMillis() else null,
                isSnoozed = false,
                snoozeTime = 0,
                updatedAt = System.currentTimeMillis()
            )
            reminderRepository.updateReminder(updated)

            if (updated.isCompleted) {
                ReminderScheduler.cancel(getApplication(), updated)
            } else {
                // If uncompleted, reschedule the alarm
                val triggerNowOrFuture = if (updated.dueDate > System.currentTimeMillis()) updated.dueDate else System.currentTimeMillis() + 60000
                val rescheduled = updated.copy(dueDate = triggerNowOrFuture, targetTime = triggerNowOrFuture)
                reminderRepository.updateReminder(rescheduled)
                ReminderScheduler.schedule(getApplication(), rescheduled)
            }
        }
    }

    fun snoozeReminderDirect(reminderId: Int) {
        viewModelScope.launch {
            val reminder = reminderRepository.getReminderById(reminderId) ?: return@launch
            val snoozeMinutes = 5
            val snoozeTime = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000)
            val updated = reminder.copy(
                isSnoozed = true,
                snoozeTime = snoozeTime,
                updatedAt = System.currentTimeMillis()
            )
            reminderRepository.updateReminder(updated)
            ReminderScheduler.schedule(getApplication(), updated)
        }
    }

    fun deleteReminder(reminderId: Int) {
        viewModelScope.launch {
            val reminder = reminderRepository.getReminderById(reminderId) ?: return@launch
            ReminderScheduler.cancel(getApplication(), reminder)
            reminderRepository.deleteReminder(reminder)
        }
    }
}
