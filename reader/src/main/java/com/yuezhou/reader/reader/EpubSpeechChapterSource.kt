package com.yuezhou.reader.reader

import android.content.Context
import android.net.Uri
import com.yuezhou.reader.core.speech.SpeechChapterSource
import com.yuezhou.reader.core.speech.SpeechPlan
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Opens EPUB chapters from the registered original independently of the WebView. */
class EpubSpeechChapterSource(context: Context, private val fileName: String, private val uri: Uri?) :
    SpeechChapterSource {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var stagingFile: File? = null
    private var closed = false

    override suspend fun next(after: SpeechPlan): SpeechPlan? = withContext(Dispatchers.IO) {
        val sourceUri = uri
        val epub = if (sourceUri == null) {
            File(EpubImporter.epubDir(appContext), fileName)
        } else {
            synchronized(lock) {
                check(!closed)
                stagingFile
            } ?: run {
                val staged = File.createTempFile("speech-", ".epub", appContext.cacheDir)
                try {
                    val input = appContext.contentResolver.openInputStream(sourceUri)
                        ?: throw IOException("无法读取朗读文件")
                    input.use {
                        staged.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    synchronized(lock) {
                        check(!closed)
                        stagingFile = staged
                    }
                    staged
                } catch (e: Exception) {
                    staged.delete()
                    throw e
                }
            }
        }
        EpubChapterTextSource.nextPlan(epub, after) { ensureActive() }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            stagingFile?.delete()
            stagingFile = null
        }
    }
}
