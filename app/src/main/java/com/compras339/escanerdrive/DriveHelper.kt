package com.compras339.escanerdrive

import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Utilidades compartidas de Google Drive (carpetas por sede/día, búsqueda o creación de rutas). */
object DriveHelper {

    const val MIME_FOLDER = "application/vnd.google-apps.folder"

    /** Caché por sesión: "parentId/nombre" -> id de carpeta, para no repetir búsquedas. */
    private val folderCache = mutableMapOf<String, String>()

    /** Carpeta de día en formato DD-MM-AAAA. */
    fun todayFolderName(): String = SimpleDateFormat("dd-MM-yyyy", Locale.ROOT).format(Date())

    /**
     * Busca (o crea) la cadena de carpetas bajo rootId y devuelve el id de la última.
     * Debe llamarse desde un hilo de fondo (Dispatchers.IO).
     */
    fun ensureFolderPath(drive: Drive, rootId: String, path: List<String>): String {
        var parent = rootId
        for (name in path) {
            val cacheKey = "$parent/$name"
            folderCache[cacheKey]?.let { parent = it; continue }

            val safeName = name.replace("\\", "\\\\").replace("'", "\\'")
            val found = drive.files().list()
                .setQ("name = '$safeName' and mimeType = '$MIME_FOLDER' and '$parent' in parents and trashed = false")
                .setFields("files(id, name)")
                .setPageSize(5)
                .execute().files

            val id = if (!found.isNullOrEmpty()) {
                found.first().id
            } else {
                val meta = DriveFile().apply { this.name = name; mimeType = MIME_FOLDER; parents = listOf(parent) }
                drive.files().create(meta).setFields("id").execute().id
            }
            folderCache[cacheKey] = id
            parent = id
        }
        return parent
    }
}
