package com.rendox.routinetracker.core.domain.completion_history

import com.rendox.routinetracker.core.data.completion_history.CompletionHistoryRepository
import com.rendox.routinetracker.core.data.habit.HabitRepository
import com.rendox.routinetracker.core.data.vacation.VacationRepository
import com.rendox.routinetracker.core.logic.time.LocalDateRange
import com.rendox.routinetracker.core.logic.time.plusDays
import com.rendox.routinetracker.core.logic.time.rangeTo
import com.rendox.routinetracker.core.model.Habit
import com.rendox.routinetracker.core.model.HabitStatus
import com.rendox.routinetracker.core.model.Schedule
import com.rendox.routinetracker.core.model.Vacation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.coroutines.CoroutineContext


/**
 * The HabitComputeStatusUseCase class is responsible for computing the [HabitStatus] based on
 * various factors such as the habit's schedule, what dates are completed, and vacation periods.
 *
 * The result also depends on whether the validation date is in the past or in the future. For
 * example, when the habit has some backlog, but the validation date is in the past, the invoke
 * function will return not [HabitStatus.Backlog], but [HabitStatus.Skipped] instead. That's because
 * the user deliberately chose to skip the habit on that day. Nonetheless, if the validation date is
 * in the future, the invoke function will return [HabitStatus.Backlog] so that the user can adjust
 * their schedule to sort out this backlog later.
 *
 * Note that today is considered to be in the future.
 *
 * When the habit is on vacation, it's considered to be not due even if it's planned on schedule.
 * During the vacation, the user can still completed the habit, which will either sort out the
 * backlog (if any is present), or will complete the habit ahead.
 *
 * Each date depends on whether other dates are completed or not. For example, when one date has
 * status [HabitStatus.OverCompleted], the user will be able to skip the next planned date, which
 * will have already completed status. In the case of the previous example, with
 * backlog, the next over completed date will have status [HabitStatus.SortedOutBacklog].
 *
 * Backlog and completing ahead can be disabled by toggling the [Habit]'s [Schedule]'s properties.
 *
 * Period separation can be enabled or disabled as well. If enabled, the schedule deviation that
 * indicates backlog and how many times the habit was completed ahead will be reset at the start
 * of each period.
 *
 * @see [HabitStatus] for more details on what each status means, and when it is returned.
 */
