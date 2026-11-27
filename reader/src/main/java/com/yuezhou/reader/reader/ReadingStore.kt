package com.yuezhou.reader.reader

import android.content.Context
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.db.ReadingDao

/** 与现有 Store 一致，由 reader 模块负责数据库装配。 */
object ReadingStore {
    fun dao(context: Context): ReadingDao = AppDatabase.get(context.applicationContext).readingDao()
}
