package com.yuezhou.reader.networktts

import android.content.Context
import com.yuezhou.reader.reader.CredentialCrypto
import io.legado.app.nettts.NetworkTtsAudioCache
import io.legado.app.nettts.NetworkTtsCookieStore
import io.legado.app.nettts.NetworkTtsLog
import io.legado.app.nettts.NetworkTtsPlayer
import io.legado.app.nettts.NetworkTtsRuntime
import io.legado.app.nettts.NetworkTtsSecretStore
import io.legado.app.nettts.NetworkTtsSynthesizer
import java.io.File
import java.security.MessageDigest

/**
 * 网络 TTS 运行时装配。
 *
 * nettts 模块刻意不依赖 app/reader：UA、上下文、缓存目录、日志出口都在这里注入。
 * 登录信息与持久 Cookie 使用独立 Keystore 别名加密，存放在应用私有目录。
 */
object NetworkTtsRuntimeBridge {

    private var installed = false

    /** 网络朗读缓存根目录（`filesDir/nettts`）。 */
    fun cacheRoot(context: Context): File =
        File(context.filesDir, "nettts").apply { mkdirs() }

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val appContext = context.applicationContext
        val credentialStore = EncryptedNetworkTtsStore(appContext)
        NetworkTtsRuntime.appContext = appContext
        NetworkTtsRuntime.secretStore = object : NetworkTtsSecretStore {
            override fun put(key: String, plainText: String) = credentialStore.putLogin(key, plainText)
            override fun get(key: String): String? = credentialStore.getLogin(key)
            override fun remove(key: String) = credentialStore.removeLogin(key)
        }
        NetworkTtsRuntime.cookieStore = object : NetworkTtsCookieStore {
            override fun get(domain: String): String? = credentialStore.getCookie(domain)
            override fun put(domain: String, cookie: String) = credentialStore.putCookie(domain, cookie)
            override fun remove(domain: String) = credentialStore.removeCookie(domain)
        }
        NetworkTtsLog.sink = { message, _ ->
            android.util.Log.i("NetworkTts", message)
        }
        installed = true
    }

    fun audioCache(context: Context): NetworkTtsAudioCache =
        NetworkTtsAudioCache(File(cacheRoot(context), "audio"))

    fun synthesizer(context: Context): NetworkTtsSynthesizer =
        NetworkTtsSynthesizer(audioCache(context))

    fun player(context: Context): NetworkTtsPlayer =
        NetworkTtsPlayer(context, cacheRoot(context))
}

/** 每个键独立加密落盘，避免单条损坏影响其他规则的登录状态。 */
private class EncryptedNetworkTtsStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "nettts/secrets").apply { mkdirs() }
    private val lock = Any()

    fun putLogin(key: String, plainText: String) = write("login:$key", plainText)
    fun getLogin(key: String): String? = read("login:$key")
    fun removeLogin(key: String) = removeValue("login:$key")

    fun getCookie(domain: String): String? = read("cookie:$domain")
    fun putCookie(domain: String, cookie: String) = write("cookie:$domain", cookie)
    fun removeCookie(domain: String) = removeValue("cookie:$domain")

    private fun read(key: String): String? = synchronized(lock) {
        val file = fileFor(key)
        if (!file.exists()) return@synchronized null
        runCatching { CredentialCrypto.decrypt(file.readText(), KEY_ALIAS) }
            .getOrElse { throw IllegalStateException("网络 TTS 凭证读取失败", it) }
    }

    private fun write(key: String, value: String) = synchronized(lock) {
        val target = fileFor(key)
        val temp = File(dir, "${target.name}.tmp")
        try {
            temp.writeText(CredentialCrypto.encrypt(value, KEY_ALIAS))
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun removeValue(key: String) = synchronized(lock) {
        fileFor(key).delete()
        Unit
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$name.secret")
    }

    private companion object {
        const val KEY_ALIAS = "yuezhou_network_tts_credentials"
    }
}
