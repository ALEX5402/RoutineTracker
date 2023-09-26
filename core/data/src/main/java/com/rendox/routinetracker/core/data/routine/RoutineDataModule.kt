package com.rendox.routinetracker.core.data.routine

import com.rendox.routinetracker.core.database.routine.RoutineLocalDataSource
import com.rendox.routinetracker.core.database.routine.RoutineLocalDataSourceImpl
import org.koin.dsl.module

val routineDataModule = module {

    single<RoutineLocalDataSource> {
        RoutineLocalDataSourceImpl(db = get(), dispatcher = get())
    }

    single<RoutineRepository> {
        RoutineRepositoryImpl(localDataSource = get())
    }
}