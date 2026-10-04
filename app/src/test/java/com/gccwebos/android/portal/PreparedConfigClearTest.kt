package com.gccwebos.android.portal

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PreparedConfigClearTest {
    @Test fun clearsOnlyOwnedTargetAndIsIdempotent() {
        val directory = Files.createTempDirectory("prepared-clear-test")
        try {
            val target = directory.resolve("prepared-connection.json")
            val unrelated = directory.resolve("unrelated")
            val crashStaged = directory.resolve(".prepared-crash.tmp")
            Files.write(target, byteArrayOf(1, 2, 3))
            Files.write(unrelated, byteArrayOf(4))
            Files.write(crashStaged, byteArrayOf(5, 6))
            val store = AtomicPreparedConfigStore(directory.toFile())
            store.clear(); store.clear()
            assertFalse(Files.exists(target))
            assertFalse("A process-exit residue is also owned credential material", Files.exists(crashStaged))
            assertArrayEquals(byteArrayOf(4), Files.readAllBytes(unrelated))
        } finally {
            Files.deleteIfExists(directory.resolve("prepared-connection.json"))
            Files.deleteIfExists(directory.resolve("unrelated"))
            Files.deleteIfExists(directory.resolve(".prepared-crash.tmp"))
            Files.deleteIfExists(directory)
        }
    }
    @Test fun deletionFailureIsNotReportedAsSuccessfulClear() {
        val directory = Files.createTempDirectory("prepared-clear-failure")
        val target = Files.createDirectory(directory.resolve("prepared-connection.json"))
        val child = Files.write(target.resolve("owned-test"), byteArrayOf(1))
        try {
            assertThrows(java.nio.file.DirectoryNotEmptyException::class.java) {
                AtomicPreparedConfigStore(directory.toFile()).clear()
            }
        } finally { Files.delete(child); Files.delete(target); Files.delete(directory) }
    }
    @Test fun refusesToTraverseUnexpectedScratchDirectory() {
        val directory = Files.createTempDirectory("prepared-clear-boundary")
        val unexpected = Files.createDirectory(directory.resolve(".prepared-unexpected.tmp"))
        val child = Files.write(unexpected.resolve("marker"), byteArrayOf(1))
        try {
            assertThrows(IllegalArgumentException::class.java) {
                AtomicPreparedConfigStore(directory.toFile()).clear()
            }
            assertTrue(Files.exists(child))
        } finally { Files.delete(child); Files.delete(unexpected); Files.delete(directory) }
    }
}
