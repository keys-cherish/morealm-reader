package com.morealm.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector

enum class BottomTab(
    val label: String,
    val icon: ImageVector,
    val route: String,
) {
    Shelf("书架", Icons.Default.LibraryBooks, "shelf"),
    Discover("发现", Icons.Default.Search, "search"),
    Listen("听书", Icons.Default.Headphones, "listen"),
    Profile("我的", Icons.Default.Person, "profile"),
}
