package com.streamlink.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBackupRulesTest {
    @Test fun keepsSettingsAndData() {
        assertTrue(SettingsBackupRules.safeEntry("shared_prefs/streamlink.xml"))
        assertTrue(SettingsBackupRules.safeEntry("files/sport-photos/nfl.jpg"))
        assertTrue(SettingsBackupRules.safeEntry("files/city-photos/chicago/1.jpg"))
        assertTrue(SettingsBackupRules.safeEntry("files/match-answers.json"))
    }

    @Test fun refusesAnythingElse() {
        assertFalse(SettingsBackupRules.safeEntry("../shared_prefs/x.xml"))
        assertFalse(SettingsBackupRules.safeEntry("shared_prefs/../../x.xml"))
        assertFalse(SettingsBackupRules.safeEntry("/data/x.xml"))
        assertFalse(SettingsBackupRules.safeEntry("shared_prefs/x.txt"))
        assertFalse(SettingsBackupRules.safeEntry("shared_prefs/a/b.xml"))
        assertFalse(SettingsBackupRules.safeEntry("files/reports.txt"))
        assertFalse(SettingsBackupRules.safeEntry("files/sport-photos"))
        assertFalse(SettingsBackupRules.safeEntry("lib/evil.so"))
        assertFalse(SettingsBackupRules.safeEntry("files\\\\sport-photos\\\\x"))
        assertFalse(SettingsBackupRules.safeEntry(""))
    }
}
