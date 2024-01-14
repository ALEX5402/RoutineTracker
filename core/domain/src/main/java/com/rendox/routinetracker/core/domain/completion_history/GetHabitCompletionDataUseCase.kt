package com.rendox.routinetracker.core.domain.completion_history

import com.rendox.routinetracker.core.model.HabitCompletionData
import kotlinx.datetime.LocalDate

interface GetHabitCompletionDataUseCase {
    suspend operator fun invoke(
        habitId: Long,
        validationDate: LocalDate,
        today: LocalDate,
    ): HabitCompletionData

    suspend operator fun invoke(
        habitId: Long,
        validationDates: Iterable<LocalDate>,
        today: LocalDate,
    ): Map<LocalDate, HabitCompletionData>
}