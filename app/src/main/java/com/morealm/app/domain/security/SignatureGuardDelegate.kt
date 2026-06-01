package com.morealm.app.domain.security

import android.content.Context

/**
 * 签名校验真实现的契约。
 *
 * 真实现（`SignatureGuardImpl`）只在打包构建本地存在，由 [SignatureGuard] 运行时按需委托。
 * 此契约与「无实现即放行」的安全默认行为构成对外可见部分。
 */
interface SignatureGuardDelegate {
    fun isOfficialSignature(context: Context): Boolean
    fun currentCertSha256(context: Context): String?
}
