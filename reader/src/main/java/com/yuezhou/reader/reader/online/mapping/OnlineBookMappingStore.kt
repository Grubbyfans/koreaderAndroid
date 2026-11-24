package com.yuezhou.reader.reader.online.mapping

import android.content.Context
import com.yuezhou.reader.reader.db.AppDatabase

/** 对外隐藏 Room 类型：本地书籍删除后由 app 层调用，使在线映射不再标记为已下载。 */
object OnlineBookMappingStore {
    suspend fun deactivateByFileName(context: Context, fileName: String) {
        AppDatabase.get(context).onlineBookMappingDao().deactivateByFileName(fileName)
    }
}
