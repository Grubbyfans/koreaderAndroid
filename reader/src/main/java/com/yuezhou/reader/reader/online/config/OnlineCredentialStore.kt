package com.yuezhou.reader.reader.online.config

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 在线书源凭证（方案 §6）：仅存于 Keystore 加密文件，绝不进入配置 JSON 或日志。
 * cookies 使用 "k=v; k2=v2" 形式保存，token 为可选。
 */
@Serializable
data class OnlineCredentials(
    val providerId: String = "zlibrary",
    val account: String,
    val cookies: String = "",
    val token: String? = null,
    val userId: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
)

/**
 * 在线书源凭证存储：按 provider + 账号派生独立 Keystore alias 与密文文件，
 * 复用 [com.yuezhou.reader.reader.CredentialCrypto] 的 AES/GCM 加密模式。
 */
class OnlineCredentialStore(
    private val context: Context,
    private val providerId: String = "zlibrary",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File
        get() = File(context.filesDir, "providers/credentials").also { it.mkdirs() }
    private val currentFile: File get() = File(dir, "${providerId}_current")

    private fun accountKey(account: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun aliasFor(account: String): String =
        "yuezhou_${providerId}_${accountKey(account)}".replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun fileFor(account: String): File = File(dir, "${providerId}_${accountKey(account)}.enc")

    suspend fun load(account: String): OnlineCredentials? = withContext(Dispatchers.IO) {
        try {
            val file = fileFor(account)
            if (!file.exists()) return@withContext null
            val plaintext = com.yuezhou.reader.reader.CredentialCrypto.decrypt(
                file.readText(),
                aliasFor(account),
            )
            json.decodeFromString<OnlineCredentials>(plaintext)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun save(credentials: OnlineCredentials): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = fileFor(credentials.account)
            val temp = File(dir, "${file.name}.tmp")
            val plaintext = json.encodeToString(credentials)
            temp.writeText(
                com.yuezhou.reader.reader.CredentialCrypto.encrypt(plaintext, aliasFor(credentials.account)),
            )
            if (!temp.renameTo(file)) return@withContext false
            currentFile.writeText(credentials.account)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun clear(account: String) = withContext(Dispatchers.IO) {
        try {
            fileFor(account).delete()
            if (currentAccount() == account) currentFile.delete()
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun currentAccount(): String? = try {
        currentFile.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
