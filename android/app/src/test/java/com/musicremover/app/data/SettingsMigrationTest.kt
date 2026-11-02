package com.musicremover.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMigrationTest {
    @Test
    fun termuxUrlMigratesToTermux() {
        assertEquals("termux", migrateProcessingMode("http://127.0.0.1:8000"))
    }

    @Test
    fun everythingElseMigratesToServer() {
        assertEquals("server", migrateProcessingMode(null))
        assertEquals("server", migrateProcessingMode("http://localhost:8000"))
        assertEquals("server", migrateProcessingMode("http://192.168.1.100:8000"))
        assertEquals("server", migrateProcessingMode("http://"))
    }
}
