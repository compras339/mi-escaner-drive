package com.compras339.escanerdrive

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
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
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pantalla GASTOS: clasifica un soporte (sede/mixto, condición, moneda) antes de escanearlo
 * y lo guarda en Drive con nombre y carpeta descriptivos:
 *   GASTO_{SEDE}_{CONDICION}_{MONEDA}_{AAAA-MM-DD}.jpg
 *   Gastos / {Año} / {MM Mes} / {Marca} / {Sede} / {Contado|Crédito}
 */
class GastosActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GastosActivity"
        const val EXTRA_SEDE_KEYS = "sede_keys"
        private const val MIME_JPEG = "image/jpeg"
        private const val MIME_FOLDER = "application/vnd.google-apps.folder"
        private val MESES = listOf(
            "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
            "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
        )
        private val ACCENT = Color.parseColor("#F5A524")
        private val INK = Color.parseColor("#F4F1EA")
        private val MUTED = Color.parseColor("#9FB0C2")
        private val SEG_OFF = Color.parseColor("#B7C2CE")
        private val BG = Color.parseColor("#16212E")
    }

    // ---------- Estado del formulario ----------
    private var mixto = false
    private val selected = linkedSetOf<Sede>()
    private val amounts = mutableMapOf<Sede, String>()
    private var cond: String? = null       // "contado" | "credito"
    private var currency: String? = null   // "bs" | "usd"
    private var availableSedes: List<Sede> = emptyList()
    private val chips = mutableMapOf<Sede, MaterialButton>()
    private val amountFields = mutableMapOf<Sede, EditText>()
    private var pendingUri: Uri? = null

    // ---------- Vistas ----------
    private lateinit var segUna: TextView
    private lateinit var segMixto: TextView
    private lateinit var layoutGroups: LinearLayout
    private lateinit var tvMixNote: TextView
    private lateinit var cardAmounts: LinearLayout
    private lateinit var layoutAmounts: LinearLayout
    private lateinit var tvTotal: TextView
    private lateinit var chipContado: MaterialButton
    private lateinit var chipCredito: MaterialButton
    private lateinit var cardDays: LinearLayout
    private lateinit var etDays: EditText
    private lateinit var layoutDayShortcuts: LinearLayout
    private lateinit var chipBs: MaterialButton
    private lateinit var chipUsd: MaterialButton
    private lateinit var btnScan: MaterialButton
    private lateinit var btnProcessing: GlowBorderLayout
    private lateinit var tvMissing: TextView

    private val dp get() = resources.displayMetrics.density

    // ---------- Escáner ----------
    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) { setProcessing(false); return@registerForActivityResult }
            val uri = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.firstOrNull()?.imageUri
            if (uri == null) { setProcessing(false); toast("No se obtuvo ninguna imagen"); return@registerForActivityResult }
            pendingUri = uri
            saveToDrive(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gastos)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvDate).text = run {
            val c = Calendar.getInstance()
            String.format(Locale.getDefault(), "%02d %s %d", c.get(Calendar.DAY_OF_MONTH),
                MESES[c.get(Calendar.MONTH)].take(3), c.get(Calendar.YEAR))
        }

        segUna = findViewById(R.id.segUna)
        segMixto = findViewById(R.id.segMixto)
        layoutGroups = findViewById(R.id.layoutGroups)
        tvMixNote = findViewById(R.id.tvMixNote)
        cardAmounts = findViewById(R.id.cardAmounts)
        layoutAmounts = findViewById(R.id.layoutAmounts)
        tvTotal = findViewById(R.id.tvTotal)
        chipContado = findViewById(R.id.chipContado)
        chipCredito = findViewById(R.id.chipCredito)
        cardDays = findViewById(R.id.cardDays)
        etDays = findViewById(R.id.etDays)
        layoutDayShortcuts = findViewById(R.id.layoutDayShortcuts)
        chipBs = findViewById(R.id.chipBs)
        chipUsd = findViewById(R.id.chipUsd)
        btnScan = findViewById(R.id.btnScan)
        btnProcessing = findViewById(R.id.btnProcessing)
        tvMissing = findViewById(R.id.tvMissing)

        // Sedes disponibles para este usuario (vienen de MainActivity)
        val keys = intent.getStringArrayListExtra(EXTRA_SEDE_KEYS) ?: arrayListOf()
        availableSedes = keys.mapNotNull { Brands.sedeByKey(it) }.ifEmpty { Brands.allSedes() }

        segUna.setOnClickListener { setMixto(false) }
        segMixto.setOnClickListener { setMixto(true) }
        chipContado.setOnClickListener { setCond("contado") }
        chipCredito.setOnClickListener { setCond("credito") }
        chipBs.setOnClickListener { setCurrency("bs") }
        chipUsd.setOnClickListener { setCurrency("usd") }
        etDays.addTextChangedListener(simpleWatcher { refresh() })
        listOf(7, 15, 30, 45).forEach { d ->
            layoutDayShortcuts.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "$d días"
                isAllCaps = false
                textSize = 13f
                insetTop = 0; insetBottom = 0
                cornerRadius = (12 * dp).toInt()
                strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#2E4057"))
                setTextColor(INK)
                backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1E2C3C"))
                layoutParams = LinearLayout.LayoutParams(0, (38 * dp).toInt(), 1f).apply { setMargins((3 * dp).toInt(), 0, (3 * dp).toInt(), 0) }
                setOnClickListener { etDays.setText(d.toString()); etDays.setSelection(etDays.text.length) }
            })
        }
        btnScan.setOnClickListener { startScanner() }

        buildGroups()
        setMixto(false)
    }

    // ---------- Construcción de grupos de sedes ----------

    private fun buildGroups() {
        layoutGroups.removeAllViews()
        chips.clear()
        val groups = availableSedes.groupBy { Brands.brandOf(it) }
        // Grupos pequeños (1 sede) van de dos en dos en una fila; el resto, uno por fila
        val small = groups.filter { it.value.size == 1 }
        val large = groups.filter { it.value.size > 1 }

        small.entries.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, (8 * dp).toInt()) }
            }
            pair.forEach { (brand, sedes) ->
                row.addView(groupCard(brand, sedes, columns = 1).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        .apply { setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) }
                })
            }
            if (pair.size == 1) row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            })
            layoutGroups.addView(row)
        }
        large.forEach { (brand, sedes) ->
            layoutGroups.addView(groupCard(brand, sedes, columns = if (sedes.size == 2) 2 else 3).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(0, 0, 0, (8 * dp).toInt()) }
            })
        }
    }

    private fun groupCard(brand: BrandTheme, sedes: List<Sede>, columns: Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * dp; setColor(Color.parseColor("#1B2939")) }
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
        }
        card.addView(TextView(this).apply {
            text = brand.name.uppercase(Locale.getDefault())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ACCENT)
            maxLines = 1
            setPadding((4 * dp).toInt(), 0, 0, (6 * dp).toInt())
        })
        sedes.chunked(columns).forEach { rowSedes ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowSedes.forEach { sede ->
                val chip = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = sede.short
                    isAllCaps = false
                    isCheckable = true
                    textSize = 13f
                    maxLines = 1
                    insetTop = 0; insetBottom = 0
                    cornerRadius = (14 * dp).toInt()
                    strokeWidth = (1.5f * dp).toInt()
                    strokeColor = getColorStateList(R.color.gastos_chip_stroke)
                    backgroundTintList = getColorStateList(R.color.gastos_chip_bg)
                    setTextColor(getColorStateList(R.color.gastos_chip_text))
                    setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(0, (42 * dp).toInt(), 1f).apply { setMargins((3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt(), (3 * dp).toInt()) }
                    setOnClickListener { toggleSede(sede) }
                }
                chips[sede] = chip
                row.addView(chip)
            }
            repeat(columns - rowSedes.size) { row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }) }
            card.addView(row)
        }
        return card
    }

    // ---------- Interacción ----------

    private fun setMixto(value: Boolean) {
        mixto = value
        selected.clear(); amounts.clear()
        chips.values.forEach { it.isChecked = false }
        segUna.background = if (!mixto) getDrawable(R.drawable.bg_seg_on) else null
        segMixto.background = if (mixto) getDrawable(R.drawable.bg_seg_on) else null
        segUna.setTextColor(if (!mixto) BG else SEG_OFF)
        segMixto.setTextColor(if (mixto) BG else SEG_OFF)
        tvMixNote.visibility = if (mixto) View.VISIBLE else View.GONE
        rebuildAmounts()
        refresh()
    }

    private fun toggleSede(sede: Sede) {
        if (mixto) {
            if (sede in selected) { selected.remove(sede); amounts.remove(sede) } else selected.add(sede)
        } else {
            selected.clear(); selected.add(sede)
        }
        chips.forEach { (s, chip) -> chip.isChecked = s in selected }
        rebuildAmounts()
        refresh()
    }

    private fun setCond(value: String) {
        cond = value
        chipContado.isChecked = value == "contado"
        chipCredito.isChecked = value == "credito"
        cardDays.visibility = if (value == "credito") View.VISIBLE else View.GONE
        refresh()
    }

    private fun setCurrency(value: String) {
        currency = value
        chipBs.isChecked = value == "bs"
        chipUsd.isChecked = value == "usd"
        rebuildAmounts()
        refresh()
    }

    private fun rebuildAmounts() {
        layoutAmounts.removeAllViews()
        amountFields.clear()
        val show = mixto && selected.isNotEmpty()
        cardAmounts.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        selected.forEach { sede ->
            val brand = Brands.brandOf(sede)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            }
            row.addView(TextView(this).apply {
                text = if (sede.short.equals(brand.name, true)) sede.short else "${sede.short} · ${brand.name.substringBefore(' ')}"
                setTextColor(INK); textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = currencySymbol(); setTextColor(MUTED); textSize = 13f
                setPadding((6 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            })
            val field = EditText(this).apply {
                setBackgroundResource(R.drawable.bg_field)
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint = "0,00"; setHintTextColor(Color.parseColor("#5F6E7E"))
                setTextColor(INK); textSize = 16f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams((130 * dp).toInt(), (44 * dp).toInt())
                setText(amounts[sede] ?: "")
                addTextChangedListener(simpleWatcher { amounts[sede] = it; refresh() })
            }
            amountFields[sede] = field
            row.addView(field)
            layoutAmounts.addView(row)
        }
    }

    // ---------- Validación y derivados ----------

    private fun parseAmount(text: String?): Double =
        text?.trim()?.replace(",", ".")?.toDoubleOrNull() ?: 0.0

    private fun days(): Int = etDays.text.toString().trim().toIntOrNull() ?: 0

    private fun currencySymbol() = when (currency) { "bs" -> "Bs"; "usd" -> "$"; else -> "—" }

    private fun missing(): List<String> {
        val m = mutableListOf<String>()
        if (if (mixto) selected.size < 2 else selected.isEmpty()) m += if (mixto) "elige 2 o más sedes" else "elige una sede"
        if (mixto && selected.size >= 2 && selected.any { parseAmount(amounts[it]) <= 0 }) m += "montos por sede"
        if (cond == null) m += "condición"
        if (cond == "credito" && days() <= 0) m += "días de crédito"
        if (currency == null) m += "moneda"
        return m
    }

    private fun refresh() {
        if (mixto) {
            val total = selected.sumOf { parseAmount(amounts[it]) }
            tvTotal.text = "${currencySymbol()} ${String.format(Locale("es", "VE"), "%,.2f", total)}"
        }
        val m = missing()
        btnScan.isEnabled = m.isEmpty()
        tvMissing.text = if (m.isEmpty()) "Todo listo. Toca para escanear." else "Falta: " + m.joinToString(" · ")
        tvMissing.setTextColor(if (m.isEmpty()) ACCENT else MUTED)
    }

    private fun amountTag(sede: Sede): String {
        val v = parseAmount(amounts[sede])
        return if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.2f", v).replace('.', '_')
    }

    private fun buildFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val sedeTag = if (mixto) "MIXTO_" + selected.joinToString("_") { Brands.gastoTag(it) + "-" + amountTag(it) }
                      else Brands.gastoTag(selected.first())
        val condTag = if (cond == "credito") "CREDITO-${days()}D" else "CONTADO"
        val curTag = if (currency == "usd") "USD" else "BS"
        return "GASTO_${sedeTag}_${condTag}_${curTag}_$date.jpg"
    }

    private fun buildFolderPath(): List<String> {
        val c = Calendar.getInstance()
        val year = c.get(Calendar.YEAR).toString()
        val month = String.format(Locale.ROOT, "%02d %s", c.get(Calendar.MONTH) + 1, MESES[c.get(Calendar.MONTH)])
        val sedePart = if (mixto) listOf("Mixto") else Brands.gastoFolder(selected.first())
        val condPart = if (cond == "credito") "Crédito" else "Contado"
        return listOf(year, month) + sedePart + condPart
    }

    // ---------- Escaneo y guardado ----------

    private fun startScanner() {
        if (missing().isNotEmpty()) return
        if (MainActivity.FOLDER_ID_GASTOS.startsWith("AQUI_")) {
            toast("Falta configurar la carpeta raíz de Gastos (FOLDER_ID_GASTOS)"); return
        }
        setProcessing(true)
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setGalleryImportAllowed(false)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { e -> setProcessing(false); toast("No se pudo abrir el escáner: ${e.localizedMessage}") }
    }

    private fun saveToDrive(uri: Uri) {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) { setProcessing(false); toast("Inicia sesión primero"); return }
        val fileName = buildFileName()
        val path = buildFolderPath()
        setProcessing(true)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val credential = GoogleAccountCredential.usingOAuth2(this@GastosActivity, listOf(DriveScopes.DRIVE))
                        .apply { selectedAccount = account.account }
                    val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                        .setApplicationName("DocScanner").build()
                    val parentId = ensureFolderPath(drive, MainActivity.FOLDER_ID_GASTOS, path)
                    val metadata = DriveFile().apply { name = fileName; mimeType = MIME_JPEG; parents = listOf(parentId) }
                    val stream = contentResolver.openInputStream(uri) ?: throw IOException("No se pudo leer la imagen")
                    stream.use { drive.files().create(metadata, InputStreamContent(MIME_JPEG, it)).setFields("id, name").execute() }
                }
                setProcessing(false)
                showSaved(fileName, ("Gastos" + " › " + path.joinToString(" › ")))
            } catch (e: Exception) {
                Log.e(TAG, "Error al guardar gasto", e)
                setProcessing(false)
                AlertDialog.Builder(this@GastosActivity)
                    .setTitle("No se pudo guardar")
                    .setMessage(e.localizedMessage ?: "Error desconocido")
                    .setPositiveButton("Reintentar") { _, _ -> pendingUri?.let { saveToDrive(it) } }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    /** Busca (o crea) la cadena de carpetas bajo rootId y devuelve el id de la última. Llamar desde IO. */
    private fun ensureFolderPath(drive: Drive, rootId: String, path: List<String>): String {
        var parent = rootId
        for (name in path) {
            val safeName = name.replace("'", "\\'")
            val found = drive.files().list()
                .setQ("name = '$safeName' and mimeType = '$MIME_FOLDER' and '$parent' in parents and trashed = false")
                .setFields("files(id, name)")
                .setPageSize(5)
                .execute().files
            parent = if (!found.isNullOrEmpty()) found.first().id else {
                val meta = DriveFile().apply { this.name = name; mimeType = MIME_FOLDER; parents = listOf(parent) }
                drive.files().create(meta).setFields("id").execute().id
            }
        }
        return parent
    }

    private fun showSaved(fileName: String, route: String) {
        AlertDialog.Builder(this)
            .setTitle("¡Guardado!")
            .setMessage("Archivo:\n$fileName\n\nCarpeta:\n$route")
            .setPositiveButton("Registrar otro gasto") { _, _ -> resetForm() }
            .setNegativeButton("Salir") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun resetForm() {
        cond = null; currency = null
        chipContado.isChecked = false; chipCredito.isChecked = false
        chipBs.isChecked = false; chipUsd.isChecked = false
        etDays.setText("")
        cardDays.visibility = View.GONE
        pendingUri = null
        setMixto(false)
    }

    private fun setProcessing(on: Boolean) {
        btnProcessing.visibility = if (on) View.VISIBLE else View.GONE
        btnScan.visibility = if (on) View.INVISIBLE else View.VISIBLE
        tvMissing.visibility = if (on) View.INVISIBLE else View.VISIBLE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
    }
}
