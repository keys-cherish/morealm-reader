package com.morealm.app.ui.navigation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.presentation.reader.ExternalFileOpenViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 外部「打开方式」文件入口（透明）。系统文件管理器 / MT / 网盘 / QQ / 微信 用 MoRealm
 * 打开 txt/epub/pdf/mobi/azw3/zip/umd 时，VIEW intent 落到这里：
 *
 * 1. 拿 `intent.data` 的 Uri（content:// 临时权限 / file://）。
 * 2. [ExternalFileOpenViewModel.importAndOpen] 复制入私有存储 + 静默入库 → 拿 bookId。
 * 3. 跳 [MainActivity]（带 [ACTION_OPEN_BOOK] + bookId）打开阅读器，本 Activity finish。
 *
 * 透明主题（`Theme.MoRealm.Transparent`）+ 居中 loading，导入完即走，用户无感经过本页。
 * 失败（不支持 / 损坏 / 读失败）只 Toast 不跳。
 */
@AndroidEntryPoint
class FileOpenActivity : ComponentActivity() {

    private val vm: ExternalFileOpenViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = intent?.data
        if (uri == null) {
            AppLog.warn("FileOpen", "VIEW intent without data, finishing")
            finish()
            return
        }
        setContent {
            Box(
                Modifier.fillMaxSize().background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        lifecycleScope.launch {
            when (val r = vm.importAndOpen(uri)) {
                is ExternalFileOpenViewModel.Result.Ok -> {
                    startActivity(
                        Intent(this@FileOpenActivity, MainActivity::class.java).apply {
                            action = ACTION_OPEN_BOOK
                            putExtra(EXTRA_BOOK_ID, r.bookId)
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        },
                    )
                }
                is ExternalFileOpenViewModel.Result.Unsupported ->
                    toast(if (r.ext.isNotEmpty()) "暂不支持的格式：${r.ext}" else "暂不支持的文件格式")
                ExternalFileOpenViewModel.Result.Corrupted -> toast("文件损坏或格式无效")
                ExternalFileOpenViewModel.Result.Failed -> toast("打开失败，请重试")
            }
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_OPEN_BOOK = "com.morealm.app.OPEN_BOOK"
        const val EXTRA_BOOK_ID = "bookId"
    }
}
