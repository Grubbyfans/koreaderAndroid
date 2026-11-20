package com.yuezhou.reader.reader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.yuezhou.reader.core.protocol.BookNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地目录扫描，对齐方案 §8.3「本地书籍」：
 * - 点击「检查本机书籍」才递归枚举授权目录（SAF 持久授权），正常进入书库不扫描；
 * - 只登记 EPUB；
 * - 同名（bookId 相同）冲突列出全部位置，不自动选一个。
 *
 * 扫描结果只读、不落盘、不修改书库；是否导入由上层决定。
 */
object LocalScanner {

    /** 一次扫描发现的一本书（含多个同名位置时合并为一条）。 */
    data class FoundBook(
        /** bookId（文件名去扩展名）。 */
        val bookId: String,
        /** 该 bookId 的所有位置（同名冲突时 >1）。 */
        val locations: List<Location>,
        /** 是否与本机书库已有 bookId 冲突。 */
        val alreadyInLibrary: Boolean,
    )

    /** 单个文件位置。 */
    data class Location(
        val displayName: String,
        val uri: Uri,
        val size: Long,
    )

    /** 扫描结果。 */
    data class ScanResult(
        val books: List<FoundBook>,
        /** 同名冲突（同名多个位置）的 bookId 集合。 */
        val conflicts: List<String>,
    )

    /**
     * 递归扫描授权目录树，收集所有 .epub 文件，按 bookId 分组。
     *
     * @param rootUri SAF 目录树 URI（来自 ACTION_OPEN_DOCUMENT_TREE 且已持久授权）。
     * @param existingIds 本机书库已有的 bookId 集合（用于标注「已在库」）。
     */
    suspend fun scan(
        context: Context,
        rootUri: Uri,
        existingIds: Set<String>,
    ): ScanResult = withContext(Dispatchers.IO) {
        val grouped = linkedMapOf<String, MutableList<Location>>()
        val root = DocumentFile.fromTreeUri(context, rootUri)
        if (root != null && root.canRead()) {
            walk(root, grouped)
        }

        val books = grouped.map { (bookId, locs) ->
            FoundBook(
                bookId = bookId,
                locations = locs.sortedBy { it.displayName },
                alreadyInLibrary = bookId in existingIds,
            )
        }
        val conflicts = books.filter { it.locations.size > 1 }.map { it.bookId }
        ScanResult(books, conflicts)
    }

    /** 递归遍历目录，收集 .epub 文件。 */
    private fun walk(dir: DocumentFile, out: MutableMap<String, MutableList<Location>>) {
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> walk(child, out)
                child.isFile && isEpub(child.name) -> {
                    val bookId = BookNames.localId(child.name ?: "").first ?: return
                    val loc = Location(
                        displayName = child.name ?: "",
                        uri = child.uri,
                        size = child.length(),
                    )
                    out.getOrPut(bookId) { mutableListOf() }.add(loc)
                }
            }
        }
    }

    private fun isEpub(name: String?): Boolean =
        name != null && name.lowercase().endsWith(".epub")
}
