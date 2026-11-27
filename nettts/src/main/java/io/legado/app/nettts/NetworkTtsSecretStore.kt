package io.legado.app.nettts

/**
 * 登录信息加密存储端口。
 *
 * 网络 TTS 规则的 `java.putLoginInfo`/`java.getLoginInfo` 需要落盘凭证，
 * 原版用 AES 加密后写 Room。静读侧由 app 层用 [com.yuezhou.reader.reader.CredentialCrypto] 实现，
 * 避免 nettts 依赖 reader 模块；未装配时不会写入任何凭证。
 */
interface NetworkTtsSecretStore {
    fun put(key: String, plainText: String)
    fun get(key: String): String?
    fun remove(key: String)
}
