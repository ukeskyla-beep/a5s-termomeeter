package ee.ukesk.a5s.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CookSessionEntity::class,
        SampleEntity::class,
        KnownBaseEntity::class,
        KnownProbeEntity::class,
        CustomTargetEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cookDao(): CookDao

    abstract fun deviceDao(): DeviceDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v2 lisas sondi aadressi, et mitme sondi küpsetusi eristada. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cook_session ADD COLUMN probeAddress TEXT")
            }
        }

        /** v3 lisas seadmeregistri (baasid, sondid) ja omad sihttemperatuurid. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS known_base (
                        address TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS known_probe (
                        address TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        baseAddress TEXT,
                        addedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS custom_target (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        celsius INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "a5s.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
