package com.compras339.escanerdrive

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
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

    /** Sedes disponibles. `key` es el prefijo que se usa en el nombre del archivo y en la hoja de permisos. */
    enum class Sede(val key: String, val label: String) {
        CP("CP", "CP"),
        PILAR("Pilar", "Pilar"),
        VINEDO("VVinedo", "V Viñedo"),
        SAMBIL("VSambil", "V Sambil");

        /** Alias aceptados en la hoja de permisos (sin acentos, sin espacios, en minúsculas). */
        fun matches(text: String): Boolean {
            val t = normalize(text)
            return when (this) {
                CP -> t == "cp"
                PILAR -> t == "pilar"
                VINEDO -> t == "vvinedo" || t == "vinedo" || t == "vvinedos"
                SAMBIL -> t == "vsambil" || t == "sambil"
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        /** Carpeta de Google Drive donde se suben los escaneos. */
        const val FOLDER_ID = "1zD07AzUmTo9tvRnVUmpO2Dk7uJe65PkZ"

        /** Usuario maestro: siempre tiene las 4 sedes habilitadas. */
        const val MASTER_EMAIL = "compras@grupoalimentos4.com"

        /**
         * URL CSV de la hoja de permisos publicada en la web (Archivo > Compartir > Publicar en la web > CSV).
         * Formato de cada fila:  correo, sedes   (ej.  cajero.pilar@grupoalimentos4.com, Pilar )
         * Varias sedes separadas por ; o |   (ej.  supervisor@grupoalimentos4.com, CP; Pilar )
         * La palabra TODAS habilita las 4 sedes.
         */
        const val PERMISSIONS_CSV_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vSGPpnlAyQAyE-FUhJsa341kNBukuTq3Wwhw4vHJJ17IJeoJO-1dIwsmfZsj_j3Tx_hDoh3YY9m3Ic-/pub?gid=0&single=true&output=csv"

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
    private lateinit var tvStatus: TextView
    private lateinit var btnSignIn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var sedeButtons: Map<Sede, Button>

    // ---------- Estado ----------
    private lateinit var googleSignInClient: GoogleSignInClient
    private var currentAccount: GoogleSignInAccount? = null
    private var allowedSedes: Set<Sede> = emptySet()
    private var selectedSede: Sede? = null
    private var pendingUploadUri: Uri? = null

    // ---------- Launchers ----------

    private val signInLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                onSignedIn(account)
            } catch (e: ApiException) {
                Log.e(TAG, "Fallo en Google Sign-In. Código: ${e.statusCode}", e)
                onSignedOut()
                setStatus(
                    "Error al iniciar sesión (código ${e.statusCode})\n\n" +
                        "Paquete: $packageName\n" +
                        "SHA-1 de esta app:\n${getSigningSha1()}"
                )
            }
        }

    private val scannerLauncher: ActivityResultLauncher<IntentSenderRequest> =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                showLoading(false)
                setStatus("Escaneo cancelado")
                return@registerForActivityResult
            }
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val imageUri = scanResult?.pages?.firstOrNull()?.imageUri
            if (imageUri == null) {
                showLoading(false)
                setStatus("No se obtuvo ninguna imagen del escáner")
                return@registerForActivityResult
            }
            uploadToDrive(imageUri)
        }

    private val consentLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingUploadUri
            pendingUploadUri = null
            if (result.resultCode == RESULT_OK && uri != null) {
                uploadToDrive(uri)
            } else {
                showLoading(false)
                setStatus("Permiso de Drive denegado. No se pudo subir el archivo.")
            }
        }

    // ---------- Ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnSignIn = findViewById(R.id.btnSignIn)
        progressBar = findViewById(R.id.progressBar)
        sedeButtons = mapOf(
            Sede.CP to findViewById<Button>(R.id.btnScanCp),
            Sede.PILAR to findViewById<Button>(R.id.btnScanPilar),
            Sede.VINEDO to findViewById<Button>(R.id.btnScanVinedo),
            Sede.SAMBIL to findViewById<Button>(R.id.btnScanSambil)
        )

        setupGoogleSignIn()

        btnSignIn.setOnClickListener {
            if (currentAccount == null) signIn() else signOut()
        }
        sedeButtons.forEach { (sede, button) ->
            button.setOnClickListener { startScanner(sede) }
        }
    }

    override fun onStart() {
        super.onStart()
        val lastAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastAccount != null &&
            GoogleSignIn.hasPermissions(lastAccount, Scope(DriveScopes.DRIVE_FILE))
        ) {
            onSignedIn(lastAccount)
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
            setStatus("Sesión cerrada. Esperando inicio de sesión")
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
        btnSignIn.text = "Vincular cuenta de Google"
        applySedeButtons()
        setStatus("Esperando inicio de sesión")
    }

    // ---------- Permisos por sede ----------

    private fun loadPermissions(account: GoogleSignInAccount) {
        val email = account.email?.trim()?.lowercase(Locale.ROOT) ?: ""
        showLoading(true)
        setStatus("Cuenta vinculada: $email\nCargando permisos...")

        lifecycleScope.launch {
            val (sedes, error) = withContext(Dispatchers.IO) { fetchAllowedSedes(email) }
            allowedSedes = sedes
            showLoading(false)
            applySedeButtons()

            val nombres = sedes.sortedBy { it.ordinal }.joinToString(", ") { it.label }
            setStatus(
                when {
                    error != null -> "Cuenta vinculada: $email\n\nNo se pudieron cargar los permisos:\n$error"
                    sedes.isEmpty() -> "Cuenta vinculada: $email\n\nEsta cuenta no tiene ninguna sede asignada.\nContacta al administrador."
                    else -> "Cuenta vinculada: $email\nSedes habilitadas: $nombres\nListo para escanear"
                }
            )
        }
    }

    /** Devuelve las sedes permitidas y, si hubo problema de red, un mensaje de error. */
    private fun fetchAllowedSedes(email: String): Pair<Set<Sede>, String?> {
        if (email == MASTER_EMAIL.lowercase(Locale.ROOT)) return Sede.values().toSet() to null
        if (PERMISSIONS_CSV_URL.startsWith("AQUI_")) {
            return emptySet<Sede>() to "Falta configurar PERMISSIONS_CSV_URL en la app"
        }
        return try {
            val csv = downloadText(PERMISSIONS_CSV_URL)
            parsePermissions(csv, email) to null
        } catch (e: Exception) {
            Log.e(TAG, "Error al descargar permisos", e)
            emptySet<Sede>() to (e.localizedMessage ?: "error de red")
        }
    }

    private fun downloadText(url: String): String {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        // Google publica el CSV con una redirección a otro dominio; la seguimos manualmente.
        var redirects = 0
        while (conn.responseCode in 300..399 && redirects < 5) {
            val location = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(location).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            redirects++
        }
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Busca la fila del correo en el CSV y devuelve las sedes.
     * Acepta separadores , ; | entre sedes y la palabra TODAS.
     */
    private fun parsePermissions(csv: String, email: String): Set<Sede> {
        val result = mutableSetOf<Sede>()
        csv.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().trim('"')
            if (line.isEmpty()) return@forEach
            val cells = line.split(',').map { it.trim().trim('"') }
            val rowEmail = cells.firstOrNull()?.lowercase(Locale.ROOT) ?: return@forEach
            if (rowEmail != email) return@forEach
            val tokens = cells.drop(1).flatMap { it.split(';', '|') }.map { it.trim() }.filter { it.isNotEmpty() }
            tokens.forEach { token ->
                if (normalize(token) == "todas") result.addAll(Sede.values())
                else Sede.values().firstOrNull { it.matches(token) }?.let { result.add(it) }
            }
        }
        return result
    }

    private fun applySedeButtons() {
        val signedIn = currentAccount != null
        sedeButtons.forEach { (sede, button) ->
            button.isEnabled = signedIn && sede in allowedSedes
        }
    }

    // ---------- Escáner ----------

    private fun startScanner(sede: Sede) {
        if (sede !in allowedSedes) {
            Toast.makeText(this, "No tienes permiso para ${sede.label}", Toast.LENGTH_SHORT).show()
            return
        }
        selectedSede = sede

        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setGalleryImportAllowed(false)
            .build()
        val scanner = GmsDocumentScanning.getClient(options)

        showLoading(true)
        setStatus("Escaneando (${sede.label})...")

        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
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
            showLoading(false)
            setStatus("No hay cuenta vinculada o sede seleccionada")
            return
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
                showLoading(false)
                setStatus("Error al subir a Drive: ${e.localizedMessage}")
            } catch (e: Exception) {
                Log.e(TAG, "Error inesperado al subir a Drive", e)
                showLoading(false)
                setStatus("Error inesperado: ${e.localizedMessage}")
            }
        }
    }

    /** Ejecuta la subida real. DEBE llamarse desde Dispatchers.IO. @return nombre del archivo creado. */
    @Throws(IOException::class)
    private fun performUpload(account: GoogleSignInAccount, sede: Sede, imageUri: Uri): String {
        val credential = GoogleAccountCredential
            .usingOAuth2(this, listOf(DriveScopes.DRIVE_FILE))
            .apply { selectedAccount = account.account }

        val driveService = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(APP_NAME)
            .build()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val userPrefix = (account.email ?: "usuario")
            .substringBefore("@")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")

        // Ej.: Pilar_cajero.pilar_20260929_093658.jpg
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

    private fun getSigningSha1(): String {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures ?: emptyArray()
            }
            val md = MessageDigest.getInstance("SHA-1")
            signatures.joinToString("\n") { sig ->
                md.digest(sig.toByteArray()).joinToString(":") { "%02X".format(it) }
            }
        } catch (e: Exception) {
            "desconocido (${e.message})"
        }
    }

    // ---------- Utilidades de UI ----------

    private fun setStatus(message: String) {
        tvStatus.text = message
    }

    private fun showLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnSignIn.isEnabled = !loading
        if (loading) sedeButtons.values.forEach { it.isEnabled = false } else applySedeButtons()
    }
}

