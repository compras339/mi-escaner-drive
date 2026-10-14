package com.compras339.escanerdrive

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.material.button.MaterialButton
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
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
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** Carpeta de Google Drive donde se suben los escaneos. */
        const val FOLDER_ID = "1zD07AzUmTo9tvRnVUmpO2Dk7uJe65PkZ"

        /** Botón "CIERRE DE CAJA": pon true para volver a mostrarlo cuando se defina su funcionamiento. */
        const val CIERRE_ENABLED = false

        /** Carpeta de Google Drive "CIERRE DE CAJA APK" para los soportes de cierre. */
        const val FOLDER_ID_CIERRE = "AQUI_ID_CARPETA_CIERRE"

        /** Subcarpeta de Gastos dentro de la carpeta de facturas; dentro se crea una carpeta por día. */
        const val GASTOS_SUBFOLDER = "GASTOS OPERATIVOS"

        /** Usuario maestro: siempre tiene todas las sedes de todas las marcas. */
        const val MASTER_EMAIL = "compras@grupoalimentos4.com"

        /**
         * URL CSV de la hoja "Permisos Escaner" publicada en la web.
         * Fila: correo, sedes   (ej.  cajero@dominio.com, TFB La Granja; TFB Sambil )
         * Palabras especiales: TODAS (todo) · TFB (todas las sedes Trinchero) · ALIMENTOS (todas las de Alimentos Express)
         */
        const val PERMISSIONS_CSV_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSGPpnlAyQAyE-FUhJsa341kNBukuTq3Wwhw4vHJJ17IJeoJO-1dIwsmfZsj_j3Tx_hDoh3YY9m3Ic-/pub?gid=0&single=true&output=csv"

        /** Números de WhatsApp con código de país (58 = Venezuela), sin + ni espacios. */
        const val WHATSAPP_COMPRAS = "584244329297"
        const val WHATSAPP_TEXT = "Hola, escribo desde la app Escáner de Compras."

        /**
         * ID de la hoja "Permisos Escaner" (el de la URL de edición: docs.google.com/spreadsheets/d/{ID}/edit).
         * La app la lee directamente por Drive (sin el retraso de la versión publicada).
         * Cada usuario debe tener la hoja compartida al menos como Lector.
         */
        const val PERMISSIONS_SHEET_ID = "1C-Vu0VZ6aUGngFNJ-LXYSF9BaujQW-BUiOsO4X4Mdvc"

        const val EXTRA_BRAND_ID = "brand_id"
        const val EXTRA_SEDE_KEYS = "sede_keys"

        private const val APP_NAME = "DocScanner"
        private const val MIME_JPEG = "image/jpeg"

        /** Quita acentos, espacios y símbolos; pasa a minúsculas. */
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "")
    }

    // ---------- Vistas ----------
    private lateinit var rootLayout: FrameLayout
    private lateinit var ivBackground: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var ivLogo: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scrollSedes: ScrollView
    private lateinit var layoutSedes: LinearLayout
    private lateinit var btnGallery: MaterialButton
    private lateinit var btnCierre: MaterialButton
    private lateinit var btnGastos: MaterialButton
    private lateinit var fabWhatsapp: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var whatsappFloat: LinearLayout
    private lateinit var btnSignIn: MaterialButton
    private lateinit var tvCredit: TextView
    private lateinit var contentLayout: ConstraintLayout

    // Pantalla de espera / inicio de sesión
    private lateinit var welcomeLayout: LinearLayout
    private lateinit var webChef: WebView
    private lateinit var tvWelcomeHeadline: TextView
    private lateinit var tvWelcomeSubtitle: TextView
    private lateinit var btnWelcomeSignIn: MaterialButton
    private lateinit var btnWelcomeLoading: GlowBorderLayout
    private lateinit var tvWelcomeLoading: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private var dotsRunnable: Runnable? = null
    private val WELCOME_BG = Color.parseColor("#16212E")
    private val WELCOME_ACCENT = Color.parseColor("#F5A524")
    private val WELCOME_SUBTLE = Color.parseColor("#B7C2CE")

    // ---------- Estado ----------
    private lateinit var googleSignInClient: GoogleSignInClient
    private var currentAccount: GoogleSignInAccount? = null
    private var allowedSedes: Set<Sede> = emptySet()
    private var allowedModules: Set<String> = emptySet()
    private var visibleSedes: List<Sede> = emptyList()
    private var currentBrand: BrandTheme = Brands.DEFAULT
    private val sedeButtons = mutableMapOf<Sede, MaterialButton>()
    private var selectedSede: Sede? = null
    private var selectedIsCierre = false
    private var pendingUploadUris: List<Uri>? = null
    private var lastPermissionsLoadAt = 0L
    /** Tras iniciar sesión, si el usuario es "solo Gastos" abrimos esa pantalla automáticamente una vez. */
    private var autoOpenGastos = false
    /** true mientras la pantalla de espera está visible (para no pisar su barra de estado). */
    private var welcomeShowing = false
    /** Solo comprobamos actualizaciones una vez por apertura de la app. */
    private var updateChecked = false
    private val isBusy get() = progressBar.visibility == View.VISIBLE

    // ---------- Launchers ----------

    private val signInLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                onSignedIn(task.getResult(ApiException::class.java))
            } catch (e: ApiException) {
                Log.e(TAG, "Fallo en Google Sign-In. Código: ${e.statusCode}", e)
                onSignedOut()
                if (e.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) return@registerForActivityResult
                showWelcome(
                    loading = false,
                    error = "Error al iniciar sesión (código ${e.statusCode}). " +
                        "Paquete: $packageName · SHA-1: ${getSigningSha1()}"
                )
            }
        }

    private val scannerLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                showLoading(false); setStatus("Escaneo cancelado"); return@registerForActivityResult
            }
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.mapNotNull { it.imageUri } ?: emptyList()
            if (uris.isEmpty()) {
                showLoading(false); setStatus("No se obtuvo ninguna imagen del escáner"); return@registerForActivityResult
            }
            uploadToDrive(uris)
        }

    private val consentLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = pendingUploadUris
            pendingUploadUris = null
            if (result.resultCode == RESULT_OK && !uris.isNullOrEmpty()) uploadToDrive(uris)
            else { showLoading(false); setStatus("Permiso de Drive denegado. No se pudo subir el archivo.") }
        }

    // ---------- Ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(R.id.rootLayout)
        ivBackground = findViewById(R.id.ivBackground)
        tvTitle = findViewById(R.id.tvTitle)
        ivLogo = findViewById(R.id.ivLogo)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        scrollSedes = findViewById(R.id.scrollSedes)
        layoutSedes = findViewById(R.id.layoutSedes)
        btnGallery = findViewById(R.id.btnGallery)
        btnCierre = findViewById(R.id.btnCierre)
        btnGastos = findViewById(R.id.btnGastos)
        fabWhatsapp = findViewById(R.id.fabWhatsapp)
        whatsappFloat = findViewById(R.id.whatsappFloat)
        btnSignIn = findViewById(R.id.btnSignIn)
        tvCredit = findViewById(R.id.tvCredit)
        contentLayout = findViewById(R.id.contentLayout)

        welcomeLayout = findViewById(R.id.welcomeLayout)
        webChef = findViewById(R.id.webChef)
        tvWelcomeHeadline = findViewById(R.id.tvWelcomeHeadline)
        tvWelcomeSubtitle = findViewById(R.id.tvWelcomeSubtitle)
        btnWelcomeSignIn = findViewById(R.id.btnWelcomeSignIn)
        btnWelcomeLoading = findViewById(R.id.btnWelcomeLoading)
        tvWelcomeLoading = findViewById(R.id.tvWelcomeLoading)
        setupChefWebView()
        btnWelcomeSignIn.setOnClickListener { signIn() }
        btnCierre.setOnClickListener { onCierreClicked() }
        setupWhatsappBubble()
        btnGastos.setOnClickListener {
            startActivity(Intent(this, GastosActivity::class.java).apply {
                putStringArrayListExtra(GastosActivity.EXTRA_SEDE_KEYS, ArrayList(allowedSedes.map { it.key }))
            })
        }

        setupGoogleSignIn()
        applyTheme(Brands.DEFAULT)
        showWelcome(loading = false)

        btnSignIn.setOnClickListener { if (currentAccount == null) signIn() else signOut() }
        // Mantener pulsado el botón de cuenta vuelve a leer la hoja de permisos
        btnSignIn.setOnLongClickListener {
            val acc = currentAccount
            if (acc != null && !isBusy) {
                Toast.makeText(this, "Actualizando permisos...", Toast.LENGTH_SHORT).show()
                loadPermissions(acc); true
            } else false
        }
        btnGallery.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java).apply {
                putExtra(EXTRA_BRAND_ID, currentBrand.id)
                putStringArrayListExtra(EXTRA_SEDE_KEYS, ArrayList(visibleSedes.map { it.key }))
            })
        }
    }

    override fun onStart() {
        super.onStart()
        val last = GoogleSignIn.getLastSignedInAccount(this)
        if (last != null && GoogleSignIn.hasPermissions(last, Scope(DriveScopes.DRIVE))) {
            when {
                currentAccount?.email != last.email -> onSignedIn(last)
                // Al volver a la app, refrescamos permisos si pasó más de 1 minuto y no hay una operación en curso
                !isBusy && System.currentTimeMillis() - lastPermissionsLoadAt > 60_000L -> loadPermissions(last)
            }
        } else {
            onSignedOut()
        }
    }

    // ---------- Autenticación ----------

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE))   // acceso completo: necesario para buscar/crear carpetas compartidas
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun signIn() {
        showWelcome(loading = true)
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            onSignedOut()
        }
    }

    private fun onSignedIn(account: GoogleSignInAccount) {
        currentAccount = account
        btnSignIn.text = "Cerrar sesión (${account.email ?: "cuenta"})"
        autoOpenGastos = true
        showWelcome(loading = true)
        loadPermissions(account)
    }

    private fun onSignedOut() {
        currentAccount = null
        allowedSedes = emptySet()
        visibleSedes = emptyList()
        btnSignIn.text = "Vincular cuenta de Google"
        applyTheme(Brands.DEFAULT)
        buildSedeButtons()
        scrollSedes.visibility = View.GONE
        btnGallery.visibility = View.GONE
        btnCierre.visibility = View.GONE
        btnGastos.visibility = View.GONE
        allowedModules = emptySet()
        hideStatus()
        showWelcome(loading = false)
    }

    // ---------- Permisos y tema ----------

    private fun loadPermissions(account: GoogleSignInAccount) {
        val email = account.email?.trim()?.lowercase(Locale.ROOT) ?: ""
        showLoading(true)
        setStatus("Cargando permisos...")

        lifecycleScope.launch {
            val permisos = withContext(Dispatchers.IO) { fetchPermisos(email) }
            val sedes = permisos.sedes
            val error = permisos.error
            lastPermissionsLoadAt = System.currentTimeMillis()
            allowedSedes = sedes
            allowedModules = permisos.modules
            currentBrand = Brands.resolveTheme(sedes)
            visibleSedes = Brands.visibleSedes(sedes, currentBrand)

            applyTheme(currentBrand)
            buildSedeButtons()
            scrollSedes.visibility = View.VISIBLE
            btnGallery.visibility = View.VISIBLE
            // Cierre de caja: solo en vistas de una marca y con al menos una sede permitida.
            // Oculto temporalmente (CIERRE_ENABLED = false) hasta definir su funcionamiento.
            btnCierre.visibility = if (CIERRE_ENABLED && currentBrand != Brands.MIXED && sedes.isNotEmpty()) View.VISIBLE else View.GONE
            // Gastos: solo para quien tenga el módulo en la columna "Módulos" (compras@ siempre)
            btnGastos.visibility = if (Brands.MODULE_GASTOS in allowedModules && sedes.isNotEmpty()) View.VISIBLE else View.GONE

            // Usuario "SOLO GASTOS": sin escaneo, cierre ni galería; se abre Gastos directamente
            val soloGastos = Brands.MODULE_SOLO_GASTOS in allowedModules && sedes.isNotEmpty()
            if (soloGastos) {
                scrollSedes.visibility = View.GONE
                btnCierre.visibility = View.GONE
                btnGallery.visibility = View.GONE
            }
            showLoading(false)
            showContent()
            if (soloGastos) {
                setStatus("Acceso a Gastos. Usa el botón «Gastos →» para registrar un soporte.")
                if (autoOpenGastos) btnGastos.performClick()
            }
            autoOpenGastos = false

            when {
                error != null -> setStatus("No se pudieron cargar los permisos:\n$error\n\nMantén pulsado el botón de cuenta para reintentar.")
                sedes.isEmpty() -> setStatus("Esta cuenta no tiene ninguna sede asignada.\nContacta al administrador.")
                soloGastos -> Unit     // conserva el mensaje de acceso a Gastos
                else -> hideStatus()   // en reposo no mostramos nada: el correo ya se ve en el botón de cuenta
            }
        }
    }

    data class Permisos(val sedes: Set<Sede>, val modules: Set<String>, val error: String?)

    private fun fetchPermisos(email: String): Permisos {
        if (email == MASTER_EMAIL.lowercase(Locale.ROOT)) return Permisos(Brands.allSedes().toSet(), Brands.ALL_MODULES, null)
        return try {
            val (sedes, modules) = parsePermissions(downloadPermissionsCsv(), email)
            Permisos(sedes, modules, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error al descargar permisos", e)
            Permisos(emptySet(), emptySet(), e.localizedMessage ?: "error de red")
        }
    }

    /**
     * Obtiene el CSV de permisos. Primero por Drive (lectura inmediata de la hoja);
     * si falla, usa la versión publicada en la web como respaldo.
     */
    private fun downloadPermissionsCsv(): String {
        if (!PERMISSIONS_SHEET_ID.startsWith("AQUI_")) {
            try {
                val account = currentAccount ?: GoogleSignIn.getLastSignedInAccount(this)
                if (account != null) {
                    val credential = GoogleAccountCredential.usingOAuth2(this, listOf(DriveScopes.DRIVE))
                        .apply { selectedAccount = account.account }
                    val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                        .setApplicationName(APP_NAME).build()
                    val csv = drive.files().export(PERMISSIONS_SHEET_ID, "text/csv")
                        .executeMediaAsInputStream().bufferedReader().use { it.readText() }
                    if (csv.isNotBlank()) {
                        Log.i(TAG, "Permisos leídos por Drive")
                        return csv
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo leer la hoja por Drive; se usa la versión publicada", e)
            }
        }
        if (PERMISSIONS_CSV_URL.startsWith("AQUI_")) throw IOException("Falta configurar PERMISSIONS_CSV_URL")
        return downloadText(PERMISSIONS_CSV_URL)
    }

    private fun downloadText(baseUrl: String): String {
        // Parámetro variable para evitar cachés intermedias (Google sigue cacheando ~5 min de su lado)
        val url = baseUrl + (if ('?' in baseUrl) "&" else "?") + "t=" + System.currentTimeMillis()
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("Pragma", "no-cache")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val location = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(location).openConnection() as HttpURLConnection
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            redirects++
        }
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Lee la fila del correo. Columna B = sedes, columna C = módulos (GASTOS, CIERRE, TODOS).
     * Las palabras se reconocen en cualquiera de las dos columnas.
     */
    private fun parsePermissions(csv: String, email: String): Pair<Set<Sede>, Set<String>> {
        val sedes = mutableSetOf<Sede>()
        val modules = mutableSetOf<String>()
        csv.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().trim('"')
            if (line.isEmpty()) return@forEach
            val cells = line.split(',').map { it.trim().trim('"') }
            val rowEmail = cells.firstOrNull()?.lowercase(Locale.ROOT) ?: return@forEach
            if (rowEmail != email) return@forEach
            cells.drop(1)
                .flatMap { it.split(';', '|') }
                .map { normalize(it) }
                .filter { it.isNotEmpty() }
                .forEach { token ->
                    sedes.addAll(Brands.sedesFromToken(token))
                    modules.addAll(Brands.modulesFromToken(token))
                }
        }
        return sedes to modules
    }

    /** Aplica colores y logo de la marca a toda la pantalla. */
    private fun applyTheme(brand: BrandTheme) {
        currentBrand = brand
        val primary = ColorStateList.valueOf(brand.primaryColor)

        rootLayout.setBackgroundColor(brand.backgroundColor)
        if (brand.backgroundRes != null) {
            ivBackground.setImageResource(brand.backgroundRes)
            ivBackground.setColorFilter(brand.backgroundOverlay, PorterDuff.Mode.SRC_ATOP)
            ivBackground.visibility = View.VISIBLE
        } else {
            ivBackground.setImageDrawable(null)
            ivBackground.visibility = View.GONE
        }
        if (!welcomeShowing) window.statusBarColor = brand.statusBarColor
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        tvTitle.setTextColor(brand.headerColor ?: brand.primaryColor)
        ivLogo.setImageResource(brand.logoRes)
        tvStatus.background = GradientDrawable().apply {
            cornerRadius = 12 * resources.displayMetrics.density
            setColor(brand.statusBoxColor)
        }
        tvStatus.setTextColor(brand.textColor)
        tvCredit.setTextColor(brand.textColor)
        progressBar.indeterminateTintList = primary

        val accent = brand.headerColor ?: brand.primaryColor   // ámbar en la vista mixta
        btnGallery.backgroundTintList = stateList(brand.tonalColor, brand.disabledBg)
        btnGallery.setTextColor(stateList(accent, brand.disabledText))
        btnGallery.iconTint = stateList(accent, brand.disabledText)

        btnSignIn.strokeColor = ColorStateList.valueOf(accent)
        btnSignIn.setTextColor(accent)

        // Botón de cierre de caja: relleno con el color de marca, texto/ícono en contraste
        btnCierre.backgroundTintList = stateList(brand.primaryColor, brand.disabledBg)
        btnCierre.setTextColor(stateList(brand.onPrimaryColor, brand.disabledText))
        btnCierre.iconTint = stateList(brand.onPrimaryColor, brand.disabledText)
        btnCierre.rippleColor = ColorStateList.valueOf(brand.tonalColor)

        // Píldora "Gastos →": ámbar sobre fondo oscuro en la vista mixta; color de marca en las demás
        btnGastos.backgroundTintList = ColorStateList.valueOf(accent)
        btnGastos.setTextColor(if (brand.headerColor != null) brand.backgroundColor else brand.onPrimaryColor)
        btnGastos.iconTint = ColorStateList.valueOf(if (brand.headerColor != null) brand.backgroundColor else brand.onPrimaryColor)
        btnSignIn.rippleColor = ColorStateList.valueOf(brand.tonalColor)

        sedeButtons.values.forEach { styleSedeButton(it, brand) }
    }

    /**
     * Crea los botones de sede. 1 columna hasta 4 sedes, 2 columnas si hay más.
     * En la vista mixta (varias marcas) cada marca va en su propio bloque con un encabezado.
     */
    private fun buildSedeButtons() {
        layoutSedes.removeAllViews()
        sedeButtons.clear()
        if (visibleSedes.isEmpty()) return

        val columns = if (visibleSedes.size <= 4) 1 else 2
        val compact = columns == 2
        val dp = resources.displayMetrics.density
        val gap = (4 * dp).toInt()

        // Agrupamos por marca manteniendo el orden
        val groups = visibleSedes.groupBy { Brands.brandOf(it) }
        val showHeaders = groups.size > 1
        val cardColor = currentBrand.cardColor

        groups.forEach { (brand, sedes) ->
            // Contenedor del grupo: tarjeta (vista mixta) o el propio layout
            val container: LinearLayout = if (showHeaders && cardColor != null) {
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(cardColor) }
                    setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, (10 * dp).toInt()) }
                }.also { layoutSedes.addView(it) }
            } else layoutSedes

            if (showHeaders) {
                container.addView(TextView(this).apply {
                    text = brand.name.uppercase(Locale.getDefault())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    letterSpacing = 0.18f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(currentBrand.headerColor ?: currentBrand.textColor)
                    alpha = if (currentBrand.headerColor != null) 1f else 0.7f
                    setPadding((6 * dp).toInt(), (4 * dp).toInt(), 0, (4 * dp).toInt())
                })
            }
            sedes.chunked(columns).forEach { rowSedes ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                rowSedes.forEach { sede ->
                    val btn = MaterialButton(this).apply {
                        // Con encabezado de marca quitamos el prefijo para que el botón respire
                        text = if (compact && showHeaders)
                            sede.label.removePrefix("TFB ").removePrefix("VESUVIO ").trim()
                        else sede.label
                        isAllCaps = false
                        maxLines = 2
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 15f)
                        setPadding((8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
                        insetTop = 0; insetBottom = 0
                        minHeight = (if (compact) 48 else 50).let { (it * dp).toInt() }
                        cornerRadius = (24 * dp).toInt()
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                            .apply { setMargins(gap, gap, gap, gap) }
                        isEnabled = sede in allowedSedes
                        setOnClickListener { startScanner(sede) }
                    }
                    styleSedeButton(btn, currentBrand)
                    sedeButtons[sede] = btn
                    row.addView(btn)
                }
                // Fila incompleta en modo 2 columnas: relleno invisible para mantener el ancho.
                // Debe ser MATCH_PARENT en alto; si no, la fila mide su altura por el relleno y los botones desaparecen.
                if (compact && rowSedes.size < columns) {
                    row.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    })
                }
                container.addView(row)
            }
        }
    }

    private fun styleSedeButton(btn: MaterialButton, brand: BrandTheme) {
        btn.backgroundTintList = stateList(brand.primaryColor, brand.disabledBg)
        btn.setTextColor(stateList(brand.onPrimaryColor, brand.disabledText))
        btn.rippleColor = ColorStateList.valueOf(brand.tonalColor)
    }

    private fun stateList(enabledColor: Int, disabledColor: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled)),
        intArrayOf(enabledColor, disabledColor)
    )

    private fun applySedeButtons() {
        val signedIn = currentAccount != null
        btnGallery.isEnabled = signedIn
        btnCierre.isEnabled = signedIn && allowedSedes.isNotEmpty()
        sedeButtons.forEach { (sede, button) -> button.isEnabled = signedIn && sede in allowedSedes }
    }

    // ---------- WhatsApp ----------

    /** Burbuja arrastrable verticalmente por el borde derecho; un toque sin arrastrar abre WhatsApp de Compras. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupWhatsappBubble() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        var startTy = 0f
        var moved = false
        fabWhatsapp.setOnClickListener { openWhatsapp(WHATSAPP_COMPRAS, "Compras") }
        fabWhatsapp.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY; startTy = whatsappFloat.translationY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (!moved && kotlin.math.abs(dy) > slop) moved = true
                    if (moved) {
                        val parent = whatsappFloat.parent as View
                        val minTy = -whatsappFloat.top.toFloat()
                        val maxTy = (parent.height - whatsappFloat.bottom).toFloat()
                        whatsappFloat.translationY = (startTy + dy).coerceIn(minTy, maxTy)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun openWhatsapp(number: String, label: String) {
        if (number.isBlank()) {
            Toast.makeText(this, "Número de $label pendiente de definir", Toast.LENGTH_SHORT).show(); return
        }
        val url = "https://wa.me/$number?text=" + Uri.encode(WHATSAPP_TEXT)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir WhatsApp", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Pantalla de espera / bienvenida ----------

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    private fun setupChefWebView() {
        webChef.setBackgroundColor(Color.TRANSPARENT)
        webChef.isVerticalScrollBarEnabled = false
        webChef.isHorizontalScrollBarEnabled = false
        webChef.settings.javaScriptEnabled = false
        webChef.settings.loadWithOverviewMode = true
        webChef.settings.useWideViewPort = true
        webChef.setOnTouchListener { _, _ -> true }   // sin interacción
        webChef.loadUrl("file:///android_asset/chef.html")
    }

    /**
     * Muestra la pantalla de espera.
     * @param loading true mientras se cargan los permisos (oculta el botón).
     * @param error mensaje opcional (p. ej. fallo de inicio de sesión).
     */
    private fun showWelcome(loading: Boolean, error: String? = null) {
        welcomeShowing = true
        whatsappFloat.visibility = View.GONE
        welcomeLayout.visibility = View.VISIBLE
        contentLayout.visibility = View.GONE
        window.statusBarColor = WELCOME_BG
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        when {
            error != null -> {
                tvWelcomeSubtitle.text = error
                tvWelcomeSubtitle.setTextColor(WELCOME_ACCENT)
            }
            loading -> {
                tvWelcomeSubtitle.text = "Iniciando sesión con el correo que seleccionaste. Esto tomará solo un momento."
                tvWelcomeSubtitle.setTextColor(WELCOME_SUBTLE)
            }
            else -> {
                tvWelcomeSubtitle.text = "Vincula tu cuenta de Google para empezar a escanear tus compras."
                tvWelcomeSubtitle.setTextColor(WELCOME_SUBTLE)
            }
        }
        btnWelcomeSignIn.visibility = if (loading) View.GONE else View.VISIBLE
        btnWelcomeSignIn.isEnabled = !loading
        btnWelcomeLoading.visibility = if (loading) View.VISIBLE else View.GONE
        startWelcomeAnimations()
    }

    // ---------- Actualización automática ----------

    private fun checkForUpdates() {
        if (updateChecked) return
        updateChecked = true
        lifecycleScope.launch {
            val latest = UpdateChecker.fetchLatest() ?: return@launch
            val current = UpdateChecker.currentBuild(this@MainActivity)
            if (latest.build <= current) return@launch
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Nueva versión disponible")
                .setMessage("Hay una versión nueva de la app (${latest.version}, build ${latest.build}).\n" +
                    "Tienes instalada la build $current.\n\nLa actualización se instala encima; no pierdes la sesión.")
                .setPositiveButton("Actualizar") { _, _ -> startUpdate(latest) }
                .setNegativeButton("Más tarde", null)
                .show()
        }
    }

    private fun startUpdate(info: UpdateChecker.Info) {
        showLoading(true)
        setStatus("Descargando actualización… 0 %")
        lifecycleScope.launch {
            try {
                UpdateChecker.downloadAndInstall(this@MainActivity, info) { pct ->
                    setStatus("Descargando actualización… $pct %")
                }
                showLoading(false)
                setStatus("Descarga lista. Pulsa «Instalar» en la ventana de Android.")
            } catch (e: Exception) {
                Log.e(TAG, "Error al actualizar", e)
                showLoading(false)
                setStatus("No se pudo actualizar: ${e.localizedMessage}\nPuedes descargarla desde el enlace de Releases.")
            }
        }
    }

    private fun showContent() {
        checkForUpdates()
        // La burbuja de WhatsApp es para las sedes; el maestro (vista mixta) no la necesita
        whatsappFloat.visibility = if (currentBrand == Brands.MIXED) View.GONE else View.VISIBLE
        welcomeShowing = false
        stopWelcomeAnimations()
        welcomeLayout.visibility = View.GONE
        contentLayout.visibility = View.VISIBLE
        window.statusBarColor = currentBrand.statusBarColor
    }

    private fun startWelcomeAnimations() {
        // Puntos suspensivos
        if (dotsRunnable == null) {
            var step = 0
            val base = "Calentando la sartén"
            dotsRunnable = object : Runnable {
                override fun run() {
                    val dots = ".".repeat(step % 4)
                    tvWelcomeHeadline.text = base + dots
                    tvWelcomeLoading.text = "Iniciando sesión" + dots
                    step++
                    uiHandler.postDelayed(this, 400L)
                }
            }
            uiHandler.post(dotsRunnable!!)
        }
    }

    private fun stopWelcomeAnimations() {
        dotsRunnable?.let { uiHandler.removeCallbacks(it) }
        dotsRunnable = null
    }

    override fun onDestroy() {
        stopWelcomeAnimations()
        webChef.destroy()
        super.onDestroy()
    }

    // ---------- Escáner ----------

    /** Pulsación de "CIERRE DE CAJA": elige la sede (si hay varias) y abre el escáner en modo cierre. */
    private fun onCierreClicked() {
        if (FOLDER_ID_CIERRE.startsWith("AQUI_")) {
            setStatus("Falta configurar la carpeta de cierre de caja (FOLDER_ID_CIERRE)."); return
        }
        val opciones = visibleSedes.filter { it in allowedSedes }
        when (opciones.size) {
            0 -> Toast.makeText(this, "No tienes sedes asignadas", Toast.LENGTH_SHORT).show()
            1 -> startScanner(opciones.first(), cierre = true)
            else -> androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Cierre de caja: ¿de qué sede?")
                .setItems(opciones.map { it.label }.toTypedArray()) { _, i -> startScanner(opciones[i], cierre = true) }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun startScanner(sede: Sede, cierre: Boolean = false) {
        if (sede !in allowedSedes) {
            Toast.makeText(this, "No tienes permiso para ${sede.label}", Toast.LENGTH_SHORT).show(); return
        }
        selectedSede = sede
        selectedIsCierre = cierre
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(20)          // escaneo por lote: cada página se sube como una foto
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setGalleryImportAllowed(false)
            .build()

        showLoading(true)
        setStatus(if (cierre) "Escaneando cierre de caja (${sede.label})..." else "Escaneando (${sede.label})...")
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { e ->
                Log.e(TAG, "No se pudo iniciar el escáner", e)
                showLoading(false)
                setStatus("Error al abrir el escáner: ${e.localizedMessage}")
            }
    }

    // ---------- Subida a Google Drive ----------

    private fun uploadToDrive(uris: List<Uri>) {
        val account = currentAccount
        val sede = selectedSede
        if (account == null || sede == null) {
            showLoading(false); setStatus("No hay cuenta vinculada o sede seleccionada"); return
        }
        val cierre = selectedIsCierre
        val tipo = if (cierre) "cierre de caja" else "a Drive"
        showLoading(true)
        setStatus("Subiendo $tipo (${sede.label})…")

        lifecycleScope.launch {
            val subidos = mutableListOf<String>()
            try {
                val usados = mutableSetOf<String>()
                uris.forEachIndexed { i, uri ->
                    if (uris.size > 1) setStatus("Subiendo ${i + 1} de ${uris.size} (${sede.label})…")
                    subidos += withContext(Dispatchers.IO) { performUpload(account, sede, uri, cierre, usados) }
                }
                showLoading(false)
                setStatus(
                    if (subidos.size == 1) (if (cierre) "Cierre de caja subido:\n" else "Archivo subido:\n") + subidos.first()
                    else "${subidos.size} archivos subidos:\n" + subidos.joinToString("\n")
                )
                Toast.makeText(this@MainActivity, "Subida completada", Toast.LENGTH_SHORT).show()
            } catch (e: UserRecoverableAuthIOException) {
                pendingUploadUris = uris.drop(subidos.size)
                setStatus("Se requiere autorización adicional...")
                consentLauncher.launch(e.intent)
            } catch (e: IOException) {
                Log.e(TAG, "Error de red / API al subir a Drive", e)
                showLoading(false)
                setStatus(errorBatch(subidos, uris.size, "Error al subir a Drive: ${e.localizedMessage}"))
            } catch (e: Exception) {
                Log.e(TAG, "Error inesperado al subir a Drive", e)
                showLoading(false)
                setStatus(errorBatch(subidos, uris.size, "Error inesperado: ${e.localizedMessage}"))
            }
        }
    }

    private fun errorBatch(subidos: List<String>, total: Int, msg: String): String =
        if (subidos.isEmpty()) msg else "$msg\n\nSe subieron ${subidos.size} de $total:\n" + subidos.joinToString("\n")

    @Throws(IOException::class)
    private fun performUpload(
        account: GoogleSignInAccount, sede: Sede, imageUri: Uri, cierre: Boolean,
        usados: MutableSet<String> = mutableSetOf()
    ): String {
        val credential = GoogleAccountCredential
            .usingOAuth2(this, listOf(DriveScopes.DRIVE))
            .apply { selectedAccount = account.account }
        val driveService = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(APP_NAME)
            .build()

        // Fecha y hora de subida de ESTA foto: 20261014_15-30-45 (la hora va al final del nombre)
        val timestamp = SimpleDateFormat("yyyyMMdd_HH-mm-ss", Locale.getDefault()).format(Date())
        val userPrefix = (account.email ?: "usuario").substringBefore("@").replace(Regex("[^A-Za-z0-9._-]"), "_")

        // Estructura: {carpeta raíz} / {SEDE} / {DD-MM-AAAA} / archivo
        val rootId = if (cierre) FOLDER_ID_CIERRE else FOLDER_ID
        val parentId = DriveHelper.ensureFolderPath(driveService, rootId, listOf(sede.label, DriveHelper.todayFolderName()))

        // Nombre único dentro del lote: si dos fotos caen en el mismo segundo, se añade _2, _3…
        var fileName = (if (cierre) "CIERRE_" else "") + "${sede.key}_${userPrefix}_$timestamp.jpg"
        var n = 2
        while (fileName in usados) {
            fileName = (if (cierre) "CIERRE_" else "") + "${sede.key}_${userPrefix}_${timestamp}_$n.jpg"; n++
        }
        usados += fileName

        val metadata = DriveFile().apply {
            // Facturas: Sede_usuario_fecha_hora.jpg · Cierres: CIERRE_Sede_usuario_fecha_hora.jpg
            name = fileName
            mimeType = MIME_JPEG
            parents = listOf(parentId)
        }
        val inputStream = contentResolver.openInputStream(imageUri)
            ?: throw IOException("No se pudo abrir el archivo escaneado: $imageUri")

        return inputStream.use { stream ->
            val uploaded = driveService.files()
                .create(metadata, InputStreamContent(MIME_JPEG, stream))
                .setFields("id, name")
                .execute()
            Log.i(TAG, "Subido a Drive: ${uploaded.name} (${uploaded.id})")
            uploaded.name
        }
    }

    // ---------- Diagnóstico ----------

    private fun getSigningSha1(): String = try {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        val md = MessageDigest.getInstance("SHA-1")
        signatures.joinToString("\n") { sig -> md.digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) } }
    } catch (e: Exception) {
        "desconocido (${e.message})"
    }

    // ---------- Utilidades de UI ----------

    private fun setStatus(message: String) {
        tvStatus.text = message
        tvStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        tvStatus.text = ""
        tvStatus.visibility = View.GONE
    }

    private fun showLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnSignIn.isEnabled = !loading
        if (loading) {
            btnGallery.isEnabled = false
            btnCierre.isEnabled = false
            sedeButtons.values.forEach { it.isEnabled = false }
        } else {
            applySedeButtons()
        }
    }
}
