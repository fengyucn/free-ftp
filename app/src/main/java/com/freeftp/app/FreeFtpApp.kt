package com.freeftp.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.freeftp.app.security.AndroidKeystoreCipher
import com.freeftp.app.storage.DownloadLocationStore
import com.freeftp.app.storage.SettingsBackup
import com.freeftp.app.transfer.TransferForegroundService
import com.freeftp.core.sftp.FileHostKeyStore
import com.freeftp.core.sftp.HostKeyStore
import com.freeftp.core.store.ServerProfileRepository
import com.freeftp.core.transfer.TransferState
import java.io.File
import java.security.Security
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * The app's whole dependency graph.
 *
 * FreeFTP has four collaborators and no need for a DI framework; a plain container keeps
 * startup instant and the wiring readable.
 */
class AppContainer(context: Context) {

    private val filesDir: File = context.filesDir

    val profiles: ServerProfileRepository by lazy {
        ServerProfileRepository(File(filesDir, "servers.dat"), AndroidKeystoreCipher.create())
    }

    val hostKeys: HostKeyStore by lazy { FileHostKeyStore(File(filesDir, "known_host_keys.txt")) }

    /** The connection the browser and the transfer queue share. */
    val session: SessionHolder by lazy { SessionHolder(hostKeys) }

    /** Where downloads land, and how to build a destination for each file. */
    val downloads: DownloadLocationStore = DownloadLocationStore(context)

    /** Exports/imports the whole configuration as one JSON file. */
    val backup: SettingsBackup by lazy {
        SettingsBackup(context, profiles, hostKeys, downloads)
    }
}

class FreeFtpApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Process-lifetime scope for application-level observers. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        installFullBouncyCastle()
        container = AppContainer(this)
        keepTransfersAlive()
    }

    /**
     * Starts the keep-alive service whenever the transfer queue gains active work. The
     * service stops itself once the queue drains. Watching the queue here rather than
     * hooking each enqueue call site means every path that adds transfers — browser
     * downloads, folder downloads, uploads — is covered by one observer.
     */
    private fun keepTransfersAlive() {
        appScope.launch {
            var alive = false
            container.session.transfers.transfers.collect { list ->
                val active = list.any { it.state == TransferState.QUEUED || it.state == TransferState.RUNNING }
                if (active && !alive) {
                    ContextCompat.startForegroundService(
                        this@FreeFtpApp,
                        Intent(this@FreeFtpApp, TransferForegroundService::class.java),
                    )
                }
                alive = active
            }
        }
    }

    /**
     * Replaces Android's cut-down `BC` provider with the full BouncyCastle bundled here.
     *
     * Android ships a repackaged, heavily trimmed BouncyCastle registered under the name
     * `BC`. SSHJ asks for that name by design, so without this it silently gets the
     * trimmed one and modern SSH key exchange dies with "no such algorithm: X25519 for
     * provider BC". Adding our copy is not enough on its own — `addProvider` is a no-op
     * when a provider of that name is already installed, so the platform one has to go
     * first. It is appended rather than inserted at the front so TLS keeps using
     * Conscrypt, which is both faster and better maintained.
     */
    private fun installFullBouncyCastle() {
        val installed = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (installed != null && installed.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as FreeFtpApp).container
