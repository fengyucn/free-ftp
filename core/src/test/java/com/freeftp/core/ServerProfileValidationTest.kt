package com.freeftp.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Protocol defaults, profile validation and display ordering. */
class ServerProfileValidationTest {

    private fun profile(
        protocol: Protocol = Protocol.FTP,
        host: String = "example.com",
        port: Int = protocol.defaultPort,
        name: String = "Example",
        credentials: Credentials = Credentials.Password("bob", "hunter2"),
    ) = ServerProfile("id", name, protocol, host, port, credentials)

    @Test
    fun `each protocol has the conventional default port`() {
        assertEquals(21, Protocol.FTP.defaultPort)
        assertEquals(21, Protocol.FTPS_EXPLICIT.defaultPort)
        assertEquals(990, Protocol.FTPS_IMPLICIT.defaultPort)
        assertEquals(22, Protocol.SFTP.defaultPort)
    }

    @Test
    fun `a well formed profile validates`() {
        assertTrue(profile().isValid)
        assertEquals(emptyList<ValidationError>(), profile().validate())
    }

    @Test
    fun `blank host is rejected with a field level error`() {
        val errors = profile(host = "  ").validate()
        assertEquals(listOf("host"), errors.map { it.field })
        assertTrue(errors.single().message.isNotBlank())
    }

    @Test
    fun `out of range ports are rejected`() {
        assertEquals(listOf("port"), profile(port = 0).validate().map { it.field })
        assertEquals(listOf("port"), profile(port = 65_536).validate().map { it.field })
        assertTrue(profile(port = 65_535).isValid)
    }

    @Test
    fun `anonymous credentials are rejected for SFTP but fine for FTP`() {
        assertTrue(profile(protocol = Protocol.FTP, credentials = Credentials.Anonymous).isValid)
        val sftp = profile(protocol = Protocol.SFTP, port = 22, credentials = Credentials.Anonymous)
        assertFalse(sftp.isValid)
        assertEquals(listOf("credentials"), sftp.validate().map { it.field })
    }

    @Test
    fun `credential toString never reveals the secret`() {
        val password = Credentials.Password("bob", "s3cr3t-value")
        assertFalse(password.toString().contains("s3cr3t-value"))
        val key = Credentials.PrivateKey("bob", "-----BEGIN PRIVATE KEY-----abc", "passphrase-value")
        assertFalse(key.toString().contains("abc"))
        assertFalse(key.toString().contains("passphrase-value"))
    }

    @Test
    fun `display ordering puts directories first then case insensitive names`() {
        val entries = listOf(
            RemoteFile("/beta.txt", isDirectory = false),
            RemoteFile("/Alpha", isDirectory = true),
            RemoteFile("/alpha.txt", isDirectory = false),
            RemoteFile("/zeta", isDirectory = true),
            RemoteFile("/Beta.txt", isDirectory = false),
        )
        assertEquals(
            listOf("Alpha", "zeta", "alpha.txt", "Beta.txt", "beta.txt"),
            entries.sortedForDisplay().map { it.name },
        )
    }

    @Test
    fun `name descending keeps directories first`() {
        val entries = listOf(
            RemoteFile("/beta.txt", isDirectory = false),
            RemoteFile("/Alpha", isDirectory = true),
            RemoteFile("/alpha.txt", isDirectory = false),
            RemoteFile("/zeta", isDirectory = true),
        )
        assertEquals(
            listOf("zeta", "Alpha", "beta.txt", "alpha.txt"),
            entries.sortedForDisplay(EntrySort.NAME_DESC).map { it.name },
        )
    }

    @Test
    fun `time descending puts newest file first and unknown timestamps last`() {
        val entries = listOf(
            RemoteFile("/old.txt", isDirectory = false, modifiedEpochMillis = 1_000L),
            RemoteFile("/new.txt", isDirectory = false, modifiedEpochMillis = 3_000L),
            RemoteFile("/mid.txt", isDirectory = false, modifiedEpochMillis = 2_000L),
            RemoteFile("/unknown.txt", isDirectory = false, modifiedEpochMillis = null),
        )
        assertEquals(
            listOf("new.txt", "mid.txt", "old.txt", "unknown.txt"),
            entries.sortedForDisplay(EntrySort.TIME_DESC).map { it.name },
        )
    }

    @Test
    fun `time ascending puts oldest first and unknown timestamps last`() {
        val entries = listOf(
            RemoteFile("/old.txt", isDirectory = false, modifiedEpochMillis = 1_000L),
            RemoteFile("/new.txt", isDirectory = false, modifiedEpochMillis = 3_000L),
            RemoteFile("/unknown.txt", isDirectory = false, modifiedEpochMillis = null),
            RemoteFile("/mid.txt", isDirectory = false, modifiedEpochMillis = 2_000L),
        )
        assertEquals(
            listOf("old.txt", "mid.txt", "new.txt", "unknown.txt"),
            entries.sortedForDisplay(EntrySort.TIME_ASC).map { it.name },
        )
    }

    @Test
    fun `time sorting still groups directories first`() {
        val entries = listOf(
            RemoteFile("/newfile.txt", isDirectory = false, modifiedEpochMillis = 3_000L),
            RemoteFile("/newdir", isDirectory = true, modifiedEpochMillis = 2_000L),
        )
        assertEquals(
            listOf("newdir", "newfile.txt"),
            entries.sortedForDisplay(EntrySort.TIME_DESC).map { it.name },
        )
    }

    @Test
    fun `permission bits render like ls -l`() {
        assertEquals("rw-r-----", permissionsToString(0b110_100_000))
        assertEquals("rwxr-xr-x", permissionsToString(0b111_101_101))
        assertEquals("---------", permissionsToString(0))
    }
}
