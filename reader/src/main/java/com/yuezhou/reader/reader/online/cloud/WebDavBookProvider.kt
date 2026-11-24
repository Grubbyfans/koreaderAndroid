package com.yuezhou.reader.reader.online.cloud

import android.content.Context
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.reader.CloudCredentials
import com.yuezhou.reader.reader.CloudSync

/** WebDAV 书源：委托现有 WebDavBackend / CloudSync，不触碰认证与云端协议。 */
class WebDavBookProvider(
    context: Context,
    cloudSync: CloudSync,
) : CloudBookProviderBase(
    context = context,
    cloudSync = cloudSync,
    id = "webdav",
    displayName = "WebDAV",
    backendKind = BackendKind.WebDav,
) {
    override fun isConfigured(credentials: CloudCredentials): Boolean =
        credentials.webdavRootUrl != null && credentials.webdavUsername != null
}
