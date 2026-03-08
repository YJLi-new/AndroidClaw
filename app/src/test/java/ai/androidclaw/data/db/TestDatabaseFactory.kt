package ai.androidclaw.data.db

import android.content.Context
import androidx.room.Room

internal fun buildTestDatabase(context: Context): AndroidClawDatabase {
    return Room.inMemoryDatabaseBuilder(context, AndroidClawDatabase::class.java)
        .allowMainThreadQueries()
        .build()
}

