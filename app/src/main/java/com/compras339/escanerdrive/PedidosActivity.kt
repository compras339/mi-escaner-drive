package com.compras339.escanerdrive

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.button.MaterialButton
import com.google.api.client.http.InputStreamContent
import com.google.api.services.drive.model.File as DriveFile
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lista de pedidos de la semana vigente (última pestaña de la hoja), con búsqueda por proveedor,
 * filtros por estado, "Tomar foto" por pedido y "Escanear recepción sin pedido".
 */
class PedidosActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PedidosActivity"
        const val EXTRA_SEDE_KEYS = "sede_keys"
        const val EXTRA_PEDIDO_JSON = "pedido_json"
        const val EXTRA_SEMANA = "semana"
        /** true = Pedidos es la pantalla principal (usuarios "solo pedidos"): sin flecha de volver y atrás sale de la app. */
        const val EXTRA_PRINCIPAL = "principal"
        private val BG = Color.parseColor("#16212E")
        private val INK = Color.parseColor("#16212E")
        private val MUTED = Color.parseColor("#5F6E7E")
        private val ACCENT = Color.parseColor("#F5A524")

        /** Esta vista es exclusiva de Centro de Producción: todo lo que se escanea aquí es CP. */
        val SEDE_PEDIDOS: Sede get() = Brands.sedeByKey("CP")!!

        fun sedeDelPedido(texto: String, sedesUsuario: List<Sede>): Sede = SEDE_PEDIDOS
    }

    private lateinit var repo: PedidosRepo
    private lateinit var layoutPedidos: LinearLayout
    private lateinit var layoutFiltros: LinearLayout
    private lateinit var tvSemana: TextView
    private lateinit var tvEstado: TextView
    private lateinit var progress: ProgressBar
    private lateinit var etBuscar: EditText

    private var semana: PedidosRepo.Semana? = null
    private var filtro = "PENDIENTES"   // PENDIENTES | LISTOS | TODOS | SIN PEDIDO
    private var sedesUsuario: List<Sede> = emptyList()
    private var sedeSinPedido: Sede? = null
    private val dp get() = resources.displayMetrics.density

    // Escáner de "recepción sin pedido" (mismo comportamiento que la pantalla principal)
    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) { setLoading(false); return@registerForActivityResult }
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.mapNotNull { it.imageUri } ?: emptyList()
            val sede = sedeSinPedido
            if (uris.isEmpty() || sede == null) { setLoading(false); return@registerForActivityResult }
            subirSinPedido(sede, uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pedidos)
        window.statusBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) { toast("Inicia sesión primero"); finish(); return }
        repo = PedidosRepo(this, account)

        val keys = intent.getStringArrayListExtra(EXTRA_SEDE_KEYS) ?: arrayListOf()
        sedesUsuario = keys.mapNotNull { Brands.sedeByKey(it) }.filter { Brands.brandOf(it) != Brands.PROYECTOS }

        layoutPedidos = findViewById(R.id.layoutPedidos)
        layoutFiltros = findViewById(R.id.layoutFiltros)
        tvSemana = findViewById(R.id.tvSemana)
        tvEstado = findViewById(R.id.tvEstado)
        progress = findViewById(R.id.progress)
        etBuscar = findViewById(R.id.etBuscar)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnRefresh = findViewById<ImageButton>(R.id.btnRefresh)
        btnRefresh.setOnClickListener { if (filtro == "SIN PEDIDO") cargarSinPedido() else cargar(forzarPestanas = true) }
        if (intent.getBooleanExtra(EXTRA_PRINCIPAL, false)) {
            // Pantalla principal: no hay a dónde volver
            btnBack.visibility = View.GONE
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { finishAffinity() }   // cierra la app sin mostrar la pantalla de inicio
            })
            // Botón visible "Cerrar sesión" abajo: vuelve a la pantalla principal de la app (inicio de sesión)
            findViewById<MaterialButton>(R.id.btnCerrarSesion).apply {
                text = "Cerrar sesión (${account.email ?: "cuenta"})"
                visibility = View.VISIBLE
                setOnClickListener { opcionesCuenta(account.email ?: "cuenta") }
            }
            buscarActualizacion()
        } else {
            btnBack.setOnClickListener { finish() }
        }
        findViewById<MaterialButton>(R.id.btnSinPedido).setOnClickListener { escanearSinPedido() }
        etBuscar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { pintar() }
        })
        construirFiltros()
        cargar()
    }

    override fun onResume() {
        super.onResume()
        // Al volver (del detalle, la cámara, desbloquear el teléfono…) refresca, pero no más de una vez cada 20 s
        if (semana != null && System.currentTimeMillis() - ultimaCarga > 20_000L) cargar()
    }

    /** Aviso de versión nueva (en "solo pedidos" esta es la pantalla principal, así que el aviso va aquí). */
    private fun buscarActualizacion() {
        lifecycleScope.launch {
            val latest = UpdateChecker.fetchLatest() ?: return@launch
            val current = UpdateChecker.currentBuild(this@PedidosActivity)
            if (latest.build <= current || isFinishing) return@launch
            AlertDialog.Builder(this@PedidosActivity)
                .setTitle("Nueva versión disponible")
                .setMessage("Hay una versión nueva de la app (${latest.version}, build ${latest.build}).\n" +
                    "Tienes instalada la build $current.\n\nLa actualización se instala encima; no pierdes la sesión.")
                .setPositiveButton("Actualizar") { _, _ ->
                    toast("Descargando actualización…")
                    lifecycleScope.launch {
                        try {
                            UpdateChecker.downloadAndInstall(this@PedidosActivity, latest)
                            toast("Descarga lista. Pulsa «Instalar» en la ventana de Android.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error al actualizar", e)
                            toast("No se pudo actualizar: ${e.localizedMessage}")
                        }
                    }
                }
                .setNegativeButton("Más tarde", null)
                .show()
        }
    }

    private fun opcionesCuenta(email: String) {
        AlertDialog.Builder(this)
            .setTitle("¿Cerrar sesión?")
            .setMessage("Se cerrará la sesión de $email y volverás a la pantalla principal de la app.")
            .setPositiveButton("Cerrar sesión") { _, _ ->
                GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut().addOnCompleteListener {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Carga ----------

    private var cargando = false
    private var ultimaCarga = 0L
    private var reintento: kotlinx.coroutines.Job? = null

    private fun cargar(forzarPestanas: Boolean = false) {
        if (cargando) return   // evita lecturas duplicadas en paralelo
        cargando = true
        reintento?.cancel()
        setLoading(true)
        lifecycleScope.launch {
            try {
                val s = withContext(Dispatchers.IO) { repo.cargarSemana(forzarPestanas) }
                semana = s
                tvEstado.visibility = View.GONE
                pintar()
            } catch (e: Exception) {
                Log.e(TAG, "Error al cargar pedidos", e)
                val limite = (e as? com.google.api.client.googleapis.json.GoogleJsonResponseException)?.statusCode == 429
                if (limite) {
                    // Cuota de Google (60 lecturas/min por cuenta): esperar y reintentar solo
                    tvEstado.text = "Google limitó las consultas por un momento.\nReintentando en 1 minuto…"
                    reintento = lifecycleScope.launch { kotlinx.coroutines.delay(65_000L); cargar() }
                } else {
                    tvEstado.text = "No se pudo leer la hoja de pedidos.\n${(e as? com.google.api.client.googleapis.json.GoogleJsonResponseException)?.details?.message ?: e.localizedMessage}\n\nToca ↻ para reintentar."
                }
                tvEstado.visibility = View.VISIBLE
            } finally {
                ultimaCarga = System.currentTimeMillis()
                cargando = false
                setLoading(false)
            }
        }
    }

    /** Pedidos visibles: todos los de la pestaña (la hoja es de Centro de Producción). */
    private fun pedidosVisibles(): List<PedidosRepo.Pedido> = semana?.pedidos ?: emptyList()

    private fun pintar() {
        if (filtro == "SIN PEDIDO") { pintarSinPedido(); return }
        val s = semana ?: return
        val q = PedidosRepo.norm(etBuscar.text.toString())
        val lista = pedidosVisibles().filter { p ->
            (q.isEmpty() || PedidosRepo.norm(p.proveedor).contains(q) || PedidosRepo.norm(p.control).contains(q)) &&
            when (filtro) {
                "PENDIENTES" -> !PedidosRepo.esListo(p.estado)
                "LISTOS" -> PedidosRepo.esListo(p.estado)
                else -> true
            }
        }
        tvSemana.text = "${s.tab} · última pestaña del archivo · ${pedidosVisibles().size} pedidos"
        layoutPedidos.removeAllViews()
        if (lista.isEmpty()) {
            layoutPedidos.addView(TextView(this).apply {
                text = if (s.pedidos.isEmpty()) "La pestaña «${s.tab}» no tiene pedidos." else "No hay pedidos con ese filtro."
                setTextColor(MUTED); textSize = 13f; gravity = Gravity.CENTER
                setPadding(0, (24 * dp).toInt(), 0, 0)
            })
            return
        }
        lista.forEach { layoutPedidos.addView(tarjeta(it)) }
    }

    private fun tarjeta(p: PedidosRepo.Pedido): View {
        val conSoporte = PedidosRepo.esListo(p.estado)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_light)
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, (10 * dp).toInt()) }
            alpha = if (conSoporte) 0.8f else 1f
            isClickable = true; isFocusable = true
            setOnClickListener { abrirDetalle(p) }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = "Pedido ${p.control}"; setTextColor(MUTED); textSize = 12f; setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(context).apply { text = "  ${p.fecha}"; setTextColor(MUTED); textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
            addView(TextView(context).apply {
                text = if (conSoporte) PedidosRepo.ESTADO_LISTO else p.estado.uppercase(Locale.getDefault()); textSize = 11f; setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (conSoporte) Color.parseColor("#0B5D3B") else INK)
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * dp; setColor(if (conSoporte) Color.parseColor("#D9F2E6") else Color.parseColor("#FFF1D6")) }
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            })
        })
        card.addView(TextView(this).apply { text = p.proveedor; setTextColor(INK); textSize = 16f; setTypeface(typeface, Typeface.BOLD); setPadding(0, (6 * dp).toInt(), 0, 0) })
        card.addView(TextView(this).apply {
            val resumen = p.lineas.take(3).joinToString(", ") { it.descripcion.lowercase(Locale.getDefault()).replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
            text = "${p.lineas.size} ítems · $resumen" + (if (p.lineas.size > 3) "…" else "")
            setTextColor(Color.parseColor("#3A4756")); textSize = 12f; setPadding(0, (4 * dp).toInt(), 0, 0)
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, (8 * dp).toInt(), 0, 0)
            addView(MaterialButton(context).apply {
                text = if (conSoporte) "Ver / tomar otra foto" else "Tomar foto"
                isAllCaps = false; textSize = 12f
                setTextColor(INK); backgroundTintList = android.content.res.ColorStateList.valueOf(if (conSoporte) Color.parseColor("#DCE4EE") else ACCENT)
                cornerRadius = (20 * dp).toInt(); insetTop = 0; insetBottom = 0
                setIconResource(android.R.drawable.ic_menu_camera); iconTint = android.content.res.ColorStateList.valueOf(INK); iconSize = (16 * dp).toInt()
                setOnClickListener { abrirDetalle(p, escanear = true) }
            })
        })
        return card
    }

    private fun abrirDetalle(p: PedidosRepo.Pedido, escanear: Boolean = false) {
        val s = semana ?: return
        startActivity(Intent(this, PedidoDetalleActivity::class.java).apply {
            putExtra(EXTRA_PEDIDO_JSON, p.toJson())
            putExtra(EXTRA_SEMANA, s.tab)
            putStringArrayListExtra(EXTRA_SEDE_KEYS, ArrayList(sedesUsuario.map { it.key }))
            putExtra(PedidoDetalleActivity.EXTRA_ESCANEAR, escanear)
            putExtra(PedidoDetalleActivity.EXTRA_COL_VENC, s.columnas["venc"] ?: -1)
            putExtra(PedidoDetalleActivity.EXTRA_COL_ESTADO, s.columnas["estado"] ?: -1)
            putExtra(PedidoDetalleActivity.EXTRA_COL_OBS, s.columnas["obs"] ?: -1)
        })
    }

    // ---------- Filtros ----------

    private fun construirFiltros() {
        layoutFiltros.removeAllViews()
        val opciones = listOf("PENDIENTES" to "Pendientes", "LISTOS" to "Listos", "SIN PEDIDO" to "Sin pedido", "TODOS" to "Todos")
        opciones.forEachIndexed { i, (key, label) ->
            layoutFiltros.addView(MaterialButton(this).apply {
                text = label; isAllCaps = false; textSize = 12f; maxLines = 1
                insetTop = 0; insetBottom = 0; cornerRadius = (18 * dp).toInt()
                minWidth = 0; minimumWidth = 0
                setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
                // Los 4 filtros se reparten el ancho por igual
                layoutParams = LinearLayout.LayoutParams(0, (36 * dp).toInt(), 1f)
                    .apply { if (i < opciones.size - 1) setMargins(0, 0, (6 * dp).toInt(), 0) }
                setOnClickListener {
                    filtro = key; construirFiltros()
                    if (key == "SIN PEDIDO") cargarSinPedido() else pintar()
                }
                val on = filtro == key
                backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) ACCENT else Color.WHITE)
                setTextColor(if (on) INK else Color.parseColor("#3A4756"))
                strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#CBD5E1")); strokeWidth = if (on) 0 else (1.5f * dp).toInt()
            })
        }
    }

    // ---------- Recepción sin pedido ----------

    private fun escanearSinPedido() = lanzarEscaner(SEDE_PEDIDOS)

    private fun lanzarEscaner(sede: Sede) {
        sedeSinPedido = sede
        setLoading(true)
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setGalleryImportAllowed(false).build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { e -> setLoading(false); toast("No se pudo abrir el escáner: ${e.localizedMessage}") }
    }

    /** Sube como factura normal: FOLDER_ID / SEDE / DD-MM-AAAA / Sede_usuario_fecha_hora.jpg */
    private fun subirSinPedido(sede: Sede, uris: List<Uri>) {
        val account = GoogleSignIn.getLastSignedInAccount(this) ?: return
        val userPrefix = (account.email ?: "usuario").substringBefore("@").replace(Regex("[^A-Za-z0-9._-]"), "_")
        setLoading(true)
        lifecycleScope.launch {
            val subidos = mutableListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    val parentId = DriveHelper.ensureFolderPath(repo.drive, MainActivity.FOLDER_ID, listOf(sede.label, DriveHelper.todayFolderName()))
                    uris.forEach { uri ->
                        val ts = SimpleDateFormat("yyyyMMdd_HH-mm-ss", Locale.getDefault()).format(Date())
                        var name = "${sede.key}_${userPrefix}_$ts.jpg"; var n = 2
                        while (name in subidos) { name = "${sede.key}_${userPrefix}_${ts}_$n.jpg"; n++ }
                        val meta = DriveFile().apply {
                            this.name = name; mimeType = "image/jpeg"; parents = listOf(parentId)
                            // Marca para listarla en la pestaña "Sin pedido" (el nombre del archivo no cambia)
                            appProperties = mapOf(PedidosRepo.PROP_TIPO to PedidosRepo.TIPO_SIN_PEDIDO)
                        }
                        val stream = contentResolver.openInputStream(uri) ?: throw IOException("No se pudo leer la imagen")
                        stream.use { repo.drive.files().create(meta, InputStreamContent("image/jpeg", it)).setFields("id").execute() }
                        subidos += name
                    }
                }
                setLoading(false)
                if (filtro == "SIN PEDIDO") cargarSinPedido()
                AlertDialog.Builder(this@PedidosActivity).setTitle("¡Guardado!")
                    .setMessage("${subidos.size} foto(s) de recepción sin pedido en ${sede.label} › ${DriveHelper.todayFolderName()}:\n" + subidos.joinToString("\n"))
                    .setPositiveButton("Aceptar", null).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error al subir recepción sin pedido", e)
                setLoading(false)
                toast("No se pudo subir: ${e.localizedMessage}")
            }
        }
    }

    // ---------- Pestaña "Sin pedido" ----------

    private var fotosSinPedido: List<DriveFile>? = null

    /** Fotos de "recepción sin pedido" de los últimos 7 días (marcadas con appProperties al subirlas). */
    private fun cargarSinPedido() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val desde = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000))
                fotosSinPedido = withContext(Dispatchers.IO) {
                    repo.drive.files().list()
                        .setQ("appProperties has { key='${PedidosRepo.PROP_TIPO}' and value='${PedidosRepo.TIPO_SIN_PEDIDO}' } " +
                            "and trashed = false and createdTime > '$desde'")
                        .setOrderBy("createdTime desc").setPageSize(100)
                        .setFields("files(id, name, createdTime, webViewLink)").execute().files ?: emptyList()
                }
                tvEstado.visibility = View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "Error al listar fotos sin pedido", e)
                fotosSinPedido = null
                tvEstado.text = "No se pudieron leer las fotos sin pedido.\nToca ↻ para reintentar."
                tvEstado.visibility = View.VISIBLE
            } finally {
                setLoading(false)
                if (filtro == "SIN PEDIDO") pintarSinPedido()
            }
        }
    }

    private fun pintarSinPedido() {
        layoutPedidos.removeAllViews()
        val lista = fotosSinPedido ?: return
        val fmt = SimpleDateFormat("dd/MM/yyyy · hh:mm a", Locale.getDefault())
        layoutPedidos.addView(TextView(this).apply {
            text = if (lista.isEmpty()) "No hay fotos sin pedido en los últimos 7 días."
                else "${lista.size} foto(s) sin pedido · últimos 7 días. Toca una para verla."
            setTextColor(MUTED); textSize = 12f; setPadding(0, 0, 0, (10 * dp).toInt())
        })
        lista.forEach { f ->
            val usuario = (f.name ?: "").split("_").getOrNull(1) ?: ""
            layoutPedidos.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card_light)
                setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, (8 * dp).toInt()) }
                isClickable = true; isFocusable = true
                setOnClickListener { f.webViewLink?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                addView(android.widget.ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply { setMargins(0, 0, (12 * dp).toInt(), 0) }
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(context).apply {
                        text = f.createdTime?.value?.let { fmt.format(Date(it)) } ?: (f.name ?: "")
                        setTextColor(INK); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
                    })
                    addView(TextView(context).apply {
                        text = "Recepción sin pedido" + (if (usuario.isNotBlank()) " · $usuario" else "")
                        setTextColor(MUTED); textSize = 12f
                    })
                })
                addView(TextView(context).apply {
                    text = "SIN PEDIDO"; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(INK)
                    background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.parseColor("#E6E9EF")) }
                    setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                })
            })
        }
    }

    // ---------- Utilidades ----------

    private fun setLoading(on: Boolean) { progress.visibility = if (on) View.VISIBLE else View.GONE }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
