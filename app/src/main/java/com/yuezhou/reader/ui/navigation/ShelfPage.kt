package com.yuezhou.reader.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 底部导航的一级栏目。页面模型只依赖此枚举，不反向依赖 MainActivity。 */
enum class Tab(val label: String, val icon: ImageVector) {
    书架("书库", Icons.AutoMirrored.Filled.List),
    云端("云端", cloudTabIcon()),
    设置("设置", Icons.Filled.Settings),
}

/** icons-core 不包含 Cloud，使用标准 Material Cloud 24dp 路径，避免新增图标依赖。 */
private fun cloudTabIcon(): ImageVector = ImageVector.Builder(
    name = "CloudTab",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(19.35f, 10.04f)
        curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
        curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
        curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
        curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
        horizontalLineTo(19f)
        curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
        curveTo(24f, 12.37f, 21.95f, 10.22f, 19.35f, 10.04f)
        close()
    }
}.build()

/**
 * 扁平页面模型：一级栏目与云端二级书源摊平为一维列表，供后续 Pager 直接消费。
 *
 * key 是跨进程重建、书源增删时的唯一稳定标识；不要用列表下标做导航依据。
 */
sealed interface ShelfPage {
    val key: String
    val tab: Tab

    data object LocalShelf : ShelfPage {
        override val key = "shelf.local"
        override val tab = Tab.书架
    }

    data class Cloud(val providerId: String) : ShelfPage {
        override val key get() = "cloud.$providerId"
        override val tab = Tab.云端
    }

    /** 全部书源被禁用时的云端占位页，保证一级「云端」仍可达且有空态。 */
    data object CloudEmpty : ShelfPage {
        override val key = "cloud.empty"
        override val tab = Tab.云端
    }

    data object Settings : ShelfPage {
        override val key = "settings"
        override val tab = Tab.设置
    }

    companion object {
        /** 书源只以 id 参与导航，新增/移除书源无需改动此模型。 */
        fun build(providerIds: List<String>): List<ShelfPage> = buildList {
            add(LocalShelf)
            if (providerIds.isEmpty()) add(CloudEmpty) else providerIds.forEach { add(Cloud(it)) }
            add(Settings)
        }
    }
}
