package com.rendox.routinetracker.core.testcommon.fakes.habit

import com.rendox.routinetracker.core.database.routine.HabitLocalDataSource
import com.rendox.routinetracker.core.model.Habit
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalTime

class HabitLocalDataSourceFake(
    private val habitData: HabitData
) : HabitLocalDataSource {

    override suspend fun getHabitById(routineId: Long): Habit =
        habitData.listOfHabits.value[(routineId - 1).toInt()]

    override suspend fun insertHabit(habit: Habit) {
        habitData.listOfHabits.update {
            it.toMutableList().apply { add(habit) }
        }
    }

    override suspend fun getAllHabits(): List<Habit> = habitData.listOfHabits.value

    override suspend fun updateDueDateSpecificCompletionTime(
        newTime: LocalTime, routineId: Long, dueDateNumber: Int
    ) {
        val existingCompletionTime = habitData.dueDateCompletionTimes.find {
            it.routineId == routineId && it.dueDateNumber == dueDateNumber
        }
        val newValue = DueDateCompletionTimeEntity(routineId, dueDateNumber, newTime)
        if (existingCompletionTime == null) {
            habitData.dueDateCompletionTimes =
                habitData.dueDateCompletionTimes.toMutableList().apply { add(newValue) }
        } else {
            val existingCompletionTimeIndex =
                habitData.dueDateCompletionTimes.indexOf(existingCompletionTime)
            habitData.dueDateCompletionTimes =
                habitData.dueDateCompletionTimes.toMutableList().apply {
                    set(existingCompletionTimeIndex, newValue)
                }
        }
    }

    override suspend fun getDueDateSpecificCompletionTime(
        routineId: Long, dueDateNumber: Int
    ): LocalTime? {
        return habitData.dueDateCompletionTimes.find {
            it.routineId == routineId && it.dueDateNumber == dueDateNumber
        }?.completionTime
    }
}