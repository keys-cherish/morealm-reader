package com.morealm.app.domain.source

import android.provider.Settings
import android.util.Base64
import com.morealm.app.MoRealmApp
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 书源登录信息的本地加密层。
 *
 * 设计目标：
 *   - 替换原先 [com.morealm.app.domain.entity.BookSource.putLoginInfo] 的明文存储，
 *     避免 root / 恶意应用直接读 CacheManager 看到用户名密码。
 *   - 与 Legado `BaseSource.getLoginInfo` 思路一致 —— 用 ANDROID_ID 派生 16 字节 key
 *     做 AES（设备绑定，跨设备恢复需要重新登录）。
 *   - **不**追求与 Legado 二进制兼容（不同设备 ANDROID_ID 不同，跨 App 也不可解）。
 *   - 兼容旧用户：旧版本写入的明文 JSON 在升级后**仍可读** —— 通过 [tryDecrypt] 返回 null
 *     时调用方 fallback 到原文。下次写入即升级为密文。
 *
 * 安全说明：
 *   - 这层加密能挡住旁观者 / 备份明文外泄，不是对抗具备 root 的攻击者（key 派生本身可逆）。
 *     如果用户机已 root 且攻击者跑 App 自己的进程，仍可拿到明文（Legado 也一样）。
 *   - 不打算用 Android Keystore 是因为：① 用户清缓存就丢登录态（无 backup），② 老设备
 *     兼容性差，③ Legado 的安全模型本身就是 ANDROID_ID 派生，迁移路径直观。
 */
object LoginCrypto {

    /** 加密文本的前缀魔数，让 [getLoginInfo] 能区分"新密文"与"旧明文"。 */
    private const val MAGIC = "AES1:"

    /** 16 字节 AES-128 key —— ANDROID_ID UTF-8 取前 16 字节，不足补 0x00。 */
    private val keyBytes: ByteArray by lazy {
        val raw = readAndroidId().encodeToByteArray()
        ByteArray(16) { i -> if (i < raw.size) raw[i] else 0 }
    }

    /**
     * IV 用 key 反向。固定 IV 在同 key 同明文下会产生相同密文，但登录信息本身无重放
     * 攻击场景，且这里的 IV 可逆推不构成额外风险（攻击者拿到 key 就直接解了）。
     */
    private val ivBytes: ByteArray by lazy { keyBytes.reversedArray() }

    @Suppress("HardwareIds")
    private fun readAndroidId(): String = try {
        val ctx = MoRealmApp.instance
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "morealm"
    } catch (_: Throwable) {
        // 测试 / 极端情况下没 Application 单例，使用一个稳定的 fallback 字串
        "morealm-fallback-key"
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return MAGIC + Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
    }

    /**
     * 解密带魔数前缀的密文。
     * @return 明文；不是 [MAGIC] 前缀或解密失败返回 null。调用方应在 null 时把 [value]
     *         本身当作明文（兼容旧数据）。
     */
    fun tryDecrypt(value: String): String? {
        if (!value.startsWith(MAGIC)) return null
        val payload = value.substring(MAGIC.length)
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            val plainBytes = cipher.doFinal(Base64.decode(payload, Base64.DEFAULT))
            String(plainBytes, Charsets.UTF_8)
        }.getOrNull()
    }
}
