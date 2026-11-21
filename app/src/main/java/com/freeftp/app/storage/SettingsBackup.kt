package com.freeftp.app.storage

import android.content.Context
import android.net.Uri
import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.RemotePath
import com.freeftp.core.ServerProfile
import com.freeftp.core.sftp.HostKeyStore
import com.freeftp.core.store.ServerProfileRepository
import org.json.JSONArray
import org.json.JSONObject

/** Outcome of an import, shown to the user in the settings screen. */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val hostKeysRestored: Int,
    val downloadFolderRestored: Boolean,
    val downloadFolderKnown: Boolean,
)

/**
 * Moves the whole configuration between devices as one JSON file: saved servers
 * (passwords and keys in **plaintext**), accepted SSH host keys, and the download
 * folder preference.
 *
 * Secrets cannot ride along in their at-rest form: they are wrapped by the Android
 * key store on this device and no other device can unwrap them. So an export is by
 * definition a plaintext copy — the caller owns warning the user, and the user owns
 * where the file lands.
 *
 * Import never deletes: profiles are merged by id (a matching id is replaced, an
 * unknown id is added), host keys are added when absent, and the download folder is
 * restored only when its permission grant can actually be taken — SAF grants do not
 * survive the trip, so this normally means "same device, after a reinstall".
 */
class SettingsBackup(
    private val context: Context,
    private val profiles: ServerProfileRepository,
    private val hostKeys: HostKeyStore,
    private val downloads: DownloadLocationStore,
) {

    fun exportTo(target: Uri): Int {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("app", "freeftp")
        root.put("exportedAt", System.currentTimeMillis())
        downloads.current.let { location ->
            if (location is DownloadLocation.UserFolder) {
                root.put("downloadFolder", location.treeUri.toString())
            }
        }
        val servers = JSONArray()
        profiles.load().forEach { profile -> servers.put(encodeProfile(profile)) }
        root.put("servers", servers)
        val keys = JSONArray()
        hostKeys.entries().forEach { (key, fingerprint) ->
            keys.put(JSONObject().put("host", key).put("fingerprint", fingerprint))
        }
        root.put("hostKeys", keys)
        context.contentResolver.openOutputStream(target, "wt")?.use { stream ->
            stream.write(root.toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("Could not open $target for writing")
        return servers.length()
    }

    fun importFrom(source: Uri): ImportResult {
        val root = context.contentResolver.openInputStream(source)?.use { stream ->
            JSONObject(String(stream.readBytes(), Charsets.UTF_8))
        } ?: error("Could not open $source for reading")

        val existing = profiles.load().associateBy { it.id }
        var replaced = 0
        var added = 0
        root.optJSONArray("servers")?.forEach { item ->
            val profile = decodeProfile(item) ?: return@forEach
            profiles.save(profile)
            if (profile.id in existing) replaced++ else added++
        }

        var keysRestored = 0
        root.optJSONArray("hostKeys")?.forEach { item ->
            val key = item.optString("host")
            val fingerprint = item.optString("fingerprint")
            if (key.isNotEmpty() && fingerprint.isNotEmpty() &&
                hostKeys.entries()[key] == null
            ) {
                parseHostPort(key)?.let { (host, port) ->
                    hostKeys.remember(host, port, fingerprint)
                    keysRestored++
                }
            }
        }

        var folderRestored = false
        val folder = root.optString("downloadFolder", "")
        val folderKnown = folder.isNotEmpty()
        if (folderKnown) {
            // The grant belongs to whichever app picked the folder, not to the file —
            // so this only succeeds on the same device (typically after a reinstall).
            folderRestored = try {
                downloads.useUserFolder(Uri.parse(folder))
                true
            } catch (_: Exception) {
                false
            }
        }

        return ImportResult(
            imported = replaced + added,
            skipped = root.optJSONArray("servers")?.length()?.minus(replaced + added) ?: 0,
            hostKeysRestored = keysRestored,
            downloadFolderRestored = folderRestored,
            downloadFolderKnown = folderKnown,
        )
    }

    // ---------------------------------------------------------------- serialization

    private fun encodeProfile(profile: ServerProfile): JSONObject = JSONObject().apply {
        put("id", profile.id)
        put("name", profile.name)
        put("protocol", profile.protocol.name)
        put("host", profile.host)
        put("port", profile.port)
        put("initialPath", profile.initialPath)
        put("passiveMode", profile.passiveMode)
        put("controlEncoding", profile.controlEncoding)
        put("showHiddenFiles", profile.showHiddenFiles)
        put("trustAllCertificates", profile.trustAllCertificates)
        put("connectTimeoutMillis", profile.connectTimeoutMillis)
        put("dataTimeoutMillis", profile.dataTimeoutMillis)
        when (val credentials = profile.credentials) {
            is Credentials.Anonymous -> put("auth", "anonymous")
            is Credentials.Password -> {
                put("auth", "password")
                put("username", credentials.username)
                put("password", credentials.password)
            }

            is Credentials.PrivateKey -> {
                put("auth", "key")
                put("username", credentials.username)
                put("privateKey", credentials.privateKey)
                credentials.passphrase?.let { put("passphrase", it) }
                credentials.password?.let { put("password", it) }
            }
        }
    }

    private fun decodeProfile(item: JSONObject): ServerProfile? {
        val protocol = Protocol.entries.firstOrNull { it.name == item.optString("protocol") }
            ?: return null
        val username = item.optString("username")
        val credentials = when (item.optString("auth")) {
            "anonymous" -> Credentials.Anonymous
            "password" -> Credentials.Password(username, item.optString("password"))
            "key" -> Credentials.PrivateKey(
                username = username,
                privateKey = item.optString("privateKey"),
                passphrase = item.optString("passphrase").takeIf { it.isNotEmpty() },
                password = item.optString("password").takeIf { it.isNotEmpty() },
            )

            else -> return null
        }
        return ServerProfile(
            id = item.optString("id").ifEmpty { return null },
            name = item.optString("name"),
            protocol = protocol,
            host = item.optString("host"),
            port = item.optInt("port", protocol.defaultPort),
            credentials = credentials,
            initialPath = item.optString("initialPath", RemotePath.ROOT),
            passiveMode = item.optBoolean("passiveMode", true),
            controlEncoding = item.optString("controlEncoding", "UTF-8"),
            showHiddenFiles = item.optBoolean("showHiddenFiles", false),
            trustAllCertificates = item.optBoolean("trustAllCertificates", false),
            connectTimeoutMillis = item.optInt("connectTimeoutMillis", 15_000),
            dataTimeoutMillis = item.optInt("dataTimeoutMillis", 30_000),
        )
    }

    private fun parseHostPort(key: String): Pair<String, Int>? {
        val split = key.lastIndexOf(':')
        if (split <= 0) return null
        val port = key.substring(split + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return key.substring(0, split) to port
    }

    private companion object {
        const val FORMAT = 1
    }
}

/** Applies [action] to every element of a [JSONArray], ignoring per-entry failures. */
private inline fun JSONArray.forEach(action: (JSONObject) -> Unit) {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        runCatching { action(item) }
    }
}
