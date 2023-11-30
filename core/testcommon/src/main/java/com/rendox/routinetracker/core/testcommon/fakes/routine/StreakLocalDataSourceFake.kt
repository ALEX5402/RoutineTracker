package com.rendox.routinetracker.core.testcommon.fakes.routine

import com.rendox.routinetracker.core.database.streak.StreakLocalDataSource
import com.rendox.routinetracker.core.model.Streak
import kotlinx.datetime.LocalDate

class StreakLocalDataSourceFake(
    private val routineData: RoutineData
) : StreakLocalDataSource {

    override suspend fun getAllStreaks(
        routineId: Long,
        afterDateInclusive: LocalDate?,
        beforeDateInclusive: LocalDate?,
    ): List<Streak> {
        return routineData.listOfStreaks
            .filter { streakEntity ->
                val streak = streakEntity.second
                streakEntity.first == routineId
                        && (afterDateInclusive == null || streak.endDate?.let { it >= afterDateInclusive } ?: true)
                        && (beforeDateInclusive == null || streak.startDate <= beforeDateInclusive)
            }
            .map {
                it.second.copy(
                    id = (routineData.listOfStreaks.indexOf(it) + 1).toLong()
                )
            }
            .sortedBy { it.startDate }
    }

    override suspend fun getStreakByDate(routineId: Long, dateWithinStreak: LocalDate): Streak? {
        val entry = routineData.listOfStreaks.firstOrNull {
            it.first == routineId && it.second.contains(dateWithinStreak)
        }
        return entry?.let {
            it.second.copy(id = (routineData.listOfStreaks.indexOf(it) + 1).toLong())
        }
    }

    override suspend fun insertStreak(streak: Streak, routineId: Long) {
        routineData.listOfStreaks = routineData.listOfStreaks.toMutableList().apply {
            add(routineId to streak)
        }
    }



    override suspend fun getLastStreak(routineId: Long): Streak? {
        return routineData.listOfStreaks
            .filter { it.first == routineId }
            .maxByOrNull { it.second.startDate }
            ?.let {
                it.second.copy(id = (routineData.listOfStreaks.indexOf(it) + 1).toLong())
            }
    }

    override suspend fun deleteStreakById(id: Long) {
        routineData.listOfStreaks =
            routineData.listOfStreaks.toMutableList().apply { removeAt((id - 1).toInt()) }
    }

    override suspend fun updateStreakById(id: Long, start: LocalDate, end: LocalDate?) {
        routineData.listOfStreaks =
            routineData.listOfStreaks.toMutableList().also {
                val oldValue = it[(id - 1).toInt()]
                it[(id - 1).toInt()] =
                    oldValue.copy(second = oldValue.second.copy(startDate = start, endDate = end))
            }
    }

    private fun Streak.contains(date: LocalDate): Boolean {
        val streakEnd = endDate
        return startDate <= date && (streakEnd == null || date <= streakEnd)
    }
}