package com.rendox.routinetracker.core.domain.completion_history

import com.rendox.routinetracker.core.data.completion_history.CompletionHistoryRepository
import com.rendox.routinetracker.core.data.vacation.VacationRepository
import com.rendox.routinetracker.core.domain.di.GetHabitUseCase
import com.rendox.routinetracker.core.logic.time.LocalDateRange
import com.rendox.routinetracker.core.logic.time.rangeTo
import com.rendox.routinetracker.core.model.HabitCompletionData
import com.rendox.routinetracker.core.model.Schedule
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext

class GetHabitCompletionDataUseCaseIndependentPeriods(
    private val getHabit: GetHabitUseCase,
    private val vacationRepository: VacationRepository,
    private val completionHistoryRepository: CompletionHistoryRepository,
    private val habitStatusComputer: HabitStatusComputer,
    private val defaultDispatcher: CoroutineContext,
) : GetHabitCompletionDataUseCase {
    override suspend operator fun invoke(
        habitId: Long,
        validationDate: LocalDate,
        today: LocalDate,
    ): HabitCompletionData = invoke(
        habitId = habitId,
        validationDates = validationDate..validationDate,
        today = today,
    ).values.first()

    override suspend operator fun invoke(
        habitId: Long,
        validationDates: LocalDateRange,
        today: LocalDate,
    ): Map<LocalDate, HabitCompletionData> = withContext(defaultDispatcher) {
        val habit = getHabit(habitId)
        val period = expandPeriodToScheduleBounds(
            requestedDates = validationDates,
            schedule = habit.schedule,
        )
        val completionHistory = completionHistoryRepository.getRecordsInPeriod(
            habitId = habitId,
            minDate = period.start,
            maxDate = period.endInclusive,
        )
        val vacationHistory = vacationRepository.getVacationsInPeriod(
            habitId = habitId,
            minDate = period.start,
            maxDate = period.endInclusive,
        )
        validationDates.associateWith { date ->
            val habitStatus = habitStatusComputer.computeStatus(
                validationDate = date,
                today = today,
                habit = habit,
                completionHistory = completionHistory,
                vacationHistory = vacationHistory,
            )
            val numOfTimesCompleted =
                completionHistory.find { it.date == date }?.numOfTimesCompleted ?: 0f
            HabitCompletionData(
                habitStatus = habitStatus,
                numOfTimesCompleted = numOfTimesCompleted,
            )
        }
    }

    companion object {
        fun expandPeriodToScheduleBounds(
            requestedDates: Iterable<LocalDate>,
            schedule: Schedule,
        ): LocalDateRange {
            val minDate = requestedDates.min()
            val requestedStart = when {
                schedule.startDate > minDate -> schedule.startDate
                else -> minDate
            }
            val schedulePeriodStart = when (schedule) {
                is Schedule.PeriodicSchedule -> schedule.getPeriodRange(requestedStart)!!.start
                else -> requestedStart
            }
            val scheduleEndDate = schedule.endDate
            val maxDate = requestedDates.max()
            val requestedEnd = when {
                scheduleEndDate != null && scheduleEndDate < maxDate ->
                    scheduleEndDate
                else -> maxDate
            }
            val schedulePeriodEnd = when (schedule) {
                is Schedule.PeriodicSchedule -> schedule.getPeriodRange(requestedEnd)!!.endInclusive
                else -> requestedEnd
            }
            return schedulePeriodStart..schedulePeriodEnd
        }
    }
}