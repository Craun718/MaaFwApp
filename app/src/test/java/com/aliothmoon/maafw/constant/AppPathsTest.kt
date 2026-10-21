package com.aliothmoon.maafw.constant

import android.content.Context
import android.os.Environment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AppPathsTest {
    @Test
    fun unavailableExternalStorageKeepsTheSharedPathForLaterRetry() {
        mockkStatic(Environment::class)
        try {
            every { Environment.getExternalStorageDirectory() } returns File("/storage/emulated/10")
            val context = mockk<Context>()
            every { context.getExternalFilesDir(null) } returns null
            every { context.packageName } returns "com.example.app"
            every { context.cacheDir } returns File("/data/user/10/com.example.app/cache")
            AppPaths.init(context)
            assertEquals(File("/storage/emulated/10/Android/data/com.example.app/files"), AppPaths.ROOT)
            assertEquals(File(AppPaths.ROOT, "log"), AppPaths.LOG_DIR)
        } finally {
            unmockkStatic(Environment::class)
        }
    }
}
