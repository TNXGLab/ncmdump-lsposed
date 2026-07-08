package com.example.ncmdumplsposed.hook

import android.app.Activity
import android.os.FileObserver
import com.example.ncmdumplsposed.nativebridge.NativeNcmdump
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NcmdumpHookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        if (loadPackageParam.packageName != NETEASE_CLOUD_MUSIC_PACKAGE) {
            return
        }

        DownloadDirectoryHook.install()
    }

    private object DownloadDirectoryHook {
        private val installed = AtomicBoolean(false)
        private val fullScanScheduled = AtomicBoolean(false)
        private val observerStarted = AtomicBoolean(false)
        private val pendingPaths = Collections.synchronizedSet(mutableSetOf<String>())
        private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ncmdump-worker").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        @Volatile
        private var lastBurstScanAtMillis = 0L
        private var downloadDirectoryObserver: FileObserver? = null

        fun install() {
            if (!installed.compareAndSet(false, true)) {
                return
            }

            hookDownloadActivityResume()
            startDownloadDirectoryObserver()
            scheduleFullDownloadScan("module-start")
        }

        private fun hookDownloadActivityResume() {
            XposedHelpers.findAndHookMethod(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activityName = param.thisObject.javaClass.name
                    if (activityName.contains("Download", ignoreCase = true) ||
                        activityName.contains("LocalMusic", ignoreCase = true)
                    ) {
                        startDownloadDirectoryObserver()
                        scheduleBurstDownloadScans(activityName)
                    }
                }
            })
        }

        @Suppress("DEPRECATION")
        private fun startDownloadDirectoryObserver() {
            if (!observerStarted.compareAndSet(false, true)) {
                return
            }

            if (!DOWNLOAD_DIRECTORY.isDirectory) {
                observerStarted.set(false)
                return
            }

            downloadDirectoryObserver = object : FileObserver(
                DOWNLOAD_DIRECTORY.absolutePath,
                CLOSE_WRITE or MOVED_TO,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    if (path.isNullOrBlank() || !path.endsWith(".ncm", ignoreCase = true)) {
                        return
                    }

                    val file = File(DOWNLOAD_DIRECTORY, path)
                    scheduleDecrypt(file)
                }
            }.also { observer ->
                observer.startWatching()
            }
        }

        private fun scheduleFullDownloadScan(reason: String) {
            if (!fullScanScheduled.compareAndSet(false, true)) {
                return
            }

            try {
                executor.execute {
                    if (!DOWNLOAD_DIRECTORY.isDirectory) {
                        return@execute
                    }

                    val ncmFiles = DOWNLOAD_DIRECTORY.listFiles()
                        ?.asSequence()
                        ?.filter { file -> file.isFile && file.extension.equals("ncm", ignoreCase = true) }
                        ?.toList()
                        .orEmpty()

                    ncmFiles.forEach(::decryptIfNeeded)
                }
            } catch (error: RejectedExecutionException) {
                fullScanScheduled.set(false)
                log("full scan task rejected, reason=$reason", error)
            }
        }

        private fun scheduleBurstDownloadScans(reason: String) {
            val now = System.currentTimeMillis()
            if (now - lastBurstScanAtMillis < BURST_SCAN_COOLDOWN_MILLIS) {
                return
            }
            lastBurstScanAtMillis = now

            for (delaySeconds in BURST_SCAN_DELAYS_SECONDS) {
                try {
                    executor.schedule(
                        {
                            scanDownloadDirectory("burst:$reason:${delaySeconds}s")
                        },
                        delaySeconds,
                        TimeUnit.SECONDS,
                    )
                } catch (error: RejectedExecutionException) {
                    log("burst scan task rejected, reason=$reason", error)
                }
            }
        }

        private fun scanDownloadDirectory(reason: String) {
            if (!DOWNLOAD_DIRECTORY.isDirectory) {
                return
            }

            val ncmFiles = DOWNLOAD_DIRECTORY.listFiles()
                ?.asSequence()
                ?.filter { file -> file.isFile && file.extension.equals("ncm", ignoreCase = true) }
                ?.toList()
                .orEmpty()

            ncmFiles.forEach(::decryptIfNeeded)
        }

        private fun scheduleDecrypt(file: File) {
            if (!file.isFile || !file.extension.equals("ncm", ignoreCase = true)) {
                return
            }

            if (!pendingPaths.add(file.absolutePath)) {
                return
            }

            try {
                executor.execute {
                    try {
                        decryptIfNeeded(file)
                    } finally {
                        pendingPaths.remove(file.absolutePath)
                    }
                }
            } catch (error: RejectedExecutionException) {
                pendingPaths.remove(file.absolutePath)
                log("decrypt task rejected for ${file.absolutePath}", error)
            }
        }

        private fun decryptIfNeeded(file: File) {
            try {
                if (file.hasFreshOutput() || !file.isNcmHeader()) {
                    return
                }

                NativeNcmdump.decryptToSibling(file.absolutePath)
            } catch (throwable: Throwable) {
                log("decrypt failed for ${file.absolutePath}", throwable)
            }
        }

        private fun File.isNcmHeader(): Boolean {
            return runCatching {
                inputStream().use { input ->
                    val header = ByteArray(NCM_HEADER.size)
                    input.read(header) == NCM_HEADER.size && header.contentEquals(NCM_HEADER)
                }
            }.getOrDefault(false)
        }

        private fun File.hasFreshOutput(): Boolean {
            val basePath = absolutePath.substringBeforeLast('.', absolutePath)
            val newestOutputTime = sequenceOf(File("$basePath.flac"), File("$basePath.mp3"))
                .filter { output -> output.isFile }
                .map { output -> output.lastModified() }
                .maxOrNull() ?: return false
            return newestOutputTime >= lastModified()
        }
    }

    companion object {
        private const val NETEASE_CLOUD_MUSIC_PACKAGE = "com.netease.cloudmusic"
        private const val BURST_SCAN_COOLDOWN_MILLIS = 2 * 60 * 1000L
        private val BURST_SCAN_DELAYS_SECONDS = longArrayOf(5, 15, 30, 60)
        private val DOWNLOAD_DIRECTORY = File("/storage/emulated/0/Download/netease/cloudmusic/Music")
        private val NCM_HEADER = "CTENFDAM".toByteArray(Charsets.US_ASCII)

        private fun log(message: String) {
            XposedBridge.log("NcmdumpLsposed: $message")
        }

        private fun log(message: String, throwable: Throwable) {
            XposedBridge.log("NcmdumpLsposed: $message")
            XposedBridge.log(throwable)
        }
    }
}
