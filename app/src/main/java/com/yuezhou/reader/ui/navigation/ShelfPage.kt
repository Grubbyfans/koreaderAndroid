package com.yuezhou.reader.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 底部导航的一级栏目。页面模型只依赖此枚举，不反向依赖 MainActivity。 */
enum class Tab(val label: String, val icon: ImageVector) {
    书架("书库", bookTabIcon()),
    云端("云端", cloudTabIcon()),
    发现("发现", discoverTabIcon()),
    设置("设置", Icons.Outlined.Settings),
}

private fun discoverTabIcon() = tabOutline("Discover") {
    moveTo(22f, 12f)
    curveTo(22f, 17.5f, 17.5f, 22f, 12f, 22f)
    curveTo(6.5f, 22f, 2f, 17.5f, 2f, 12f)
    curveTo(2f, 6.5f, 6.5f, 2f, 12f, 2f)
    curveTo(17.5f, 2f, 22f, 6.5f, 22f, 12f)
    close()
    moveTo(15.8f, 8.2f)
    lineTo(13.7f, 13.7f)
    lineTo(8.2f, 15.8f)
    lineTo(10.3f, 10.3f)
    close()
}

private fun bookTabIcon() = tabOutline("OpenBook") {
    moveTo(12f, 6f)
    curveTo(9f, 4f, 6f, 4f, 3f, 5f)
    lineTo(3f, 19f)
    curveTo(6f, 18f, 9f, 18f, 12f, 20f)
    curveTo(15f, 18f, 18f, 18f, 21f, 19f)
    lineTo(21f, 5f)
    curveTo(18f, 4f, 15f, 4f, 12f, 6f)
    close()
    moveTo(12f, 6f)
    lineTo(12f, 20f)
}

private fun cloudTabIcon() = tabOutline("Cloud") {
    moveTo(18.8f, 10f)
    curveTo(18f, 6.8f, 15.4f, 4.5f, 12f, 4.5f)
    curveTo(9.2f, 4.5f, 6.8f, 6f, 5.6f, 8.5f)
    curveTo(3f, 8.8f, 1.5f, 11f, 1.5f, 13.6f)
    curveTo(1.5f, 16.5f, 3.8f, 18.7f, 6.5f, 18.7f)
    lineTo(18.6f, 18.7f)
    curveTo(21f, 18.7f, 22.5f, 16.8f, 22.5f, 14.5f)
    curveTo(22.5f, 12.2f, 21f, 10.4f, 18.8f, 10f)
    close()
}

private fun tabOutline(
    name: String,
    pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, pathBuilder = pathBuilder)
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

    data object Discover : ShelfPage {
        const val PROVIDER_ID = "zlibrary"
        override val key = "discover.zlibrary"
        override val tab = Tab.发现
    }

    data object Settings : ShelfPage {
        override val key = "settings"
        override val tab = Tab.设置
    }

    companion object {
        /** 书源只以 id 参与导航，新增/移除书源无需改动此模型。 */
        fun build(providerIds: List<String>): List<ShelfPage> = buildList {
            add(LocalShelf)
            val cloud = providerIds.filterNot { it == Discover.PROVIDER_ID }
            if (cloud.isEmpty()) add(CloudEmpty) else cloud.forEach { add(Cloud(it)) }
            add(Discover)
            add(Settings)
        }
    }
}
