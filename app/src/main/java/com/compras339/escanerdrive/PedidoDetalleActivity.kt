package com.compras339.escanerdrive

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.button.MaterialButton
import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.model.File as DriveFile
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Detalle de un pedido: ítems con vencimiento editable, observaciones, soportes escaneados y escaneo por lote. */
class PedidoDetalleActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PedidoDetalle"
        const val EXTRA_ESCANEAR = "escanear"
        const val EXTRA_COL_VENC = "col_venc"
        const val EXTRA_COL_ESTADO = "col_estado"
        const val EXTRA_COL_OBS = "col_obs"
        const val EXTRA_COL_NO_LLEGO = "col_no_llego"
        /** false = este proveedor no está en la pestaña "Proveedores vencimiento": no se muestra la fecha. */
        const val EXTRA_PIDE_VENC = "pide_venc"
        private val ROJO = Color.parseColor("#C62828")
        private val ROJO_FONDO = Color.parseColor("#FDE4E4")
        private val BG = Color.parseColor("#16212E")
        private val INK = Color.parseColor("#16212E")
        private val MUTED = Color.parseColor("#5F6E7E")
    }

    private lateinit var repo: PedidosRepo
    private lateinit var pedido: PedidosRepo.Pedido
    private lateinit var semanaTab: String
    private lateinit var semana: PedidosRepo.Semana
    private var sede: Sede? = null
    private val dp get() = resources.displayMetrics.density

    private lateinit var layoutLineas: LinearLayout
    private lateinit var etObs: EditText
    private lateinit var tvObsCount: TextView
    private lateinit var cardAnteriores: LinearLayout
    private lateinit var layoutAnteriores: LinearLayout
    private lateinit var layoutFotos: LinearLayout
    private lateinit var tvFotosCount: TextView
    private lateinit var tvEstadoPedido: TextView
    private lateinit var btnGuardar: MaterialButton
    private lateinit var btnEscanear: MaterialButton
    private val fechaFields = mutableMapOf<Int, TextView>()
    private var pideVenc = true

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) { setBusy(false); return@registerForActivityResult }
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.mapNotNull { it.imageUri } ?: emptyList()
            if (uris.isEmpty()) { setBusy(false); toast("No se obtuvo ninguna imagen"); return@registerForActivityResult }
            subirFotos(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pedido_detalle)
        window.statusBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) { toast("Inicia sesión primero"); finish(); return }
        repo = PedidosRepo(this, account)
        pedido = PedidosRepo.Pedido.fromJson(intent.getStringExtra(PedidosActivity.EXTRA_PEDIDO_JSON) ?: "{}")
        semanaTab = intent.getStringExtra(PedidosActivity.EXTRA_SEMANA) ?: ""
        semana = PedidosRepo.Semana(semanaTab, emptyList(), mapOf(
            "venc" to intent.getIntExtra(EXTRA_COL_VENC, -1), "estado" to intent.getIntExtra(EXTRA_COL_ESTADO, -1),
            "obs" to intent.getIntExtra(EXTRA_COL_OBS, -1),
            "nollego" to intent.getIntExtra(EXTRA_COL_NO_LLEGO, -1)))
        pideVenc = intent.getBooleanExtra(EXTRA_PIDE_VENC, true)
        val sedesUsuario = (intent.getStringArrayListExtra(PedidosActivity.EXTRA_SEDE_KEYS) ?: arrayListOf()).mapNotNull { Brands.sedeByKey(it) }
        sede = PedidosActivity.SEDE_PEDIDOS   // esta vista es de Centro de Producción

        layoutLineas = findViewById(R.id.layoutLineas)
        etObs = findViewById(R.id.etObs)
        tvObsCount = findViewById(R.id.tvObsCount)
        cardAnteriores = findViewById(R.id.cardAnteriores)
        layoutAnteriores = findViewById(R.id.layoutAnteriores)
        layoutFotos = findViewById(R.id.layoutFotos)
        tvFotosCount = findViewById(R.id.tvFotosCount)
        tvEstadoPedido = findViewById(R.id.tvEstadoPedido)
        btnGuardar = findViewById(R.id.btnGuardar)
        btnEscanear = findViewById(R.id.btnEscanear)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvProveedor).text = pedido.proveedor
        findViewById<TextView>(R.id.tvControl).text = pedido.control
        findViewById<TextView>(R.id.tvFecha).text = pedido.fecha
        findViewById<TextView>(R.id.tvSede).text = "CENTRO DE PRODUCCIÓN"
        findViewById<TextView>(R.id.tvHeadItems).text = "ÍTEM · ${pedido.lineas.size}"
        findViewById<TextView>(R.id.tvHeadVenc).visibility = if (pideVenc) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvAyudaLineas).text =
            (if (pideVenc) "Toca la fecha de vencimiento de cada ítem para editarla. " else "") +
            "Marca la casilla ✕ si el producto no llegó. Pulsa Guardar para registrarlo en la hoja de pedidos."
        pintarEstado()

        etObs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { tvObsCount.text = "${s?.length ?: 0} / 300" }
        })
        btnGuardar.setOnClickListener { guardar() }
        btnEscanear.setOnClickListener { escanear() }

        construirLineas()
        cargarAnteriores()
        cargarFotos()
        if (intent.getBooleanExtra(EXTRA_ESCANEAR, false)) escanear()
    }

    // ---------- Ítems ----------

    private fun construirLineas() {
        layoutLineas.removeAllViews()
        fechaFields.clear()
        pedido.lineas.forEachIndexed { idx, l ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                if (idx < pedido.lineas.size - 1) background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE) }
            }
            // Casilla "no llegó": al marcarla muestra ✕ y resalta el ítem en rojo
            val check = TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { setMargins(0, 0, (10 * dp).toInt(), 0) }
                contentDescription = "No llegó"
            }
            row.addView(check)
            val tvDesc = TextView(this).apply { text = l.descripcion; textSize = 13f; setTypeface(typeface, Typeface.BOLD) }
            val tvFalta = TextView(this).apply { text = "NO LLEGÓ"; setTextColor(ROJO); textSize = 10f; setTypeface(typeface, Typeface.BOLD) }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(tvDesc)
                if (l.codigo.isNotBlank()) addView(TextView(context).apply { text = "Código: ${l.codigo}"; setTextColor(MUTED); textSize = 11f })
                addView(tvFalta)
            })
            fun pintarNoLlego() {
                check.text = if (l.noLlego) "✕" else ""
                check.setTextColor(Color.WHITE)
                check.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 6 * dp
                    if (l.noLlego) setColor(ROJO) else { setColor(Color.WHITE); setStroke((2 * dp).toInt(), Color.parseColor("#9AA7B5")) }
                }
                row.background = android.graphics.drawable.GradientDrawable().apply { setColor(if (l.noLlego) ROJO_FONDO else Color.WHITE) }
                tvDesc.setTextColor(if (l.noLlego) ROJO else INK)
                tvFalta.visibility = if (l.noLlego) View.VISIBLE else View.GONE
            }
            check.setOnClickListener { l.noLlego = !l.noLlego; pintarNoLlego() }
            pintarNoLlego()
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = l.cantidad; setTextColor(INK); textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END })
                addView(TextView(context).apply { text = l.unidad; setTextColor(MUTED); textSize = 10f; gravity = Gravity.END })
            })
            val fecha = TextView(this).apply {
                textSize = 12f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(INK)
                layoutParams = LinearLayout.LayoutParams((112 * dp).toInt(), (40 * dp).toInt()).apply { setMargins((8 * dp).toInt(), 0, 0, 0) }
                setOnClickListener { elegirFecha(l, this) }
            }
            pintarFecha(fecha, l.vencimiento)
            fechaFields[l.fila] = fecha
            if (pideVenc) row.addView(fecha)
            layoutLineas.addView(row)
            if (idx < pedido.lineas.size - 1) layoutLineas.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#EEF1F5"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }

    private fun pintarFecha(tv: TextView, valor: String) {
        tv.text = valor.ifBlank { "dd/mm/aaaa" }
        tv.setBackgroundResource(if (valor.isBlank()) R.drawable.bg_field_warn else R.drawable.bg_field_light)
        tv.setTextColor(if (valor.isBlank()) MUTED else INK)
    }

    private fun elegirFecha(l: PedidosRepo.Linea, tv: TextView) {
        val c = Calendar.getInstance()
        Regex("""(\d{4})-(\d{2})-(\d{2})""").find(l.vencimiento)?.let { m ->
            c.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
        }
        val dialog = DatePickerDialog(this, { _, y, mo, d ->
            l.vencimiento = String.format(Locale.ROOT, "%04d-%02d-%02d", y, mo + 1, d)
            pintarFecha(tv, l.vencimiento)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        // Botón "Limpiar": quita la fecha del ítem (al guardar, la celda queda vacía en la hoja)
        dialog.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Limpiar") { _, _ ->
            l.vencimiento = ""
            pintarFecha(tv, l.vencimiento)
        }
        dialog.show()
    }

    private fun pintarEstado() {
        val con = PedidosRepo.esListo(pedido.estado)
        tvEstadoPedido.text = if (con) PedidosRepo.ESTADO_LISTO else pedido.estado.uppercase(Locale.getDefault())
        tvEstadoPedido.setTextColor(if (con) Color.parseColor("#9EE2BF") else Color.parseColor("#B7C2CE"))
    }

    // ---------- Guardar (vencimientos + observación) ----------

    private fun guardar() {
        val obs = etObs.text.toString().trim()
        val account = GoogleSignIn.getLastSignedInAccount(this) ?: return
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (pideVenc) repo.guardarVencimientos(semana, pedido)
                    repo.guardarNoLlego(semana, pedido)
                    if (obs.isNotEmpty()) {
                        repo.guardarObservacion(semanaTab, pedido, obs, account.email ?: "")
                        // También queda en la columna "Observaciones" de la pestaña de la semana
                        repo.escribirObservacionesEnSemana(semana, pedido)
                    }
                }
                if (obs.isNotEmpty()) { etObs.setText(""); cargarAnteriores() }
                toast("Guardado en la hoja de pedidos")
            } catch (e: Exception) {
                Log.e(TAG, "Error al guardar", e)
                AlertDialog.Builder(this@PedidoDetalleActivity).setTitle("No se pudo guardar")
                    .setMessage(e.localizedMessage ?: "Error desconocido").setPositiveButton("Aceptar", null).show()
            } finally { setBusy(false) }
        }
    }

    private fun cargarAnteriores() {
        lifecycleScope.launch {
            val lista = withContext(Dispatchers.IO) { repo.observaciones(semanaTab, pedido.clave) }
            layoutAnteriores.removeAllViews()
            cardAnteriores.visibility = if (lista.isEmpty()) View.GONE else View.VISIBLE
            lista.reversed().forEach { o ->
                layoutAnteriores.addView(LinearLayout(this@PedidoDetalleActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                    addView(TextView(context).apply { text = o.fechaHora.takeLast(11); setTextColor(MUTED); textSize = 11f
                        layoutParams = LinearLayout.LayoutParams((84 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT) })
                    addView(TextView(context).apply { text = o.texto + (if (o.usuario.isNotBlank()) "\n— ${o.usuario.substringBefore('@')}" else ""); setTextColor(INK); textSize = 12f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
                })
            }
        }
    }

    // ---------- Fotos del pedido ----------

    private fun prefijoFotos(): String = "PEDIDO_${Brands.slug(semanaTab).take(20)}_${pedido.clave}_"

    private fun cargarFotos() {
        lifecycleScope.launch {
            val archivos = try {
                withContext(Dispatchers.IO) {
                    repo.drive.files().list()
                        .setQ("mimeType = 'image/jpeg' and trashed = false and name contains '${prefijoFotos()}'")
                        .setOrderBy("createdTime").setFields("files(id, name, createdTime, webViewLink)").setPageSize(50).execute().files ?: emptyList()
                }
            } catch (e: Exception) { Log.w(TAG, "No se pudieron listar fotos", e); emptyList() }
            val lista = archivos.filter { (it.name ?: "").startsWith(prefijoFotos(), ignoreCase = true) }
            tvFotosCount.text = if (lista.isEmpty()) "Sin fotos" else "${lista.size} foto(s)"
            layoutFotos.removeAllViews()
            lista.forEachIndexed { i, f ->
                val cell = LinearLayout(this@PedidoDetalleActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_field_light)
                    layoutParams = LinearLayout.LayoutParams((86 * dp).toInt(), (110 * dp).toInt()).apply { setMargins(0, 0, (8 * dp).toInt(), 0) }
                    setOnClickListener { f.webViewLink?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                }
                val iv = ImageView(this@PedidoDetalleActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (76 * dp).toInt())
                    setImageResource(android.R.drawable.ic_menu_gallery)
                }
                cell.addView(iv)
                cell.addView(TextView(this@PedidoDetalleActivity).apply { text = "Factura ${i + 1}"; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(INK) })
                cell.addView(TextView(this@PedidoDetalleActivity).apply {
                    text = f.createdTime?.value?.let { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(it)) } ?: ""
                    textSize = 9f; setTextColor(MUTED)
                })
                layoutFotos.addView(cell)
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { miniatura(f.id) }
                    if (bmp != null) iv.setImageBitmap(bmp)
                }
            }
            // Casilla "Otra factura"
            layoutFotos.addView(LinearLayout(this@PedidoDetalleActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_field_warn)
                layoutParams = LinearLayout.LayoutParams((86 * dp).toInt(), (110 * dp).toInt())
                addView(TextView(context).apply { text = "+"; textSize = 22f; setTextColor(INK); setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(context).apply { text = "Otra factura"; textSize = 10f; setTextColor(INK); setTypeface(typeface, Typeface.BOLD) })
                setOnClickListener { escanear() }
            })
        }
    }

    private fun miniatura(id: String): Bitmap? = try {
        val cached = File(cacheDir, "thumb_$id.jpg")
        if (!cached.exists()) FileOutputStream(cached).use { repo.drive.files().get(id).executeMediaAndDownloadTo(it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cached.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 480) sample *= 2
        BitmapFactory.decodeFile(cached.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }

    // ---------- Escanear soportes del pedido ----------

    private fun escanear() {
        if (sede == null) { toast("No se pudo determinar la sede del pedido"); return }
        setBusy(true)
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setGalleryImportAllowed(false).build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { e -> setBusy(false); toast("No se pudo abrir el escáner: ${e.localizedMessage}") }
    }

    /** FOLDER_ID / SEDE / DD-MM-AAAA / PEDIDO_{control}_{semana}_{SedeKey}_{usuario}_{fecha}_{hora}.jpg */
    private fun subirFotos(uris: List<Uri>) {
        val s = sede ?: return
        val account = GoogleSignIn.getLastSignedInAccount(this) ?: return
        val userPrefix = (account.email ?: "usuario").substringBefore("@").replace(Regex("[^A-Za-z0-9._-]"), "_")
        setBusy(true)
        lifecycleScope.launch {
            val subidos = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    val parentId = DriveHelper.ensureFolderPath(repo.drive, MainActivity.FOLDER_ID, listOf(s.label, DriveHelper.todayFolderName()))
                    uris.forEach { uri ->
                        val ts = SimpleDateFormat("yyyyMMdd_HH-mm-ss", Locale.getDefault()).format(Date())
                        var name = "${prefijoFotos()}${s.key}_${userPrefix}_$ts.jpg"; var n = 2
                        while (name in subidos) { name = "${prefijoFotos()}${s.key}_${userPrefix}_${ts}_$n.jpg"; n++ }
                        val meta = DriveFile().apply { this.name = name; mimeType = "image/jpeg"; parents = listOf(parentId) }
                        val stream = contentResolver.openInputStream(uri) ?: throw IOException("No se pudo leer la imagen")
                        stream.use { repo.drive.files().create(meta, InputStreamContent("image/jpeg", it)).setFields("id").execute() }
                        subidos += name
                    }
                    repo.marcarEstado(semana, pedido, PedidosRepo.ESTADO_LISTO)
                }
                pintarEstado()
                cargarFotos()
                toast("${subidos.size} foto(s) guardadas para ${pedido.proveedor}")
            } catch (e: Exception) {
                Log.e(TAG, "Error al subir fotos del pedido", e)
                val detalle = if (subidos.isEmpty()) "" else "\n\nYa se guardaron ${subidos.size} de ${uris.size}."
                AlertDialog.Builder(this@PedidoDetalleActivity).setTitle("No se pudo subir")
                    .setMessage((e.localizedMessage ?: "Error desconocido") + detalle)
                    .setPositiveButton("Reintentar") { _, _ -> subirFotos(uris.drop(subidos.size)) }
                    .setNegativeButton("Cancelar", null).show()
            } finally { setBusy(false) }
        }
    }

    private fun setBusy(on: Boolean) {
        btnGuardar.isEnabled = !on; btnEscanear.isEnabled = !on
        btnEscanear.text = if (on) "PROCESANDO…" else "ESCANEAR SOPORTE"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
