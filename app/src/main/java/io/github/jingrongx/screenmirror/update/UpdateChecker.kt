package io.github.jingrongx.screenmirror.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 检查更新 + 下载 APK（所有请求按「国内代理优先 → 直连兜底」依次尝试） */
class UpdateChecker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    /** 获取最新版本清单：先走 raw + 代理，全部失败再兜底 GitHub API */
    suspend fun fetchLatest(): UpdateManifest = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        for (prefix in UpdateConfig.PROXY_PREFIXES) {
            try {
                val body = httpGet(prefix + UpdateConfig.rawManifestUrl)
                val manifest = ProtocolUpdateJson.decode(body)
                return@withContext manifest
            } catch (e: Exception) {
                errors.add("${prefix.ifEmpty { "直连" }}：${e.message}")
            }
        }
        // raw 全部不可达时，兜底 GitHub Releases API（无 versionCode，用版本名比较）
        try {
            val body = httpGet(UpdateConfig.apiLatestUrl)
            val obj = json.parseToJsonElement(body).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: throw IOException("响应缺少 tag_name")
            val changelog = obj["body"]?.jsonPrimitive?.contentOrNull
            val apkUrl = obj["assets"]?.jsonArray
                ?.mapNotNull { it.jsonObject["browser_download_url"]?.jsonPrimitive?.contentOrNull }
                ?.firstOrNull { it.endsWith(".apk") }
                ?: throw IOException("Release 中没有 APK 附件")
            UpdateManifest(
                versionCode = 0,
                versionName = tag.removePrefix("v"),
                url = apkUrl,
                sha256 = null,
                changelog = changelog,
            )
        } catch (e: Exception) {
            throw IOException("无法获取更新信息（${errors.firstOrNull() ?: e.message}）")
        }
    }

    /** 下载 APK：按代理顺序尝试，校验 sha256（如有），带进度回调 */
    suspend fun downloadApk(
        manifest: UpdateManifest,
        onProgress: (read: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (manifest.url.isEmpty()) throw IOException("下载地址为空")
        val dir = context.getExternalFilesDir("update")
            ?: File(context.filesDir, "update").apply { mkdirs() }
        val fileName = manifest.url.substringAfterLast('/').ifEmpty { "update.apk" }
        val target = File(dir, fileName)
        var lastError: Exception? = null
        for (prefix in UpdateConfig.PROXY_PREFIXES) {
            try {
                downloadTo(prefix + manifest.url, target, manifest.sha256, onProgress)
                return@withContext target
            } catch (e: Exception) {
                lastError = e
                target.delete()
            }
        }
        throw IOException("下载失败：${lastError?.message ?: "网络不可用"}")
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "screen-mirror-app/${Build.VERSION.SDK_INT}")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw IOException("空响应")
        }
    }

    private fun downloadTo(
        url: String,
        target: File,
        sha256: String?,
        onProgress: (Long, Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "screen-mirror-app/${Build.VERSION.SDK_INT}")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("空响应")
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastNotify = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastNotify >= 200) {
                            lastNotify = now
                            onProgress(read, total)
                        }
                    }
                }
            }
            onProgress(read, if (total > 0) total else read)
            if (total > 0 && read != total) throw IOException("下载不完整（$read/$total）")
            sha256?.takeIf { it.isNotBlank() }?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IOException("文件校验失败（sha256 不匹配）")
                }
            }
        }
    }
}

/** JSON 解码辅助 */
private object ProtocolUpdateJson {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    fun decode(body: String): UpdateManifest = json.decodeFromString(UpdateManifest.serializer(), body)
}

/** APK 安装 */
object ApkInstaller {

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
