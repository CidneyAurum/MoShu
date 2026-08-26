package app.moshu.journal

import android.app.Application
import app.moshu.journal.data.db.AppDatabase
import app.moshu.journal.data.JournalRepository
import app.moshu.journal.data.settings.SettingsRepository
import app.moshu.journal.reminder.Notifications

class MoShuApp : Application() {

    lateinit var database: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var journal: JournalRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.build(this)
        settings = SettingsRepository(applicationContext)
        journal = JournalRepository(applicationContext, database, settings)
        Notifications.ensureChannel(this)
    }

    companion object {
        lateinit var instance: MoShuApp
            private set
    }
}
