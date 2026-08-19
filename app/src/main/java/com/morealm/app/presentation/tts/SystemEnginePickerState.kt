package com.morealm.app.presentation.tts

import com.morealm.app.domain.tts.SystemTtsEngine

/** 阅读与听书界面共用的系统 TTS 引擎选择状态。 */
data class SystemEnginePickerState(
    val selectedPackage: String = "",
    val engines: List<SystemTtsEngine.EngineInfo> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 查询失败时不伪装成空列表，否则用户会误以为设备没有安装引擎，也无法重试。
 */
fun SystemTtsEngine.loadPickerState(): SystemEnginePickerState =
    getInstalledEnginesResult().fold(
        onSuccess = { SystemEnginePickerState(engines = it) },
        onFailure = {
            SystemEnginePickerState(errorMessage = "读取系统 TTS 引擎失败，请重试")
        },
    )
