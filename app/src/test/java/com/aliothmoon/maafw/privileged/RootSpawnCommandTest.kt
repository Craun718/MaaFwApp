package com.aliothmoon.maafw.privileged

import org.junit.Assert.assertTrue
import org.junit.Test

class RootSpawnCommandTest {
    @Test
    fun entersInitMountNamespaceButFallsBackWhenUnavailable() {
        val command = SuSpawner.wrapCommand("/x/liblauncher.so", "'/x/liblauncher.so' --token='t'")
        assertTrue(command.startsWith("if nsenter -t 1 -m -- true 2>/dev/null; then "))
        assertTrue(command.contains("nsenter -t 1 -m -- '/x/liblauncher.so' --token='t' >/dev/null 2>&1 &"))
        assertTrue(command.contains("else '/x/liblauncher.so' --token='t' >/dev/null 2>&1 & fi"))
    }
}
