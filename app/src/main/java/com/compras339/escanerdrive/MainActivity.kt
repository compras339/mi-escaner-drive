package com.compras339.escanerdrive

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
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

        /** Usuario maestro: siempre tiene todas las sedes de todas las marcas. */
        const val MASTER_EMAIL = "compras@grupoalimentos4.com"

        /**
         * URL CSV de la hoja "Permisos Escaner" publicada en la web.
         * Fila: correo, sedes   (ej.  cajero@dominio.com, TFB La Granja; TFB Sambil )
         * Palabras especiales: TODAS (todo) · TFB (todas las sedes Trinchero) · ALIMENTOS (todas las de Alimentos Express)
         */
        const val PERMISSIONS_CSV_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSGPpnlAyQAyE-FUhJsa341kNBukuTq3Wwhw4vHJJ17IJeoJO-1dIwsmfZsj_j3Tx_hDoh3YY9m3Ic-/pub?gid=0&single=true&output=csv"

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
    private lateinit var btnSignIn: MaterialButton
    private lateinit var tvCredit: TextView

    // ---------- Estado ----------
    private lateinit var googleSignInClient: GoogleSignInClient
    private var currentAccount: GoogleSignInAccount? = null
    private var allowedSedes: Set<Sede> = emptySet()
    private var visibleSedes: List<Sede> = emptyList()
    private var currentBrand: BrandTheme = Brands.DEFAULT
    private val sedeButtons = mutableMapOf<Sede, MaterialButton>()
    private var selectedSede: Sede? = null
    private var pendingUploadUri: Uri? = null
    private var lastPermissionsLoadAt = 0L
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
                setStatus(
                    "Error al iniciar sesión (código ${e.statusCode})\n\n" +
                        "Paquete: $packageName\nSHA-1 de esta app:\n${getSigningSha1()}"
                )
            }
        }

    private val scannerLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                showLoading(false); setStatus("Escaneo cancelado"); return@registerForActivityResult
            }
            val imageUri = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.firstOrNull()?.imageUri
            if (imageUri == null) {
                showLoading(false); setStatus("No se obtuvo ninguna imagen del escáner"); return@registerForActivityResult
            }
            uploadToDrive(imageUri)
        }

    private val consentLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingUploadUri
            pendingUploadUri = null
            if (result.resultCode == RESULT_OK && uri != null) uploadToDrive(uri)
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
        btnSignIn = findViewById(R.id.btnSignIn)
        tvCredit = findViewById(R.id.tvCredit)

        setupGoogleSignIn()
        applyTheme(Brands.DEFAULT)

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
        if (last != null && GoogleSignIn.hasPermissions(last, Scope(DriveScopes.DRIVE_FILE))) {
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
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }

    private fun signIn() {
        setStatus("Abriendo inicio de sesión de Google...")
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
        hideStatus()
    }

    // ---------- Permisos y tema ----------

    private fun loadPermissions(account: GoogleSignInAccount) {
        val email = account.email?.trim()?.lowercase(Locale.ROOT) ?: ""
        showLoading(true)
        setStatus("Cargando permisos...")

        lifecycleScope.launch {
            val (sedes, error) = withContext(Dispatchers.IO) { fetchAllowedSedes(email) }
            lastPermissionsLoadAt = System.currentTimeMillis()
            allowedSedes = sedes
            currentBrand = Brands.resolveTheme(sedes)
            visibleSedes = Brands.visibleSedes(sedes, currentBrand)

            applyTheme(currentBrand)
            buildSedeButtons()
            scrollSedes.visibility = View.VISIBLE
            btnGallery.visibility = View.VISIBLE
            showLoading(false)

            when {
                error != null -> setStatus("No se pudieron cargar los permisos:\n$error\n\nMantén pulsado el botón de cuenta para reintentar.")
                sedes.isEmpty() -> setStatus("Esta cuenta no tiene ninguna sede asignada.\nContacta al administrador.")
                else -> hideStatus()   // en reposo no mostramos nada: el correo ya se ve en el botón de cuenta
            }
        }
    }

    private fun fetchAllowedSedes(email: String): Pair<Set<Sede>, String?> {
        if (email == MASTER_EMAIL.lowercase(Locale.ROOT)) return Brands.allSedes().toSet() to null
        if (PERMISSIONS_CSV_URL.startsWith("AQUI_")) return emptySet<Sede>() to "Falta configurar PERMISSIONS_CSV_URL"
        return try {
            parsePermissions(downloadText(PERMISSIONS_CSV_URL), email) to null
        } catch (e: Exception) {
            Log.e(TAG, "Error al descargar permisos", e)
            emptySet<Sede>() to (e.localizedMessage ?: "error de red")
        }
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

    private fun parsePermissions(csv: String, email: String): Set<Sede> {
        val result = mutableSetOf<Sede>()
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
                .forEach { token -> result.addAll(Brands.sedesFromToken(token)) }
        }
        return result
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
        window.statusBarColor = brand.statusBarColor
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        tvTitle.setTextColor(brand.primaryColor)
        ivLogo.setImageResource(brand.logoRes)
        tvStatus.background = GradientDrawable().apply {
            cornerRadius = 12 * resources.displayMetrics.density
            setColor(brand.statusBoxColor)
        }
        tvStatus.setTextColor(brand.textColor)
        tvCredit.setTextColor(brand.textColor)
        progressBar.indeterminateTintList = primary

        btnGallery.backgroundTintList = stateList(brand.tonalColor, brand.disabledBg)
        btnGallery.setTextColor(stateList(brand.primaryColor, brand.disabledText))
        btnGallery.iconTint = stateList(brand.primaryColor, brand.disabledText)

        btnSignIn.strokeColor = primary
        btnSignIn.setTextColor(brand.primaryColor)
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

        groups.forEach { (brand, sedes) ->
            if (showHeaders) {
                layoutSedes.addView(TextView(this).apply {
                    text = brand.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(currentBrand.textColor)
                    alpha = 0.7f
                    setPadding((8 * dp).toInt(), (8 * dp).toInt(), 0, 0)
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
                        // Con encabezado de marca quitamos el prefijo "TFB " para que el botón respire
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
                // Fila incompleta en modo 2 columnas: relleno invisible para mantener el ancho
                if (compact && rowSedes.size < columns) {
                    row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
                }
                layoutSedes.addView(row)
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
        sedeButtons.forEach { (sede, button) -> button.isEnabled = signedIn && sede in allowedSedes }
    }

    // ---------- Escáner ----------

    private fun startScanner(sede: Sede) {
        if (sede !in allowedSedes) {
            Toast.makeText(this, "No tienes permiso para ${sede.label}", Toast.LENGTH_SHORT).show(); return
        }
        selectedSede = sede
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setGalleryImportAllowed(false)
            .build()

        showLoading(true)
        setStatus("Escaneando (${sede.label})...")
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { e ->
                Log.e(TAG, "No se pudo iniciar el escáner", e)
                showLoading(false)
                setStatus("Error al abrir el escáner: ${e.localizedMessage}")
            }
    }

    // ---------- Subida a Google Drive ----------

    private fun uploadToDrive(imageUri: Uri) {
        val account = currentAccount
        val sede = selectedSede
        if (account == null || sede == null) {
            showLoading(false); setStatus("No hay cuenta vinculada o sede seleccionada"); return
        }
        showLoading(true)
        setStatus("Subiendo a Drive (${sede.label})...")

        lifecycleScope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) { performUpload(account, sede, imageUri) }
                showLoading(false)
                setStatus("Archivo subido:\n$fileName")
                Toast.makeText(this@MainActivity, "Subida completada", Toast.LENGTH_SHORT).show()
            } catch (e: UserRecoverableAuthIOException) {
                pendingUploadUri = imageUri
                setStatus("Se requiere autorización adicional...")
                consentLauncher.launch(e.intent)
            } catch (e: IOException) {
                Log.e(TAG, "Error de red / API al subir a Drive", e)
                showLoading(false); setStatus("Error al subir a Drive: ${e.localizedMessage}")
            } catch (e: Exception) {
                Log.e(TAG, "Error inesperado al subir a Drive", e)
                showLoading(false); setStatus("Error inesperado: ${e.localizedMessage}")
            }
        }
    }

    @Throws(IOException::class)
    private fun performUpload(account: GoogleSignInAccount, sede: Sede, imageUri: Uri): String {
        val credential = GoogleAccountCredential
            .usingOAuth2(this, listOf(DriveScopes.DRIVE_FILE))
            .apply { selectedAccount = account.account }
        val driveService = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(APP_NAME)
            .build()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val userPrefix = (account.email ?: "usuario").substringBefore("@").replace(Regex("[^A-Za-z0-9._-]"), "_")

        val metadata = DriveFile().apply {
            name = "${sede.key}_${userPrefix}_$timestamp.jpg"
            mimeType = MIME_JPEG
            parents = listOf(FOLDER_ID)
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
            sedeButtons.values.forEach { it.isEnabled = false }
        } else {
            applySedeButtons()
        }
    }
}
