package com.rendox.routinetracker.routine_details.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kizitonwose.calendar.core.atStartOfMonth
import com.kizitonwose.calendar.core.yearMonth
import com.rendox.routinetracker.core.data.completion_history.CompletionHistoryRepository
import com.rendox.routinetracker.core.data.habit.HabitRepository
import com.rendox.routinetracker.core.data.vacation.VacationRepository
import com.rendox.routinetracker.core.domain.completion_history.HabitStatusComputer
import com.rendox.routinetracker.core.domain.completion_history.InsertHabitCompletionUseCase
import com.rendox.routinetracker.core.domain.streak.contains
import com.rendox.routinetracker.core.domain.streak.getCurrentStreak
import com.rendox.routinetracker.core.domain.streak.getDurationInDays
import com.rendox.routinetracker.core.domain.streak.getLongestStreak
import com.rendox.routinetracker.core.logic.time.rangeTo
import com.rendox.routinetracker.core.model.DisplayStreak
import com.rendox.routinetracker.core.model.Habit
import com.rendox.routinetracker.core.model.HabitStatus
import com.rendox.routinetracker.core.model.Vacation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.todayIn
import java.time.YearMonth
import kotlin.coroutines.CoroutineContext

class RoutineCalendarViewModel(
    today: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    private val routineId: Long,
    private val habitRepository: HabitRepository,
    private val completionHistoryRepository: CompletionHistoryRepository,
    private val vacationRepository: VacationRepository,
    private val insertHabitCompletion: InsertHabitCompletionUseCase,
    private val defaultDispatcher: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    private val _habitFlow: MutableStateFlow<Habit?> = MutableStateFlow(null)
    val habitFlow: StateFlow<Habit?> = _habitFlow.asStateFlow()

    private val todayFlow = MutableStateFlow(today)

    private val _calendarDatesFlow = MutableStateFlow(emptyMap<LocalDate, CalendarDateData>())
    val calendarDatesFlow = _calendarDatesFlow.asStateFlow()

    private val streaksFlow = MutableStateFlow(emptyList<DisplayStreak>())

    private val _currentMonthFlow: MutableStateFlow<YearMonth> =
        MutableStateFlow(YearMonth.from(todayFlow.value.toJavaLocalDate()))
    val currentMonthFlow: StateFlow<YearMonth> = _currentMonthFlow.asStateFlow()

    val currentStreakDurationInDays: StateFlow<Int> = streaksFlow.map { streaks ->
        streaks.getCurrentStreak(today)?.getDurationInDays() ?: 0
    }.stateIn(
        scope = viewModelScope,
        initialValue = 0,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    val longestStreakDurationInDays: StateFlow<Int> = streaksFlow.map { streaks ->
        streaks.getLongestStreak()?.getDurationInDays() ?: 0
    }.stateIn(
        scope = viewModelScope,
        initialValue = 0,
        started = SharingStarted.WhileSubscribed(5_000),
    )

    private val completionHistoryFlow =
        MutableStateFlow(emptyList<Habit.CompletionRecord>())
    private val vacationHistoryFlow = MutableStateFlow(emptyList<Vacation>())

    init {
        viewModelScope.launch {
            val jobs = mutableListOf<Job>()

            val updateHabitJob = launch {
                _habitFlow.update { habitRepository.getHabitById(routineId) }
            }
            jobs.add(updateHabitJob)

            val updateCompletionHistoryJob = launch {
                completionHistoryFlow.update {
                    completionHistoryRepository.getRecordsInPeriod(habitId = routineId)
                }
            }
            jobs.add(updateCompletionHistoryJob)

            val updateVacationsJob = launch {
                vacationHistoryFlow.update {
                    vacationRepository.getVacationsInPeriod(habitId = routineId)
                }
            }
            jobs.add(updateVacationsJob)

            jobs.joinAll()
            updateMonthsWithMargin()
        }
    }

    private fun updateMonthsWithMargin(
        forceUpdate: Boolean = false
    ) = viewModelScope.launch(defaultDispatcher) {
        // delete all other months because the data may be outdated
        if (forceUpdate) {
            val start = _currentMonthFlow.value.minusMonths(NumOfMonthsToLoadAhead.toLong())
                .atStartOfMonth().toKotlinLocalDate()
            val end = _currentMonthFlow.value.plusMonths(NumOfMonthsToLoadAhead.toLong())
                .atEndOfMonth().toKotlinLocalDate()
            _calendarDatesFlow.update { calendarDates ->
                calendarDates.filterKeys { it in start..end }
            }
        }
        updateMonth(_currentMonthFlow.value, forceUpdate)
        for (i in 1..NumOfMonthsToLoadAhead) {
            updateMonth(_currentMonthFlow.value.plusMonths(i.toLong()), forceUpdate)
            updateMonth(_currentMonthFlow.value.minusMonths(i.toLong()), forceUpdate)
        }
    }


    /**
     * @param forceUpdate update the data even if it's already loaded
     */
    private suspend fun updateMonth(monthToUpdate: YearMonth, forceUpdate: Boolean = false) {
        if (!forceUpdate) {
            val dataForMonthIsAlreadyLoaded = _calendarDatesFlow.value.keys.any {
                it.toJavaLocalDate().yearMonth == monthToUpdate
            }
            if (dataForMonthIsAlreadyLoaded) return
        }

        val monthStart = monthToUpdate.atStartOfMonth().toKotlinLocalDate()
        val monthEnd = monthToUpdate.atEndOfMonth().toKotlinLocalDate()
        for (date in monthStart..monthEnd) {
            updateStatusForDate(date)
        }
    }

    private suspend fun updateStatusForDate(date: LocalDate) {
        val habitStatusComputer = HabitStatusComputer(
            habit = habitFlow.value!!,
            completionHistory = completionHistoryFlow.value,
            vacationHistory = vacationHistoryFlow.value,
            defaultDispatcher = defaultDispatcher,
        )
        val habitStatus = habitStatusComputer.computeStatus(
            validationDate = date,
            today = todayFlow.value,
        )
        val numOfTimesCompleted =
            completionHistoryFlow.value.find { it.date == date }?.numOfTimesCompleted ?: 0F

        val calendarDateData = CalendarDateData(
            status = habitStatus,
            includedInStreak = streaksFlow.value.any { it.contains(date) },
            numOfTimesCompleted = numOfTimesCompleted,
        )

        _calendarDatesFlow.update {
            it.toMutableMap().also { calendarDates ->
                calendarDates[date] = calendarDateData
            }
        }
    }

    fun onScrolledToNewMonth(newMonth: YearMonth) {
        _currentMonthFlow.update { newMonth }
        updateMonthsWithMargin()
    }

    fun onHabitComplete(completionRecord: Habit.CompletionRecord) = viewModelScope.launch {
        try {
            insertHabitCompletion(
                habitId = routineId,
                completionRecord = completionRecord,
                today = todayFlow.value,
            )
        } catch (e: InsertHabitCompletionUseCase.IllegalDateException) {
            // TODO display a snackbar
        }

        completionHistoryFlow.update {
            completionHistoryRepository.getRecordsInPeriod(habitId = routineId)
        }

        updateMonthsWithMargin(forceUpdate = true)
    }

// TODO update todayFlow when the date changes (in case the screen is opened at midnight)

    companion object {
        /**
         * The number of months that should be loaded ahead or behind the current month (depending
         * on the user's scroll direction). It's done for the user to see the pre-loaded data
         * when they scroll to the next month.
         */
        const val NumOfMonthsToLoadAhead = 3
    }
}

data class CalendarDateData(
    val status: HabitStatus,
    val includedInStreak: Boolean,
    val numOfTimesCompleted: Float,
)