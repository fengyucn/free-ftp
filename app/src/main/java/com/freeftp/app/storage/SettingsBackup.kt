package com.freeftp.app.storage

import android.content.Context
import android.net.Uri
import com.freeftp.core.Credentials
import com.freeftp.core.Protocol
import com.freeftp.core.RemotePath
import com.freeftp.core.ServerProfile
import com.freeftp.core.sftp.HostKeyStore
import com.freeftp.core.store.ServerProfileRepository
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** The file decrypted, but its contents did not verify — the password is wrong. */
class BadPasswordException : Exception("wrong password (or the file is corrupted)")

/** Outcome of an import, shown to the user in the settings screen. */
data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val hostKeysRestored: Int,
    val downloadFolderRestored: Boolean,
    val downloadFolderKnown: Boolean,
)

/**
 * Moves the whole configuration between devices as one password-encrypted JSON file:
 * saved servers, accepted SSH host keys, and the download folder preference.
 *
 * The file is an envelope: PBKDF2-HmacSHA256 stretches the password into an AES-256
 * key, and AES-GCM authenticates the payload — a wrong password fails the tag check
 * instead of yielding garbage, which is how [BadPasswordException] knows to blame the
 * password. Secrets sit **inside** the encrypted payload, since the at-rest form is
 * wrapped by one device's key store and no other device could unwrap it.
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

    /** How many server profiles are stored, for the export confirmation line. */
    fun countServers(): Int = profiles.load().size

    fun exportTo(target: Uri, password: String) {
        val payload = buildPayload()
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt, ITERATIONS)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ciphertext = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))

        val envelope = JSONObject()
            .put("format", FORMAT)
            .put("app", "freeftp")
            .put("exportedAt", System.currentTimeMillis())
            .put("kdf", KDF)
            .put("iterations", ITERATIONS)
            .put("salt", base64(salt))
            .put("nonce", base64(cipher.iv))
            .put("ciphertext", base64(ciphertext))
        context.contentResolver.openOutputStream(target, "wt")?.use { stream ->
            stream.write(envelope.toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("Could not open $target for writing")
    }

    fun importFrom(source: Uri, password: String): ImportResult {
        val root = context.contentResolver.openInputStream(source)?.use { stream ->
            JSONObject(String(stream.readBytes(), Charsets.UTF_8))
        } ?: error("Could not open $source for reading")

        if (root.optInt("format") != FORMAT || root.optString("ciphertext").isEmpty()) {
            error("Not a password-protected FreeFTP backup (format ${root.optInt("format")})")
        }
        val key = deriveKey(
            password,
            Base64.getDecoder().decode(root.getString("salt")),
            root.getInt("iterations"),
        )
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                key,
                javax.crypto.spec.GCMParameterSpec(
                    TAG_BITS,
                    Base64.getDecoder().decode(root.getString("nonce")),
                ),
            )
        }
        val plain = try {
            cipher.doFinal(Base64.getDecoder().decode(root.getString("ciphertext")))
        } catch (_: AEADBadTagException) {
            throw BadPasswordException()
        }
        val payload = JSONObject(String(plain, Charsets.UTF_8))

        val existing = profiles.load().associateBy { it.id }
        var replaced = 0
        var added = 0
        payload.optJSONArray("servers")?.forEach { item ->
            val profile = decodeProfile(item) ?: return@forEach
            profiles.save(profile)
            if (profile.id in existing) replaced++ else added++
        }

        var keysRestored = 0
        payload.optJSONArray("hostKeys")?.forEach { item ->
            val entry = item.optString("host")
            val fingerprint = item.optString("fingerprint")
            if (entry.isNotEmpty() && fingerprint.isNotEmpty() &&
                hostKeys.entries()[entry] == null
            ) {
                parseHostPort(entry)?.let { (host, port) ->
                    hostKeys.remember(host, port, fingerprint)
                    keysRestored++
                }
            }
        }

        var folderRestored = false
        val folder = payload.optString("downloadFolder", "")
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
            skipped = payload.optJSONArray("servers")?.length()?.minus(replaced + added) ?: 0,
            hostKeysRestored = keysRestored,
            downloadFolderRestored = folderRestored,
            downloadFolderKnown = folderKnown,
        )
    }

    // ---------------------------------------------------------------- payload

    /** Everything worth carrying to another device, unencrypted. */
    private fun buildPayload(): JSONObject {
        val payload = JSONObject()
        payload.put("exportedAt", System.currentTimeMillis())
        downloads.current.let { location ->
            if (location is DownloadLocation.UserFolder) {
                payload.put("downloadFolder", location.treeUri.toString())
            }
        }
        val servers = JSONArray()
        profiles.load().forEach { profile -> servers.put(encodeProfile(profile)) }
        payload.put("servers", servers)
        val keys = JSONArray()
        hostKeys.entries().forEach { (key, fingerprint) ->
            keys.put(JSONObject().put("host", key).put("fingerprint", fingerprint))
        }
        payload.put("hostKeys", keys)
        return payload
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance(KDF)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
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

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val FORMAT = 2
        const val KDF = "PBKDF2WithHmacSHA256"
        const val ITERATIONS = 600_000
        const val SALT_BYTES = 16
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** Applies [action] to every element of a [JSONArray], ignoring per-entry failures. */
private inline fun JSONArray.forEach(action: (JSONObject) -> Unit) {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        runCatching { action(item) }
    }
}
