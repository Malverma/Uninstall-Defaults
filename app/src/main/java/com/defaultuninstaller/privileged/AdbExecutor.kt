package com.defaultuninstaller.privileged

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Built-in replacement for Shizuku: pairs with this phone's own Wireless debugging (Android 11+)
 * and runs commands through adbd, which gives shell (uid 2000) privileges without another app.
 *
 * Once connected, it grants itself WRITE_SECURE_SETTINGS so later it can switch Wireless debugging
 * back on by itself after a reboot; pairing only has to happen once.
 */
class AdbExecutor(private val context: Context, private val scope: CoroutineScope) : PrivilegedExecutor {
    override val kind = BackendKind.ADB

    private val prefs = context.getSharedPreferences("adb", Context.MODE_PRIVATE)
    private val manager by lazy { LocalAdbManager(File(context.noBackupFilesDir, "adb")) }
    private val connectLock = Mutex()

    private val _status = MutableStateFlow<BackendStatus>(if (isSupported) BackendStatus.Unknown else BackendStatus.NotInstalled)
    val status: StateFlow<BackendStatus> = _status.asStateFlow()

    /** Why the last connection attempt failed, shown under the status. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Port of the "Pair device with pairing code" service while that dialog is open. */
    private val _pairingPort = MutableStateFlow<Int?>(null)
    val pairingPort: StateFlow<Int?> = _pairingPort.asStateFlow()

    private var pairingMdns: AdbMdns? = null

    val isPaired: Boolean get() = prefs.getBoolean(KEY_PAIRED, false)

    val canToggleWirelessDebugging: Boolean
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Reconnects quietly if this phone was paired before. */
    fun refresh() {
        if (!isSupported || !isPaired) {
            if (isSupported) _status.value = BackendStatus.NeedsPermission
            return
        }
        if (manager.isConnected) return
        scope.launch { connect() }
    }

    suspend fun connect(): BackendStatus = withContext(Dispatchers.IO) {
        connectLock.withLock {
            if (!isSupported) return@withLock BackendStatus.NotInstalled
            if (runCatching { manager.isConnected }.getOrDefault(false)) return@withLock _status.value
            _status.value = BackendStatus.Unknown
            val turnedOn = ensureWirelessDebuggingOn()
            val status = try {
                // Discovers the TLS port over mDNS; give adbd a moment if we just switched it on.
                if (manager.connectTls(context, if (turnedOn) 15_000 else 6_000)) {
                    _problem.value = null
                    BackendStatus.Ready("adb")
                } else {
                    _problem.value = "Wireless debugging is off or the phone isn't on Wi-Fi."
                    BackendStatus.NotRunning
                }
            } catch (e: AdbPairingRequiredException) {
                prefs.edit().putBoolean(KEY_PAIRED, false).apply()
                _problem.value = "This phone forgot the pairing. Pair again."
                BackendStatus.NeedsPermission
            } catch (t: Throwable) {
                _problem.value = "Couldn't connect: ${t.message ?: t.javaClass.simpleName}"
                BackendStatus.NotRunning
            }
            _status.value = status
            if (status is BackendStatus.Ready && !canToggleWirelessDebugging) {
                runShell("pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
            }
            status
        }
    }

    /** Watches for the pairing dialog; [pairingPort] fills in when it's open. */
    fun startPairingDiscovery() {
        if (!isSupported || pairingMdns?.isRunning == true) return
        _pairingPort.value = null
        pairingMdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
            _pairingPort.value = port.takeIf { it > 0 }
        }.also { it.start() }
    }

    fun stopPairingDiscovery() {
        pairingMdns?.stop()
        pairingMdns = null
        _pairingPort.value = null
    }

    /** Pairs using the 6-digit code from Settings › Wireless debugging › Pair device with pairing code. */
    suspend fun pair(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val port = _pairingPort.value
            ?: return@withContext Result.failure(IllegalStateException("Open \"Pair device with pairing code\" in Wireless debugging first."))
        runCatching {
            check(manager.pair("127.0.0.1", port, code.trim())) { "Pairing was rejected. Check the code and try again." }
            prefs.edit().putBoolean(KEY_PAIRED, true).apply()
        }.onSuccess {
            stopPairingDiscovery()
            // The pairing service needs a moment to hand over to the connect service.
            delay(500)
            connect()
        }.recoverCatching { throw IllegalStateException(friendlyPairError(it), it) }
    }