class HabitComputeStatusUseCase(
    private val habitRepository: HabitRepository,
    private val vacationRepository: VacationRepository,
    private val completionHistoryRepository: CompletionHistoryRepository,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) {

    suspend operator fun invoke(
        habitId: Long,
        validationDate: LocalDate,
        today: LocalDate,
    ): HabitStatus = withContext(dispatcher) {
        val habit = habitRepository.getHabitById(habitId)

        if (validationDate < habit.schedule.startDate) return@withContext HabitStatus.NotStarted
        habit.schedule.endDate?.let { if (validationDate > it) return@withContext HabitStatus.Finished }

        val completedToday = completionHistoryRepository.getRecordByDate(habit.id!!, today) != null
        val scheduleDeviation = computeScheduleDeviation(
            habit = habit,
            currentDate = validationDate,
            today = today,
            completedToday = completedToday,
        )

        val numOfTimesCompletedOnValidationDate = completionHistoryRepository.getRecordByDate(
            habitId = habit.id!!,
            date = validationDate,
        )?.numOfTimesCompleted ?: 0f

        val habitIsOnVacationAtTheMomentOfValidationDate = vacationRepository.getVacationByDate(
            habitId = habit.id!!,
            date = validationDate,
        ) != null

        val numOfDueTimesOnValidationDate = habit.getNumOfDueTimesOnDate(
            date = validationDate, habitIsOnVacation = habitIsOnVacationAtTheMomentOfValidationDate
        )

        val validationDateIsDue = numOfDueTimesOnValidationDate > 0f
        if (validationDateIsDue) {
            val completedStatus = deriveCompletedStatusWhenPlanned(
                habit = habit,
                scheduleDeviation = scheduleDeviation,
                numOfTimesCompletedOnValidationDate = numOfTimesCompletedOnValidationDate,
                numOfDueTimesOnValidationDate = numOfDueTimesOnValidationDate,
            )
            if (completedStatus != null) return@withContext completedStatus

            println("validationDate $validationDate is due: $validationDate")
            val alreadyCompleted = checkIfIsAlreadyCompleted(
                habit, scheduleDeviation, numOfDueTimesOnValidationDate, validationDate, today
            )
            if (alreadyCompleted && validationDate < today) return@withContext HabitStatus.PastDateAlreadyCompleted
            if (alreadyCompleted && validationDate >= today) return@withContext HabitStatus.FutureDateAlreadyCompleted

            if (validationDate < today) {
                val wasCompletedLater = checkIfWasCompletedLater(
                    currentDate = validationDate,
                    numOfDueTimesOnCurrentDate = numOfDueTimesOnValidationDate,
                    habit = habit,
                )
                if (wasCompletedLater) return@withContext HabitStatus.CompletedLater
            }

            return@withContext if (validationDate < today) HabitStatus.Failed else HabitStatus.Planned
        } else {
            val backlogStatus = deriveBacklogStatus(
                habit,
                scheduleDeviation,
                numOfTimesCompletedOnValidationDate,
                validationDate,
                today,
                completedToday = completedToday,
            )
            if (backlogStatus != null) return@withContext backlogStatus

            if (numOfTimesCompletedOnValidationDate > 0f) {
                return@withContext HabitStatus.OverCompleted
            }
            if (habitIsOnVacationAtTheMomentOfValidationDate) return@withContext HabitStatus.OnVacation
            return@withContext if (validationDate < today) HabitStatus.Skipped else HabitStatus.NotDue
        }
    }

    private fun deriveCompletedStatusWhenPlanned(
        habit: Habit,
        scheduleDeviation: Double,
        numOfTimesCompletedOnValidationDate: Float,
        numOfDueTimesOnValidationDate: Float,
    ): HabitStatus? = when {
        numOfTimesCompletedOnValidationDate == numOfDueTimesOnValidationDate ->
            HabitStatus.Completed

        numOfTimesCompletedOnValidationDate > numOfDueTimesOnValidationDate -> {
            if (scheduleDeviation < 0.0 && habit.schedule.backlogEnabled) {
                HabitStatus.SortedOutBacklog
            } else {
                HabitStatus.OverCompleted
            }
        }

        numOfTimesCompletedOnValidationDate > 0f -> HabitStatus.PartiallyCompleted
        else -> null
    }

    private suspend fun deriveBacklogStatus(
        habit: Habit,
        scheduleDeviation: Double,
        numOfTimesCompletedOnValidationDate: Float,
        validationDate: LocalDate,
        today: LocalDate,
        completedToday: Boolean,
    ): HabitStatus? {
        if (scheduleDeviation < 0.0 && habit.schedule.backlogEnabled) {
            val numOfNotDueTimes =
                if (validationDate >= today) {
                    val startDate = if (completedToday) today.plusDays(1) else today
                    getNumOfNotDueTimesInPeriod(
                        habit = habit,
                        period = startDate..validationDate,
                    )
                } else {
                    0.0
                }
            if (scheduleDeviation <= -numOfNotDueTimes) {
                if (numOfTimesCompletedOnValidationDate > 0f) {
                    return HabitStatus.SortedOutBacklog
                }
                if (validationDate >= today) {
                    return HabitStatus.Backlog
                }
            }
        }
        return null
    }

    private suspend fun getNumOfNotDueTimesInPeriod(
        habit: Habit,
        period: LocalDateRange,
    ): Double {
        var numOfNotDueTimesInPeriod = 0.0

        val defaultNumOfDueTimesOnDate = when (habit) {
            is Habit.YesNoHabit -> 1F
        }

        val vacations: List<Vacation> = vacationRepository.getVacationsInPeriod(
            habitId = habit.id!!,
            minDate = period.start,
            maxDate = period.endInclusive,
        )

        for (date in period) {
            val habitIsOnVacation = vacations.any { it.containsDate(date) }
            val habitIsDue = if (habitIsOnVacation) {
                false
            } else {
                habit.schedule.isDue(validationDate = date)
            }
            if (!habitIsDue) numOfNotDueTimesInPeriod += defaultNumOfDueTimesOnDate
        }

        return numOfNotDueTimesInPeriod
    }

    private suspend fun checkIfIsAlreadyCompleted(
        habit: Habit,
        scheduleDeviation: Double,
        numOfDueTimesOnValidationDate: Float,
        validationDate: LocalDate,
        today: LocalDate,
    ): Boolean {
        if (!habit.schedule.completingAheadEnabled) return false

        if (habit.schedule.backlogEnabled) {
            val numOfDueTimes =
                if (validationDate >= today) {
                    getNumOfDueTimesInPeriod(
                        habit = habit,
                        period = today..validationDate,
                    )
                } else {
                    numOfDueTimesOnValidationDate.toDouble()
                }
            if (scheduleDeviation >= numOfDueTimes) {
                return true
            }
        } else {
            // when backlog is disabled, there may be a situation when the schedule deviation is
            // negative and the user can neither sort out the backlog nor complete ahead; the
            // following code is required to fix this bug
            val schedule = habit.schedule
            val currentDatePeriod: LocalDateRange? =
                if (schedule is Schedule.PeriodicSchedule && schedule.periodSeparationEnabled) {
                    schedule.getPeriodRange(currentDate = validationDate)
                } else {
                    null
                }

            val firstDateInPeriod = currentDatePeriod?.start
            val firstDateToLookFor =
                completionHistoryRepository.getFirstCompletedRecord(
                    habitId = habit.id!!,
                    minDate = firstDateInPeriod,
                    maxDate = validationDate.minus(DatePeriod(days = 1)),
                )?.date ?: return false
            val lastDateToLookFor = validationDate.minus(DatePeriod(days = 1))

            val completionRecords: List<Habit.CompletionRecord> =
                completionHistoryRepository.getRecordsInPeriod(
                    habitId = habit.id!!,
                    minDate = firstDateToLookFor,
                    maxDate = lastDateToLookFor,
                )
            val vacations: List<Vacation> = vacationRepository.getVacationsInPeriod(
                habitId = habit.id!!,
                minDate = firstDateToLookFor,
                maxDate = lastDateToLookFor,
            )

            var numOfDueTimes = 0.0
            var numOfTimesCompleted = 0.0

            var date = lastDateToLookFor
            while (date >= firstDateToLookFor) {
                numOfDueTimes += habit.getNumOfDueTimesOnDate(
                    date = date,
                    habitIsOnVacation = vacations.any { it.containsDate(date) },
                )
                numOfTimesCompleted +=
                    completionRecords.find { it.date == date }?.numOfTimesCompleted ?: 0f

                if (numOfTimesCompleted - numOfDueTimes >= numOfDueTimesOnValidationDate) {
                    return true
                }

                date = date.minus(DatePeriod(days = 1))
            }
        }
        return false
    }

    /**
     * @return positive value if the habit is ahead of schedule (completed even more than planned),
     * negative if behind (there is some backlog), 0 if on schedule
     */
    private suspend fun computeScheduleDeviation(
        habit: Habit,
        today: LocalDate,
        currentDate: LocalDate,
        completedToday: Boolean,
    ): Double {
        val actualDate = if (currentDate <= today) {
            currentDate.minus(DatePeriod(days = 1))
        } else {
            if (completedToday) {
                today
            } else {
                today.minus(DatePeriod(days = 1))
            }
        }

        val schedule = habit.schedule
        val period =
            if (schedule is Schedule.PeriodicSchedule && schedule.periodSeparationEnabled) {
                val lastPeriod = schedule.getPeriodRange(currentDate = actualDate)
                if (lastPeriod == null || currentDate !in lastPeriod) return 0.0
                lastPeriod.start..actualDate
            } else {
                schedule.startDate..actualDate
            }

        val numOfTimesCompleted = completionHistoryRepository.getNumOfTimesCompletedInPeriod(
            habitId = habit.id!!,
            minDate = period.start,
            maxDate = period.endInclusive,
        )
        val numOfDueTimes = getNumOfDueTimesInPeriod(habit, period)
        return numOfTimesCompleted - numOfDueTimes
    }

    /**
     * @return true if the habit wasn't completed on the date it was planned and introduced a
     * backlog that was sorted out later
     */
    private suspend fun checkIfWasCompletedLater(
        habit: Habit,
        currentDate: LocalDate,
        numOfDueTimesOnCurrentDate: Float,
    ): Boolean {
        if (!habit.schedule.backlogEnabled) return false

        val schedule = habit.schedule
        val currentDatePeriod: LocalDateRange? =
            if (schedule is Schedule.PeriodicSchedule && schedule.periodSeparationEnabled) {
                schedule.getPeriodRange(currentDate = currentDate)
            } else {
                null
            }

        val lastCompletedDate =
            completionHistoryRepository.getLastCompletedRecord(habit.id!!)?.date ?: return false
        val lastDateInPeriod = currentDatePeriod?.endInclusive

        val firstDateToLookFor = currentDate.plusDays(1)
        val lastDateToLookFor =
            if (lastDateInPeriod != null && lastDateInPeriod < lastCompletedDate) {
                lastDateInPeriod
            } else {
                lastCompletedDate
            }

        val completionRecords: List<Habit.CompletionRecord> =
            completionHistoryRepository.getRecordsInPeriod(
                habitId = habit.id!!,
                minDate = firstDateToLookFor,
                maxDate = lastDateToLookFor,
            )
        val vacations: List<Vacation> = vacationRepository.getVacationsInPeriod(
            habitId = habit.id!!,
            minDate = firstDateToLookFor,
            maxDate = lastDateToLookFor,
        )

        var numOfDueTimes = 0.0
        var numOfTimesCompleted = 0.0

        for (date in firstDateToLookFor..lastDateToLookFor) {
            numOfDueTimes += habit.getNumOfDueTimesOnDate(
                date = date,
                habitIsOnVacation = vacations.any { it.containsDate(date) },
            )
            numOfTimesCompleted +=
                completionRecords.find { it.date == date }?.numOfTimesCompleted ?: 0f

            val scheduleDeviation = numOfTimesCompleted - numOfDueTimes
            if (scheduleDeviation >= numOfDueTimesOnCurrentDate) return true
        }
        return false
    }

    private suspend fun getNumOfDueTimesInPeriod(
        habit: Habit,
        period: LocalDateRange,
    ): Double {
        var numOfDueTimesInPeriod = 0.0

        val vacationsInPeriod = vacationRepository.getVacationsInPeriod(
            habitId = habit.id!!,
            minDate = period.start,
            maxDate = period.endInclusive,
        )

        for (date in period) {
            numOfDueTimesInPeriod += habit.getNumOfDueTimesOnDate(
                date = date,
                habitIsOnVacation = vacationsInPeriod.any { it.containsDate(date) },
            )
        }

        return numOfDueTimesInPeriod
    }

    private fun Habit.getNumOfDueTimesOnDate(
        date: LocalDate,
        habitIsOnVacation: Boolean,
    ): Float {
        val numOfDueTimes = when (this) {
            is Habit.YesNoHabit -> {
                val dueOnSchedule = if (habitIsOnVacation) {
                    false
                } else {
                    schedule.isDue(validationDate = date)
                }
                if (dueOnSchedule) 1f else 0f
            }
        }
        return numOfDueTimes
    }
}