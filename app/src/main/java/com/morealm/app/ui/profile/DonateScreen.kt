package com.morealm.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Donation page — author appreciation.
 *
 * Design intent (per discussion 2026-05-01):
 *  - 软件本体「永久免费 / 无广告 / 无内购 / 无付费功能」承诺写在卡片底部，
 *    避免让用户产生「点进来就是要被收钱」的反感。
 *  - 真诚口吻而非「服务器/域名烧钱」式卖惨 — MoRealm 是离线本地应用，
 *    没有真实的服务器开支，把它说成有就是失信。
 *  - 微信 / 支付宝 双码并列展示（不是 Tab 切换），用户扫习惯哪个用哪个，
 *    少一次手势就少一份流失。
 *
 * 二维码资源（TODO 作者后续放置）：
 *  替换两张占位图为真实付款码 PNG，路径建议：
 *      app/src/main/res/drawable/qr_wechat.png      (建议 1024×1024，纯白底)
 *      app/src/main/res/drawable/qr_alipay.png      (同上)
 *  然后把 [QrCodePlaceholder] 调用替换成：
 *      Image(painterResource(R.drawable.qr_wechat), contentDescription = "微信赞赏码",
 *            modifier = Modifier.size(200.dp))
 *  无需改动其它布局。占位图与真实图同样是 200.dp 见方，视觉位置不会跳。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateScreen(onBack: () -> Unit) {
    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("请作者喝杯咖啡") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            HeroBlock()

            Spacer(Modifier.height(28.dp))

            // 双码并列。在窄屏会自动垂直堆叠 — FlowRow 会更优雅，但额外引依赖
            // 不值当；用 Column 简单满足需求。
            DonateCodeCard(
                channel = "微信赞赏",
                hint = "扫一扫 · 微信红包 / 微信赞赏",
                accentColor = Color(0xFF07C160), // WeChat brand green
            )

            Spacer(Modifier.height(16.dp))

            DonateCodeCard(
                channel = "支付宝",
                hint = "扫一扫 · 支付宝转账",
                accentColor = Color(0xFF1677FF), // Alipay brand blue
            )

            Spacer(Modifier.height(28.dp))

            PromiseFooter()

            Spacer(Modifier.height(96.dp))
        }
    }
}

/** 顶部诚恳文案 — 不卖惨，不夸大开支，承认是业余项目。 */
@Composable
private fun HeroBlock() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 大爱心
        Surface(
            modifier = Modifier.size(72.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "MoRealm 是一个业余项目",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = buildString {
                append("如果它在某个深夜陪你读完了一本书，")
                append("或者它的某次更新刚好帮你解决了一个小问题——")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "愿意请作者喝杯咖啡吗？",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "你的鼓励会让下一个版本来得更快一点。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 单个支付渠道的卡片：渠道名 + 二维码 + 提示文字。
 * 二维码部分目前是 [QrCodePlaceholder]，作者后续替换为真实图。
 */
@Composable
private fun DonateCodeCard(channel: String, hint: String, accentColor: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 渠道标签 — 用渠道色块标识，比纯文字更易识别
            Surface(
                shape = MaterialTheme.shapes.small,
                color = accentColor.copy(alpha = 0.12f),
            ) {
                Text(
                    text = channel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            QrCodePlaceholder(channel = channel)

            Spacer(Modifier.height(12.dp))

            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * 二维码占位组件。
 *
 * 替换为真实图的步骤（作者）：
 *  1. 把微信 / 支付宝付款码 PNG 放到：
 *       app/src/main/res/drawable/qr_wechat.png
 *       app/src/main/res/drawable/qr_alipay.png
 *  2. 在 [DonateCodeCard] 内把 `QrCodePlaceholder(channel = channel)` 改为：
 *       Image(
 *         painter = painterResource(
 *           if (channel == "微信赞赏") R.drawable.qr_wechat else R.drawable.qr_alipay
 *         ),
 *         contentDescription = "$channel 二维码",
 *         modifier = Modifier.size(200.dp).clip(MaterialTheme.shapes.medium),
 *       )
 *  3. 删掉本组件即可（如果不再有别处用到）。
 *
 *  尺寸：200.dp 见方 — 接近物理 5cm，扫码距离 10–25cm 都能识别。
 */
@Composable
private fun QrCodePlaceholder(channel: String) {
    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.QrCode2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "请作者放置 $channel 二维码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // 这条提示只有作者会看到（因为开发版本才会保留占位），但提前
                // 写清楚替换路径可以避免「以后翻代码再回来找位置」。
                text = "res/drawable/qr_*.png",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 永久承诺脚注。粗字号小，但永远在。 */
@Composable
private fun PromiseFooter() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "MoRealm 的承诺",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            PromiseLine("软件本体永久免费")
            PromiseLine("永远不接广告")
            PromiseLine("永远不做内购，所有功能等同")
            PromiseLine("捐赠 ≠ 解锁，捐与不捐使用体验完全一致")
        }
    }
}

@Composable
private fun PromiseLine(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        // 朴素小圆点 — 比 ✓ 更克制，不喧宾夺主
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}
