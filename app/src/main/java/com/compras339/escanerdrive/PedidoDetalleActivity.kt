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
        const val EXTRA_COL_RECIBIDO = "col_recibido"
        const val EXTRA_COL_FREC = "col_fecha_recibido"
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
            "nollego" to intent.getIntExtra(EXTRA_COL_NO_LLEGO, -1),
            "recibido" to intent.getIntExtra(EXTRA_COL_RECIBIDO, -1),
            "frec" to intent.getIntExtra(EXTRA_COL_FREC, -1)))
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
        findViewById<TextView>(R.id.tvHeadVenc).visibility = View.GONE   // Recibido y Vence van debajo de cada producto
        findViewById<TextView>(R.id.tvAyudaLineas).text =
            "Toca «Recibido» para anotar lo que llegó (con calculadora)" +
            (if (pideVenc) " y «Vence» para la fecha de vencimiento" else "") +
            ". Marca ✕ si el producto no llegó. Pulsa Guardar para registrarlo en la hoja de pedidos."
        pintarEstado()

        etObs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { tvObsCount.text = "${s?.length ?: 0} / 300" }
        })
        btnGuardar.setOnClickListener { guardar() }
        btnEscanear.setOnClickListener { escanear() }

        construirLineas()
        // Observaciones ocultas: ya no se leen al abrir (ahorra una consulta a la hoja)
        cargarFotos()
        if (intent.getBooleanExtra(EXTRA_ESCANEAR, false)) escanear()
    }

    // ---------- Ítems ----------

    private fun construirLineas() {
        layoutLineas.removeAllViews()
        fechaFields.clear()
        pedido.lineas.forEachIndexed { idx, l ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            }
            // Línea 1: casilla "no llegó" · producto · cantidad pedida
            val l1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val check = TextView(this).apply {
                gravity = Gravity.CENTER; textSize = 16f; setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { setMargins(0, 0, (10 * dp).toInt(), 0) }
                contentDescription = "No llegó"
            }
            l1.addView(check)
            val tvDesc = TextView(this).apply { text = l.descripcion; textSize = 13f; setTypeface(typeface, Typeface.BOLD) }
            val tvFalta = TextView(this).apply { text = "NO LLEGÓ"; setTextColor(ROJO); textSize = 10f; setTypeface(typeface, Typeface.BOLD) }
            l1.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(tvDesc)
                if (l.codigo.isNotBlank()) addView(TextView(context).apply { text = "Código: ${l.codigo}"; setTextColor(MUTED); textSize = 11f })
                addView(tvFalta)
            })
            l1.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(TextView(context).apply { text = l.cantidad; setTextColor(INK); textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END })
                addView(TextView(context).apply { text = l.unidad; setTextColor(MUTED); textSize = 10f; gravity = Gravity.END })
            })
            row.addView(l1)

            // Línea 2: Recibido (con calculadora) · Vence (solo proveedores de la lista)
            val l2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins((36 * dp).toInt(), (8 * dp).toInt(), 0, 0) }
            }
            val recibido = campo("RECIBIDO")
            fun pintarRecibido() {
                val pedida = PedidosRepo.numero(l.cantidad)
                val v = l.recibido
                val (titulo, valor) = recibido
                valor.text = if (v == null) "— ${l.unidad}" else "${PedidosRepo.formatoNum(v)} ${l.unidad}"
                val igual = v != null && pedida != null && Math.abs(v - pedida) < 0.0005
                titulo.text = if (v != null && pedida != null && !igual)
                    "RECIBIDO · dif. ${if (v - pedida > 0) "+" else ""}${PedidosRepo.formatoNum(v - pedida)}" else "RECIBIDO"
                (titulo.parent as View).setBackgroundResource(when {
                    v == null -> R.drawable.bg_field_warn
                    igual -> R.drawable.bg_field_light
                    else -> R.drawable.bg_field_warn
                })
                valor.setTextColor(when { v == null -> MUTED; igual -> Color.parseColor("#0B5D3B"); else -> Color.parseColor("#9A5B00") })
            }
            (recibido.first.parent as View).setOnClickListener {
                calculadora(l) { pintarRecibido() }
            }
            pintarRecibido()
            l2.addView(recibido.first.parent as View)
            if (pideVenc) {
                val vence = campo("VENCE")
                (vence.first.parent as View).layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { setMargins((8 * dp).toInt(), 0, 0, 0) }
                val fecha = vence.second
                (vence.first.parent as View).setOnClickListener { elegirFecha(l, fecha) }
                pintarFecha(fecha, l.vencimiento)
                fechaFields[l.fila] = fecha
                l2.addView(vence.first.parent as View)
            }
            row.addView(l2)

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

            layoutLineas.addView(row)
            if (idx < pedido.lineas.size - 1) layoutLineas.addView(View(this).apply {
                setBackgroundColor(Color.parseColor("#EEF1F5"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }

    /** Casilla con título pequeño y valor (Recibido / Vence). Devuelve (título, valor); el contenedor es su padre. */
    private fun campo(titulo: String): Pair<TextView, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), (5 * dp).toInt(), (8 * dp).toInt(), (5 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundResource(R.drawable.bg_field_warn)
            isClickable = true; isFocusable = true
            tag = "campo"
        }
        val t = TextView(this).apply { text = titulo; setTextColor(MUTED); textSize = 9f; setTypeface(typeface, Typeface.BOLD); maxLines = 1 }
        val v = TextView(this).apply { textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(INK); maxLines = 1 }
        box.addView(t); box.addView(v)
        return t to v
    }

    // ---------- Calculadora de "Recibido" ----------

    /** Calculadora para sumar cestas, sacos o cajas; "Usar total" deja el resultado en Recibido (se guarda con Guardar). */
    private fun calculadora(l: PedidosRepo.Linea, alTerminar: () -> Unit) {
        val toks = mutableListOf<String>()          // números y operadores: "12.5", "+", "13.2"
        var cur = l.recibido?.let { PedidosRepo.formatoNum(it).replace(',', '.') } ?: ""
        fun esOp(x: String) = x == "+" || x == "-" || x == "×"
        fun total(): Double {
            var t = 0.0; var op = "+"
            (toks + cur).filter { it.isNotEmpty() }.forEach { x ->
                if (esOp(x)) op = x else x.toDoubleOrNull()?.let { v -> t = when (op) { "-" -> t - v; "×" -> t * v; else -> t + v } }
            }
            return t
        }
        val tvExpr = TextView(this).apply { setTextColor(MUTED); textSize = 13f; gravity = Gravity.END }
        val tvTotal = TextView(this).apply { setTextColor(INK); textSize = 28f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.END }
        fun pintar() {
            tvExpr.text = (toks + cur).filter { it.isNotEmpty() }.joinToString(" ") { if (it == "-") "−" else it.replace('.', ',') }.ifEmpty { " " }
            tvTotal.text = "${PedidosRepo.formatoNum(total())} ${l.unidad}"
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (8 * dp).toInt(), (18 * dp).toInt(), 0)
        }
        raiz.addView(TextView(this).apply { text = "Pedido: ${l.cantidad} ${l.unidad} · suma cada cesta, saco o caja"; setTextColor(MUTED); textSize = 12f })
        raiz.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2F4F7"))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
            addView(tvExpr); addView(tvTotal)
        })
        val teclas = listOf(listOf("7", "8", "9", "←"), listOf("4", "5", "6", "×"), listOf("1", "2", "3", "−"), listOf("C", "0", ",", "+"))
        teclas.forEach { fila ->
            raiz.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                fila.forEach { k ->
                    addView(MaterialButton(context).apply {
                        text = k; textSize = 18f; isAllCaps = false; insetTop = 0; insetBottom = 0
                        cornerRadius = (10 * dp).toInt(); minWidth = 0; minimumWidth = 0
                        setTextColor(if (k == "C") ROJO else INK)
                        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(
                            if (k in listOf("+", "−", "×", "←")) "#DCE4EE" else "#EEF1F5"))
                        layoutParams = LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f).apply { setMargins((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt()) }
                        setOnClickListener {
                            when (k) {
                                "C" -> { toks.clear(); cur = "" }
                                "←" -> if (cur.isNotEmpty()) cur = cur.dropLast(1) else if (toks.isNotEmpty()) cur = toks.removeAt(toks.size - 1).let { if (esOp(it)) "" else it }
                                "," -> if (!cur.contains('.')) cur = (cur.ifEmpty { "0" }) + "."
                                "+", "−", "×" -> {
                                    val op = if (k == "−") "-" else k
                                    if (cur.isNotEmpty()) { toks += cur; cur = "" }
                                    if (toks.isNotEmpty()) { if (esOp(toks.last())) toks[toks.size - 1] = op else toks += op }
                                }
                                else -> if (cur.length < 9) cur += k
                            }
                            pintar()
                        }
                    })
                }
            })
        }
        pintar()
        AlertDialog.Builder(this)
            .setTitle("${l.descripcion} · Recibido")
            .setView(raiz)
            .setPositiveButton("Usar total") { _, _ ->
                val vacio = toks.isEmpty() && cur.isEmpty()
                l.recibido = if (vacio) null else total()
                alTerminar()
            }
            .setNeutralButton("Borrar") { _, _ -> l.recibido = null; alTerminar() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pintarFecha(tv: TextView, valor: String) {
        tv.text = valor.ifBlank { "dd/mm/aaaa" }
        // Si la fecha está dentro de una casilla (campo "VENCE"), se colorea la casilla completa
        val caja = (tv.parent as? View)?.takeIf { it.tag == "campo" } ?: tv
        caja.setBackgroundResource(if (valor.isBlank()) R.drawable.bg_field_warn else R.drawable.bg_field_light)
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
                    repo.guardarRecepcion(semana, pedido)
                    if (obs.isNotEmpty()) {
                        repo.guardarObservacion(semanaTab, pedido, obs, account.email ?: "")
                        // También queda en la columna "Observaciones" de la pestaña de la semana
                        repo.escribirObservacionesEnSemana(semana, pedido)
                    }
                }
                if (obs.isNotEmpty()) { etObs.setText(""); cargarAnteriores() }
                PedidosActivity.recargarAlVolver = true   // la lista no debe mostrar las marcas viejas
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
                    repo.marcarFechaRecibido(semana, pedido)   // fecha y hora del primer soporte
                }
                PedidosActivity.recargarAlVolver = true
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
