package com.compras339.escanerdrive

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Comprueba si hay una Release más nueva en GitHub y descarga/instala el APK.
 * Convención de etiquetas de Release: v{versión}-build{número}  (ej. v1.1-build87).
 * El número de build es el versionCode del APK, así que basta compararlos.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    const val REPO = "compras339/mi-escaner-drive"
    const val ASSET_NAME = "EscanerCompras.apk"

    private val LATEST_URL = "https://github.com/$REPO/releases/latest"
    private val DOWNLOAD_URL = "https://github.com/$REPO/releases/latest/download/$ASSET_NAME"

    data class Info(val build: Int, val version: String, val downloadUrl: String)

    /** versionCode de la app instalada. */
    fun currentBuild(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info).toInt()
    }

    /**
     * Consulta la última Release sin usar la API (sin límites de peticiones):
     * GitHub redirige /releases/latest a /releases/tag/v1.1-build87 y leemos la etiqueta.
     */
    suspend fun fetchLatest(): Info? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "EscanerCompras")
            val location = conn.getHeaderField("Location") ?: ""
            conn.disconnect()
            val tag = location.substringAfterLast("/tag/", "")
            if (tag.isEmpty()) return@withContext null
            val m = Regex("""v?([^-]+)-build(\d+)""").find(tag) ?: return@withContext null
            Info(build = m.groupValues[2].toInt(), version = m.groupValues[1], downloadUrl = DOWNLOAD_URL)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprobar actualizaciones", e)
            null
        }
    }

    /** Descarga el APK con DownloadManager y, al terminar, abre el instalador. Lanza excepción si falla. */
    suspend fun downloadAndInstall(context: Context, info: Info, onProgress: (Int) -> Unit = {}) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Sin almacenamiento disponible")
        val target = File(dir, ASSET_NAME)
        if (target.exists()) target.delete()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("Escáner de Compras ${info.version}")
            .setDescription("Descargando actualización…")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, ASSET_NAME)
        val id = dm.enqueue(request)

        // Esperamos a que termine consultando el estado (evita registrar receivers)
        withContext(Dispatchers.IO) {
            while (true) {
                delay(600)
                val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: throw IllegalStateException("Descarga cancelada")
                cursor.use {
                    if (!it.moveToFirst()) throw IllegalStateException("Descarga cancelada")
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val done = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    if (total > 0) withContext(Dispatchers.Main) { onProgress((done * 100 / total).toInt()) }
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> return@withContext
                        DownloadManager.STATUS_FAILED -> {
                            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            throw IllegalStateException("Fallo de descarga (código $reason)")
                        }
                    }
                }
            }
        }

        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(install)
    }
}
