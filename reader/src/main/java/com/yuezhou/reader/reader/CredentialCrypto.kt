package com.yuezhou.reader.reader

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据加密工具，对齐方案 §13.3：凭据不落明文，用 Android Keystore 保存密钥、
 * AES/GCM 加密后写入应用私有目录。
 *
 * - 密钥由 Android Keystore 生成并受系统保护，私钥不出设备、不可导出。
 * - 密文格式：`base64(IV) + ":" + base64(ciphertext)`，GCM 自带认证防篡改。
 */
object CredentialCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "yuezhou_cloud_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    /** 加密明文为 base64 密文（含 IV）。 */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
        val dataB64 = android.util.Base64.encodeToString(ciphertext, android.util.Base64.NO_WRAP)
        return "$ivB64:$dataB64"
    }

    /** 解密 base64 密文为明文；失败抛异常由调用方兜底。 */
    fun decrypt(payload: String): String {
        val parts = payload.split(':', limit = 2)
        require(parts.size == 2) { "凭据密文格式无效" }
        val iv = android.util.Base64.decode(parts[0], android.util.Base64.NO_WRAP)
        val ciphertext = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
