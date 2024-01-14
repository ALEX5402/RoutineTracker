package com.rendox.routinetracker.app

import android.app.Application
import com.rendox.routinetracker.core.data.di.completionHistoryDataModule
import com.rendox.routinetracker.core.data.di.completionTimeDataModule
import com.rendox.routinetracker.core.data.di.routineDataModule
import com.rendox.routinetracker.core.data.di.vacationDataModule
import com.rendox.routinetracker.core.database.di.completionTimeLocalDataModule
import com.rendox.routinetracker.core.database.di.habitLocalDataModule
import com.rendox.routinetracker.core.database.di.localDataSourceModule
import com.rendox.routinetracker.core.domain.di.completionHistoryDomainModule
import com.rendox.routinetracker.core.domain.di.completionTimeDomainModule
import com.rendox.routinetracker.core.domain.di.domainModule
import com.rendox.routinetracker.core.domain.di.habitDomainModule
import com.rendox.routinetracker.core.domain.di.streaksDomainModule
import com.rendox.routinetracker.feature.agenda.di.agendaScreenModule
import com.rendox.routinetracker.routine_details.di.routineDetailsModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class RoutineTrackerApp: Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RoutineTrackerApp)
            modules(
                localDataSourceModule,
                habitLocalDataModule,
                completionTimeLocalDataModule,
                routineDataModule,
                completionHistoryDataModule,
                completionTimeDataModule,
                vacationDataModule,

                domainModule,
                habitDomainModule,
                completionHistoryDomainModule,
                streaksDomainModule,
                completionTimeDomainModule,

                agendaScreenModule,
                routineDetailsModule,
            )
        }
    }
}