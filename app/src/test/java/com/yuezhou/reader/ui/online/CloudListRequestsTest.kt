package com.yuezhou.reader.ui.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudListRequestsTest {
    @Test
    fun olderCompletionCannotOverwriteNewerListOrError() {
        val requests = CloudListRequests()
        val old = requests.begin("webdav")
        val fresh = requests.begin("webdav")
        var visible = "cache"

        if (requests.isLatest("webdav", fresh)) visible = "fresh"
        if (requests.isLatest("webdav", old)) visible = "old response"

        assertEquals("fresh", visible)
        assertFalse(requests.isLatest("webdav", old))
    }

    @Test
    fun sourcesHaveIndependentRequestLifetimes() {
        val requests = CloudListRequests()
        val baidu = requests.begin("baidu")
        val webdav = requests.begin("webdav")
        val refreshedBaidu = requests.begin("baidu")
        assertFalse(requests.isLatest("baidu", baidu))
        assertTrue(requests.isLatest("baidu", refreshedBaidu))
        assertTrue(requests.isLatest("webdav", webdav))
    }
}
