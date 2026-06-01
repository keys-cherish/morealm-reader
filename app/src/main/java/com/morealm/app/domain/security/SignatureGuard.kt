package com.morealm.app.domain.security

import android.content.Context

/**
 * APK 签名自校验入口。
 *
 * 真实校验由仅在打包构建本地存在的实现承担，运行时通过 [SignatureGuardDelegate] 按需委托。
 * 此入口只保留 API 形状与「无实现即放行」的安全默认——缺少实现时按官方/放行处理，不影响
 * 公开仓库与 CI 的可编译性及调试。
 */
object SignatureGuard {

    private val delegate: SignatureGuardDelegate? by lazy {
        runCatching {
            Class.forName("com.morealm.app.domain.security.SignatureGuardImpl")
                .getDeclaredField("INSTANCE")
                .get(null) as? SignatureGuardDelegate
        }.getOrNull()
    }

    /** true = 官方签名 / 无法判定 / 无本地实现（安全放行）。结果由实现侧缓存，可在热路径调用。 */
    fun isOfficialSignature(context: Context): Boolean =
        delegate?.isOfficialSignature(context) ?: true

    /** 当前安装包首证书 SHA-256（大写 hex）；无本地实现返回 null。 */
    fun currentCertSha256(context: Context): String? =
        delegate?.currentCertSha256(context)
}
