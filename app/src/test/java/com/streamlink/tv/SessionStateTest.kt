package com.streamlink.tv

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateTest {
    @After fun restore() { Session.prefetchEnabled = { true } }

    @Test fun stateReportsHeadStartOnByDefault() {
        assertEquals(true, JSONObject(Session.stateJson()).getBoolean("prefetch"))
    }

    @Test fun stateFollowsTheProvider() {
        Session.prefetchEnabled = { false }
        assertEquals(false, JSONObject(Session.stateJson()).getBoolean("prefetch"))
    }
}
