package com.nanote.fts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nanote.data.db.AppDatabase
import com.nanote.data.db.PageTextEntity
import com.nanote.data.db.PageTextDao
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FtsDiagTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun rawFts() = runBlocking {
        val dao: PageTextDao = db.pageTextDao()
        dao.insertAll(
            listOf(
                PageTextEntity(1, "p1", "nb1", "hello world"),
                PageTextEntity(2, "p2", "nb1", "مرحبا بالعالم")
            )
        )
        val a = dao.searchNotebooks("world*")
        println("FTS-DIAG world* => $a")
        val b = dao.searchNotebooks("مرحبا*")
        println("FTS-DIAG ar => $b")
        check(a.isNotEmpty()) { "FTS world* empty" }
        check(b.isNotEmpty()) { "FTS ar empty" }
    }
}
