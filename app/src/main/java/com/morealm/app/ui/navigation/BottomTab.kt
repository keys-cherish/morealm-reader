package com.morealm.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomTab(
    val label: String,
    val icon: ImageVector,
    val route: String,
) {
    Home("首页", Icons.Default.Home, "home"),
    Shelf("书架", Icons.Default.LibraryBooks, "shelf"),
    Discover("发现", Icons.Default.Search, "search"),
    Library("图书馆", Icons.Default.LocalLibrary, "library"),
    Listen("听书", Icons.Default.Headphones, "listen"),
    Profile("我的", Icons.Default.Person, "profile"),
    ;

    companion object {
        /**
         * 底部胶囊实际展示的 tab。Listen / Library 暂时下架但枚举与页面代码保留，
         * 恢复展示只需从这里去掉过滤。
         */
        val visible: List<BottomTab> = entries.filter { it != Listen && it != Library }
    }
}
