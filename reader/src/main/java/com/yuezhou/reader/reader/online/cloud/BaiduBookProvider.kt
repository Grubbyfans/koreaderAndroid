package com.yuezhou.reader.reader.online.cloud

import android.content.Context
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.reader.CloudCredentials
import com.yuezhou.reader.reader.CloudSync

/** 百度网盘书源：委托现有 BaiduBackend / CloudSync，不触碰 OAuth 与云端协议。 */
class BaiduBookProvider(
    context: Context,
    cloudSync: CloudSync,
) : CloudBookProviderBase(
    context = context,
    cloudSync = cloudSync,
    id = "baidu",
    displayName = "百度网盘",
    backendKind = BackendKind.Baidu,
) {
    override fun isConfigured(credentials: CloudCredentials): Boolean =
        credentials.baiduAccessToken != null && credentials.baiduAppDir != null
}
