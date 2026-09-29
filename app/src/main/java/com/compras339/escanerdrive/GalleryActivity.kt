package com.compras339.escanerdrive

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.material.appbar.MaterialToolbar
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Galería de escaneos leída desde Google Drive.
 *  - Usuario normal: ve solo lo que subió su cuenta con la app (permiso drive.file).
 *  - Usuario maestro (MASTER_EMAIL): pide además drive.readonly y ve TODO lo que hay
 *    en la carpeta, con filtro por sede.
 */
class GalleryActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GalleryActivity"
        private const val THUMB_MAX_PX = 480
    }

    data class DriveImage(
        val id: String,
        val name: String,
        val createdMillis: Long,
        val webViewLink: String?
    )

    private lateinit var grid: GridView
    private lateinit var progress: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: GalleryAdapter
    private lateinit var spinnerSede: Spinner

    private var driveService: Drive? = null
    private var isMaster = false
    private var allItems: List<DriveImage> = emptyList()

    /** Pantalla de consentimiento cuando el maestro aún no ha concedido drive.readonly. */
    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                loadImages(forceRefresh = false)
            } else {
                progress.visibility = View.GONE
                tvEmpty.text = "Sin permiso de lectura de Drive no se puede mostrar la galería completa."
                tvEmpty.visibility = View.VISIBLE
            }
        }
    private val memoryCache = LruCache<String, Bitmap>(40)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { loadImages(forceRefresh = true) }

        grid = findViewById(R.id.gridImages)
        progress = findViewById(R.id.progressGallery)
        tvEmpty = findViewById(R.id.tvEmpty)

        spinnerSede = findViewById(R.id.spinnerSede)
        val sedeOptions = listOf("Todas las sedes") + MainActivity.Sede.values().map { it.label }
        spinnerSede.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sedeOptions)
        spinnerSede.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = applyFilter()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        adapter = GalleryAdapter()
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val link = adapter.items[position].webViewLink
            if (link != null) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            } else {
                Toast.makeText(this, "Sin enlace disponible", Toast.LENGTH_SHORT).show()
            }
        }

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "Inicia sesión primero", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        isMaster = account.email.equals(MainActivity.MASTER_EMAIL, ignoreCase = true)
        toolbar.title = if (isMaster) "Todos los escaneos" else "Mis escaneos"
        driveService = buildDrive(account)
        loadImages(forceRefresh = false)
    }

    private fun buildDrive(account: GoogleSignInAccount): Drive {
        // El maestro necesita drive.readonly para ver archivos subidos por otras cuentas.
        val scopes = if (isMaster) listOf(DriveScopes.DRIVE_FILE, DriveScopes.DRIVE_READONLY)
                     else listOf(DriveScopes.DRIVE_FILE)
        val credential = GoogleAccountCredential
            .usingOAuth2(this, scopes)
            .apply { selectedAccount = account.account }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("DocScanner")
            .build()
    }

    private fun loadImages(forceRefresh: Boolean) {
        val drive = driveService ?: return
        progress.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE

        if (forceRefresh) {
            memoryCache.evictAll()
            cacheDir.listFiles { f -> f.name.startsWith("thumb_") }?.forEach { it.delete() }
        }

        lifecycleScope.launch {
            try {
                allItems = withContext(Dispatchers.IO) { listImages(drive) }
                progress.visibility = View.GONE
                applyFilter()
            } catch (e: UserRecoverableAuthIOException) {
                // Falta conceder el permiso adicional: abrimos la pantalla de Google y reintentamos.
                consentLauncher.launch(e.intent)
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "Error al listar imágenes", e)
                tvEmpty.text = "No se pudo cargar la galería:\n${e.localizedMessage}"
                tvEmpty.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun applyFilter() {
        val pos = spinnerSede.selectedItemPosition
        val items = if (pos <= 0) allItems else {
            val sede = MainActivity.Sede.values()[pos - 1]
            allItems.filter { it.name.startsWith(sede.key + "_", ignoreCase = true) }
        }
        adapter.items = items
        adapter.notifyDataSetChanged()
        tvEmpty.text = if (allItems.isEmpty()) "Aún no hay escaneos subidos." else "No hay escaneos para esta sede."
        // Mientras se está cargando no mostramos el mensaje de "vacío"
        tvEmpty.visibility = if (items.isEmpty() && progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun listImages(drive: Drive): List<DriveImage> {
        val result = mutableListOf<DriveImage>()
        var pageToken: String? = null
        do {
            val response = drive.files().list()
                .setQ("'${MainActivity.FOLDER_ID}' in parents and trashed = false and mimeType = 'image/jpeg'")
                .setOrderBy("createdTime desc")
                .setFields("nextPageToken, files(id, name, createdTime, webViewLink)")
                .setPageSize(100)
                .setPageToken(pageToken)
                .execute()
            response.files?.forEach { f ->
                result.add(
                    DriveImage(
                        id = f.id,
                        name = f.name ?: "",
                        createdMillis = f.createdTime?.value ?: 0L,
                        webViewLink = f.webViewLink
                    )
                )
            }
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return result
    }

    /** Descarga (o lee de caché) la imagen y devuelve una miniatura. Llamar desde IO. */
    private fun loadThumbnail(drive: Drive, image: DriveImage): Bitmap? {
        memoryCache.get(image.id)?.let { return it }

        val cached = File(cacheDir, "thumb_${image.id}.jpg")
        if (!cached.exists()) {
            val tmp = File(cacheDir, "thumb_${image.id}.tmp")
            FileOutputStream(tmp).use { out ->
                drive.files().get(image.id).executeMediaAndDownloadTo(out)
            }
            tmp.renameTo(cached)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cached.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > THUMB_MAX_PX || bounds.outHeight / sample > THUMB_MAX_PX) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(cached.absolutePath, opts) ?: return null
        memoryCache.put(image.id, bitmap)
        return bitmap
    }

    // ---------- Adapter ----------

    private inner class GalleryAdapter : BaseAdapter() {
        var items: List<DriveImage> = emptyList()
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_gallery, parent, false)
            val image = items[position]
            val ivThumb = view.findViewById<ImageView>(R.id.ivThumb)
            val tvName = view.findViewById<TextView>(R.id.tvName)
            val tvDate = view.findViewById<TextView>(R.id.tvDate)

            tvName.text = image.name.removeSuffix(".jpg")
            tvDate.text = if (image.createdMillis > 0) dateFormat.format(Date(image.createdMillis)) else ""
            ivThumb.tag = image.id

            val cachedBitmap = memoryCache.get(image.id)
            if (cachedBitmap != null) {
                ivThumb.setImageBitmap(cachedBitmap)
            } else {
                ivThumb.setImageResource(android.R.drawable.ic_menu_gallery)
                val drive = driveService ?: return view
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) {
                        try { loadThumbnail(drive, image) } catch (e: Exception) {
                            Log.w(TAG, "Miniatura falló: ${image.name}", e); null
                        }
                    }
                    // Solo pintamos si la celda sigue mostrando la misma imagen (reciclaje de vistas)
                    if (bitmap != null && ivThumb.tag == image.id) ivThumb.setImageBitmap(bitmap)
                }
            }
            return view
        }
    }
}
