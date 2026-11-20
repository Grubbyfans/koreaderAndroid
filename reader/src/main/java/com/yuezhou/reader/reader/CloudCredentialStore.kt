package com.yuezhou.reader.reader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 云端后端凭据模型，对齐方案 §13.3：凭据不落明文，经 [CredentialCrypto] 加密后存应用私有文件。
 *
 * WebDAV 与百度分别承载，同一时刻二者可选其一（与 BaiduPanSync 双后端语义一致）。
 */
@Serializable
data class CloudCredentials(
    /** WebDAV：根目录 URL（含协议与主机，不含尾部斜杠）。 */
    val webdavRootUrl: String? = null,
    /** WebDAV：用户名。 */
    val webdavUsername: String? = null,
    /** WebDAV：密码。 */
    val webdavPassword: String? = null,
    /** 百度：应用 AppKey（发起授权与换 token 用，换得 token 后仍保留以便后续续期）。 */
    val baiduAppKey: String? = null,
    /** 百度：应用 SecretKey。 */
    val baiduSecretKey: String? = null,
    /** 百度：应用目录 /apps/xxx。 */
    val baiduAppDir: String? = null,
    /** 百度：access_token。 */
    val baiduAccessToken: String? = null,
    /** 百度：token 过期时间（epoch 秒）。 */
    val baiduExpiresAt: Long? = null,
    /** 百度：账户稳定标识 uk（字符串承载，防 2^53 精度丢失）。 */
    val baiduUserId: String? = null,
    /** 百度：账户显示名。 */
    val baiduAccountName: String? = null,
)

/**
 * 云端凭据存储：读取/写入加密后的凭据文件。
 *
 * 文件位于 `files/cloud_credentials`（应用私有目录，不参与备份迁移），
 * 内容为 `CredentialCrypto` 加密后的 JSON。损坏或解密失败时返回空凭据，不抛异常。
 */
class CloudCredentialStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "cloud_credentials")

    private val json = Json { ignoreUnknownKeys = true }

    /** 读取凭据；文件不存在或解密失败返回空凭据。 */
    suspend fun load(): CloudCredentials = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext CloudCredentials()
            val plaintext = CredentialCrypto.decrypt(file.readText())
            json.decodeFromString<CloudCredentials>(plaintext)
        } catch (e: Exception) {
            CloudCredentials()
        }
    }

    /** 写入凭据（加密后落盘）。 */
    suspend fun save(credentials: CloudCredentials): Boolean = withContext(Dispatchers.IO) {
        try {
            file.writeText(CredentialCrypto.encrypt(json.encodeToString(credentials)))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 清空凭据（移除文件）。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        try {
            file.delete()
        } catch (e: Exception) {
            // 忽略
        }
    }
}