    override suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!manager.isConnected && connect() !is BackendStatus.Ready) {
            return@withContext ShellResult.error("Wireless debugging isn't connected. ${_problem.value.orEmpty()}".trim())
        }
        runShell(command)
    }

    override suspend fun setNotificationsEnabled(pkg: String, uid: Int, enabled: Boolean): ShellResult =
        exec(PrivilegedCli.notifCommand(context.applicationInfo.sourceDir, pkg, uid, enabled))

    /** Runs through adbd's `exec:` service (sh -c, no pty). Output is stdout and stderr merged. */
    private fun runShell(command: String): ShellResult = try {
        val stream = manager.openStream("exec:{ $command; } 2>&1; echo $EXIT_MARK$?")
        val watchdog = scope.launch(Dispatchers.IO) {
            delay(TIMEOUT_MS)
            runCatching { stream.close() }
        }
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            // libadb throws "Stream closed." instead of returning -1 when adbd closes first.
            val n = try {
                stream.read(chunk, 0, chunk.size)
            } catch (e: IOException) {
                if (stream.isClosed) -1 else throw e
            }
            if (n < 0) break
            output.write(chunk, 0, n)
        }
        val text = output.toByteArray().decodeToString()
        watchdog.cancel()
        runCatching { stream.close() }
        val mark = text.lastIndexOf(EXIT_MARK)
        if (mark < 0) {
            ShellResult(-1, text, "Command timed out or the connection dropped")
        } else {
            ShellResult(text.substring(mark + EXIT_MARK.length).trim().toIntOrNull() ?: -1, text.substring(0, mark), "")
        }
    } catch (t: Throwable) {
        _status.value = BackendStatus.NotRunning
        ShellResult.error("Wireless debugging: ${t.message ?: t.javaClass.simpleName}")
    }

    /** Returns true if it had to switch Wireless debugging on. Needs WRITE_SECURE_SETTINGS. */
    private fun ensureWirelessDebuggingOn(): Boolean {
        if (!canToggleWirelessDebugging) return false
        val cr = context.contentResolver
        return runCatching {
            if (Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) == 1) return false
            Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    private fun friendlyPairError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            t is IllegalStateException && msg.isNotEmpty() -> msg
            "refused" in msg.lowercase() -> "The pairing dialog closed. Open it again and use the new code."
            else -> "Pairing failed. Check the code and try again. ($msg)"
        }
    }

    companion object {
        val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        private const val KEY_PAIRED = "paired"
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val EXIT_MARK = "__UD_EXIT__"
        private const val TIMEOUT_MS = 120_000L
    }
}

/** Holds this app's ADB identity (an RSA key and self-signed certificate) and the adbd connection. */
private class LocalAdbManager(private val dir: File) : AbsAdbConnectionManager() {
    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val keyFile = File(dir, "key.pk8")
        val certFile = File(dir, "cert.der")
        if (keyFile.exists() && certFile.exists()) {
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            privateKey = pair.private
            certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(selfSignedCertificate(pair.public, pair.private).inputStream())
            dir.mkdirs()
            keyFile.writeBytes(privateKey.encoded)
            certFile.writeBytes(certificate.encoded)
        }
    }

    override fun getPrivateKey() = privateKey
    override fun getCertificate() = certificate
    override fun getDeviceName() = "Uninstall Defaults"
}

/**
 * Minimal DER-encoded, self-signed X.509 v3 certificate (SHA256withRSA, CN=Uninstall Defaults).
 * adbd only uses it to carry the public key over TLS, so nothing more is needed than this.
 */
private fun selfSignedCertificate(publicKey: PublicKey, privateKey: PrivateKey): ByteArray {
    fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { acc, b -> acc + b }
        val out = ByteArrayOutputStream().apply { write(tag) }
        when {
            body.size < 0x80 -> out.write(body.size)
            body.size < 0x100 -> { out.write(0x81); out.write(body.size) }
            else -> { out.write(0x82); out.write(body.size shr 8); out.write(body.size and 0xFF) }
        }
        out.write(body)
        return out.toByteArray()
    }
    fun seq(vararg parts: ByteArray) = tlv(0x30, *parts)
    fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    val utc = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun time(ms: Long) = tlv(0x17, utc.format(Date(ms)).toByteArray())

    val sha256WithRsa = seq(bytes(0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B), bytes(0x05, 0x00))
    val name = seq(tlv(0x31, seq(bytes(0x06, 0x03, 0x55, 0x04, 0x03), tlv(0x0C, "Uninstall Defaults".toByteArray()))))
    val now = System.currentTimeMillis()
    val tbs = seq(
        tlv(0xA0, tlv(0x02, bytes(2))),
        tlv(0x02, BigInteger.valueOf(now).toByteArray()),
        sha256WithRsa,
        name,
        seq(time(now - 86_400_000L), time(now + 20L * 365 * 86_400_000L)),
        name,
        publicKey.encoded,
    )
    val signature = Signature.getInstance("SHA256withRSA").run {
        initSign(privateKey)
        update(tbs)
        sign()
    }
    return seq(tbs, sha256WithRsa, tlv(0x03, bytes(0), signature))
}
