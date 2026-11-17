package io.nekohasekai.sagernet.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForkDatabaseTest {
    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), SagerDatabase::class.java,
    )

    @Test
    fun oldWarpProfilesMoveWithoutChangingOtherProfiles() {
        val name = "warp-migration-test"
        val warp = byteArrayOf(1, 2, 3, 4)
        val socks = byteArrayOf(5, 6, 7)
        migrations.createDatabase(name, 38).use { db ->
            db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, row(1, 31).apply {
                put("masqueBean", warp)
            })
            db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, row(2, ProxyEntity.TYPE_SOCKS).apply {
                put("socksBean", socks)
            })
        }
        migrations.runMigrationsAndValidate(
            name, 1002, true, SagerDatabase_Migration_38_1001, SagerDatabase_Migration_1001_1002,
        ).use { db ->
            db.query("SELECT type, warpMasqueBean, masqueBean, speed, tx, uuid FROM proxy_entities WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals(ProxyEntity.TYPE_WARP_MASQUE, it.getInt(0))
                assertArrayEquals(warp, it.getBlob(1))
                assertTrue(it.isNull(2))
                assertEquals(0L, it.getLong(3))
                assertEquals(123L, it.getLong(4))
                assertEquals("profile-1", it.getString(5))
            }
            db.query("SELECT type, socksBean, warpMasqueBean FROM proxy_entities WHERE id = 2").use {
                assertTrue(it.moveToFirst())
                assertEquals(ProxyEntity.TYPE_SOCKS, it.getInt(0))
                assertArrayEquals(socks, it.getBlob(1))
                assertTrue(it.isNull(2))
            }
        }
    }

    @Test
    fun speedMigrationPreservesBothMasqueTypes() {
        val name = "speed-migration-test"
        migrations.createDatabase(name, 1001).use { db ->
            db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, row(1, ProxyEntity.TYPE_MASQUE).apply {
                put("masqueBean", byteArrayOf(1))
            })
            db.insert("proxy_entities", SQLiteDatabase.CONFLICT_ABORT, row(2, ProxyEntity.TYPE_WARP_MASQUE).apply {
                put("warpMasqueBean", byteArrayOf(2))
            })
        }
        migrations.runMigrationsAndValidate(name, 1002, true, SagerDatabase_Migration_1001_1002).use { db ->
            db.query("SELECT type, masqueBean, warpMasqueBean, speed FROM proxy_entities ORDER BY id").use {
                assertTrue(it.moveToFirst())
                assertEquals(ProxyEntity.TYPE_MASQUE, it.getInt(0))
                assertArrayEquals(byteArrayOf(1), it.getBlob(1))
                assertEquals(0L, it.getLong(3))
                assertTrue(it.moveToNext())
                assertEquals(ProxyEntity.TYPE_WARP_MASQUE, it.getInt(0))
                assertArrayEquals(byteArrayOf(2), it.getBlob(2))
                assertEquals(0L, it.getLong(3))
            }
        }
    }

    @Test
    fun speedResultDoesNotOverwriteAnUpdatedProfileOrRecreateADeletedOne() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java).build()
        try {
            val dao = db.proxyDao()
            val original = ProxyEntity(groupId = 1).apply {
                putBean(SOCKSBean().apply { initializeDefaultValues(); serverAddress = "old.example" })
            }
            original.id = dao.addProxy(original)
            val edited = dao.getById(original.id)!!.apply {
                socksBean!!.serverAddress = "new.example"
                tx = 999
                ping = 42
                userOrder = 7
            }
            dao.updateProxy(edited)
            assertEquals(1, dao.updateSpeed(original.id, 123456))
            val saved = dao.getById(original.id)!!
            assertEquals("new.example", saved.socksBean!!.serverAddress)
            assertEquals(999L, saved.tx)
            assertEquals(42, saved.ping)
            assertEquals(7L, saved.userOrder)
            assertEquals(123456L, saved.speed)
            dao.deleteById(original.id)
            assertEquals(0, dao.updateSpeed(original.id, 654321))
            assertNull(dao.getById(original.id))
        } finally {
            db.close()
        }
    }

    private fun row(id: Long, type: Int) = ContentValues().apply {
        put("id", id)
        put("groupId", 1)
        put("type", type)
        put("userOrder", id)
        put("tx", 123)
        put("rx", 456)
        put("status", 0)
        put("ping", 0)
        put("uuid", "profile-$id")
    }
}
