package com.aliothmoon.maafw.constant

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * App 进程运行期路径单例
 *
 * [init] 在 Application.onCreate 一口气解析所有目录并定值；不保留 Context 引用
 *
 * 外部存储暂未挂载时仍保留应有路径，日志写入失败可降级，PI 解包失败交给界面提示和重试。
 * 不切到内部目录：提权进程必须与 App 看到同一份资源和日志。
 *
 * 与 RemoteBootTrace / ServiceBootLogger 同属进程级全局，不进 Koin：路径解析早于 DI 就绪
 */
object AppPaths {
    lateinit var ROOT: File
        private set

    lateinit var LOG_DIR: File
        private set

    lateinit var FOCUS_DIR: File
        private set

    lateinit var DEBUG_DIR: File
        private set

    /** 更新 APK 下载缓存；挂在内部 cacheDir 下，不属于 [AppFiles] 的外部私有目录树 */
    lateinit var UPDATES_CACHE_DIR: File
        private set

    fun init(context: Context) {
        val root = context.getExternalFilesDir(null) ?: File(
            Environment.getExternalStorageDirectory(), "Android/data/${context.packageName}/files",
        )
        ROOT = root
        LOG_DIR = File(root, AppFiles.LOG_DIR)
        FOCUS_DIR = File(LOG_DIR, AppFiles.FOCUS_DIR)
        DEBUG_DIR = File(root, AppFiles.DEBUG_DIR)
        UPDATES_CACHE_DIR = File(context.cacheDir, "updates")
    }
}
