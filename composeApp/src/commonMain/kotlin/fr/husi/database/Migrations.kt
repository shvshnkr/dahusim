@file:Suppress("ClassName")

package fr.husi.database

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@DeleteColumn.Entries(
    DeleteColumn(
        tableName = "proxy_entities",
        columnName = "nekoBean",
    ),
)
class SagerDatabase_Migration_2_3 : AutoMigrationSpec

object SagerDatabase_Migration_3_4 : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""ALTER TABLE `rules` ADD `clientType` TEXT NOT NULL DEFAULT ''""")
    }
}

object SagerDatabase_Migration_4_5 : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""ALTER TABLE `rules` ADD `clashMode` TEXT NOT NULL DEFAULT ''""")
    }
}

@DeleteColumn.Entries(
    DeleteColumn(
        tableName = "proxy_entities",
        columnName = "trojanGoBean",
    ),
    DeleteColumn(
        tableName = "rules",
        columnName = "ruleSet",
    ),
)
class SagerDatabase_Migration_5_6 : AutoMigrationSpec

object SagerDatabase_Migration_6_7 : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DELETE FROM proxy_entities WHERE type = " + ProxyEntity.TYPE_TROJAN_GO.toString())
        connection.execSQL("DELETE FROM proxy_entities WHERE type = " + ProxyEntity.TYPE_NEKO.toString())
    }
}

@DeleteColumn.Entries(
    DeleteColumn(
        tableName = "proxy_groups",
        columnName = "isSelector",
    ),
)
class SagerDatabase_Migration_12_13 : AutoMigrationSpec

@DeleteColumn.Entries(
    DeleteColumn(
        tableName = "proxy_entities",
        columnName = "uuid",
    ),
)
class SagerDatabase_Migration_14_15 : AutoMigrationSpec

object SagerDatabase_Migration_18_19 : Migration(18, 19) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `proxy_probe_states` (
                `profileId` INTEGER NOT NULL,
                `state` INTEGER NOT NULL,
                `lastCheckedAt` INTEGER NOT NULL,
                `lastOkAt` INTEGER NOT NULL,
                `lastFailAt` INTEGER NOT NULL,
                `failCountConsecutive` INTEGER NOT NULL,
                `successCountWindow` INTEGER NOT NULL,
                `ewmaDelayMs` INTEGER NOT NULL,
                `lastErrorClass` TEXT NOT NULL,
                `nextProbeAt` INTEGER NOT NULL,
                `sourcePriority` INTEGER NOT NULL,
                `lastTcpMs` INTEGER NOT NULL,
                `lastUrlMs` INTEGER NOT NULL,
                PRIMARY KEY(`profileId`)
            )
            """.trimIndent(),
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_proxy_probe_states_state` ON `proxy_probe_states` (`state`)",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_proxy_probe_states_nextProbeAt` ON `proxy_probe_states` (`nextProbeAt`)",
        )
    }
}