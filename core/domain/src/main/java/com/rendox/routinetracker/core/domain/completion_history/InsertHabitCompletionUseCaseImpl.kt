package com.rendox.routinetracker.core.domain.completion_history

import com.rendox.routinetracker.core.data.completion_history.CompletionHistoryRepository
import com.rendox.routinetracker.core.domain.di.GetHabitUseCase
import com.rendox.routinetracker.core.model.Habit
import kotlinx.datetime.LocalDate

open class InsertHabitCompletionUseCaseImpl(
    private val completionHistoryRepository: CompletionHistoryRepository,
    private val getHabit: GetHabitUseCase
) : InsertHabitCompletionUseCase {

    /**
     * Inserts a completion record for a habit.
     * If the number of times completed is 0, the completion record is deleted.
     *
     * @throws IllegalDateException if the [completionRecord]'s date is earlier than the habit's
     * start date or later than the habit's end date (if it exists), or if the date is later
     * than today.
     */
    override suspend operator fun invoke(
        habitId: Long,
        completionRecord: Habit.CompletionRecord,
        today: LocalDate,
    ) {
        val habit = getHabit(habitId)
        if (completionRecord.date > today) throw IllegalDateException()
        if (completionRecord.date < habit.schedule.startDate) throw IllegalDateException()
        habit.schedule.endDate?.let { if (completionRecord.date > it) throw IllegalDateException() }

        if (completionRecord.numOfTimesCompleted > 0F) {
            completionHistoryRepository.insertCompletion(
                habitId = habitId,
                completionRecord = completionRecord,
            )
        } else {
            completionHistoryRepository.deleteCompletionByDate(
                habitId = habitId,
                date = completionRecord.date,
            )
        }
    }

    class IllegalDateException : Exception()
}