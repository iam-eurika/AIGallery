package com.lucrativeworm.seekframe.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechStatusTest {

    @Test
    fun `automatic prefers Android's model and falls back to the downloaded one`() {
        val auto = SpeechStatus(choice = SpeechChoice.AUTO, android = SystemModelStatus.AVAILABLE, downloaded = true)
        assertEquals(SpeechEngine.ANDROID, auto.engine)
        assertEquals(SpeechEngine.DOWNLOADED, auto.copy(android = SystemModelStatus.UNSUPPORTED).engine)
    }

    @Test
    fun `automatic fetches Android's model when offered, otherwise the app's`() {
        val auto = SpeechStatus(choice = SpeechChoice.AUTO, android = SystemModelStatus.DOWNLOADABLE)
        assertNull(auto.engine)
        assertEquals(SpeechEngine.ANDROID, auto.toFetch)
        assertEquals(SpeechEngine.DOWNLOADED, auto.copy(android = SystemModelStatus.UNSUPPORTED).toFetch)
    }

    @Test
    fun `an explicit choice is never swapped for the other engine`() {
        val android = SpeechStatus(choice = SpeechChoice.ANDROID, android = SystemModelStatus.UNSUPPORTED, downloaded = true)
        assertNull(android.engine)
        assertNull(android.toFetch)
        val app = SpeechStatus(choice = SpeechChoice.DOWNLOADED, android = SystemModelStatus.AVAILABLE)
        assertNull(app.engine)
        assertEquals(SpeechEngine.DOWNLOADED, app.toFetch)
    }

    @Test
    fun `nothing runs or downloads until the user chooses, or when off`() {
        val unasked = SpeechStatus(android = SystemModelStatus.AVAILABLE, downloaded = true)
        assertNull(unasked.engine)
        assertNull(unasked.toFetch)
        val off = unasked.copy(choice = SpeechChoice.OFF)
        assertNull(off.engine)
        assertNull(off.toFetch)
    }
}
