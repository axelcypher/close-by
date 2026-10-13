package net.pendzialek.closeby

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * One SQLite connection, serialized by a lock. CloseBy has a handful of
 * devices, so this is simpler than a pool and avoids SQLITE_BUSY entirely.
 */
class Db(path: String) : AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private val lock = Any()

    init {
        connection.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA foreign_keys=ON")
        }
        migrate()
    }

    suspend fun <T> tx(block: Tx.() -> T): T = withContext(Dispatchers.IO) { txBlocking(block) }

    fun <T> txBlocking(block: Tx.() -> T): T = synchronized(lock) {
        connection.autoCommit = false
        try {
            Tx(connection).block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun migrate() = txBlocking {
        val current = one("PRAGMA user_version") { it.getInt(1) } ?: 0
        MIGRATIONS.drop(current).forEach { script ->
            script.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { exec(it) }
        }
        // PRAGMA does not take bind parameters
        connection.createStatement().use { it.execute("PRAGMA user_version = ${MIGRATIONS.size}") }
    }

    override fun close() = connection.close()

    companion object {
        /** Append only; each entry runs once, tracked by PRAGMA user_version. */
        private val MIGRATIONS = listOf(
            """
            CREATE TABLE persons (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                display_name TEXT,
                is_admin INTEGER NOT NULL DEFAULT 0,
                avatar BLOB,
                avatar_type TEXT,
                avatar_version INTEGER,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE devices (
                id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                person_id TEXT REFERENCES persons(id) ON DELETE CASCADE,
                token_hash TEXT NOT NULL UNIQUE,
                fcm_token TEXT,
                app_version INTEGER,
                status TEXT,
                last_seen INTEGER,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE frames (
                device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
                album_id TEXT,
                settings TEXT NOT NULL
            );
            CREATE TABLE frame_contacts (
                frame_id TEXT NOT NULL REFERENCES frames(device_id) ON DELETE CASCADE,
                person_id TEXT NOT NULL REFERENCES persons(id) ON DELETE CASCADE,
                position INTEGER NOT NULL,
                auto_answer INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (frame_id, person_id)
            );
            CREATE TABLE invites (
                code TEXT PRIMARY KEY,
                created_by TEXT NOT NULL,
                expires_at INTEGER NOT NULL
            );
            CREATE TABLE pairings (
                id TEXT PRIMARY KEY,
                code TEXT NOT NULL UNIQUE,
                poll_hash TEXT NOT NULL,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                device_id TEXT,
                person_id TEXT,
                token TEXT
            )
            """,
            """
            ALTER TABLE frames ADD COLUMN avatar BLOB;
            ALTER TABLE frames ADD COLUMN avatar_type TEXT;
            ALTER TABLE frames ADD COLUMN avatar_version INTEGER
            """,
        )
    }
}

class Tx(private val connection: Connection) {
    fun exec(sql: String, vararg args: Any?): Int =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
            statement.executeUpdate()
        }

    fun <T> query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { i, arg -> statement.setObject(i + 1, arg) }
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(map(rs)) }
            }
        }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = query(sql, *args, map = map).firstOrNull()
}

fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
