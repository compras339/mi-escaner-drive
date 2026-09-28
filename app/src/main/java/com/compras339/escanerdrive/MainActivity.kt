package com.compras339.escanerdrive

import android.content.Intent
import android.net.Uri
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** Reemplaza este valor por el ID de tu carpeta de Google Drive. */
        const val FOLDER_ID = "1zD07AzUmTo9tvRnVUmpO2Dk7uJe65PkZ"

        private const val APP_NAME = "DocScanner"
        private const val MIME_JPEG = "image/jpeg"
    }

    // ---------- Vistas ----------
    private lateinit var tvStatus: TextView
    private lateinit var btnSignIn: Button
    private lateinit var btnScan: Button
    private lateinit var progressBar: ProgressBar

    // ---------- Google Sign-In ----------
    private lateinit var googleSignInClient: GoogleSignInClient
    private var currentAccount: GoogleSignInAccount? = null

    /** Uri pendiente de subir cuando el usuario debe aceptar un consentimiento adicional. */
    private var pendingUploadUri: Uri? = null

    // ---------- Launchers (Activity Result API) ----------

    /** Resultado del flujo de Google Sign-In. */
    private val signInLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                onSignedIn(account)
            } catch (e: ApiException) {
                Log.e(TAG, "Fallo en Google Sign-In. Código: ${e.statusCode}", e)
                onSignedOut()
                setStatus("Error al iniciar sesión (código ${e.statusCode})")
            }
        }

    /** Resultado del escáner de documentos (usa IntentSender). */
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

    /**
     * Pantalla de consentimiento adicional de Google (UserRecoverableAuthIOException).
     * Ocurre si el usuario aún no ha concedido el scope DRIVE_FILE al hacer la llamada a la API.
     */
    private val consentLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = pendingUploadUri
            pendingUploadUri = null
            if (result.resultCode == RESULT_OK && uri != null) {
                uploadToDrive(uri) // Reintentamos la subida tras aceptar el consentimiento
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
        btnScan = findViewById(R.id.btnScan)
        progressBar = findViewById(R.id.progressBar)

        setupGoogleSignIn()

        btnSignIn.setOnClickListener {
            if (currentAccount == null) signIn() else signOut()
        }
        btnScan.setOnClickListener { startScanner() }
    }

    override fun onStart() {
        super.onStart()
        // Restaurar sesión previa si existe y ya tiene el scope de Drive
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
            .requestScopes(Scope(DriveScopes.DRIVE_FILE)) // Scope explícito para Drive
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
        btnScan.isEnabled = true
        setStatus("Cuenta vinculada: ${account.email}\nListo para escanear")
    }

    private fun onSignedOut() {
        currentAccount = null
        btnSignIn.text = "Vincular cuenta de Google"
        btnScan.isEnabled = false
        setStatus("Esperando inicio de sesión")
    }

    // ---------- Escáner ----------

    private fun startScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setGalleryImportAllowed(false)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)

        showLoading(true)
        setStatus("Escaneando...")

        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "No se pudo iniciar el escáner", e)
                showLoading(false)
                setStatus("Error al abrir el escáner: ${e.localizedMessage}")
                Toast.makeText(
                    this,
                    "Asegúrate de tener Google Play Services actualizado",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    // ---------- Subida a Google Drive ----------

    private fun uploadToDrive(imageUri: Uri) {
        val account = currentAccount
        if (account == null) {
            showLoading(false)
            setStatus("No hay cuenta vinculada")
            return
        }

        showLoading(true)
        setStatus("Subiendo a Drive...")

        lifecycleScope.launch {
            try {
                val fileId = withContext(Dispatchers.IO) {
                    performUpload(account, imageUri)
                }
                showLoading(false)
                setStatus("Archivo subido: $fileId")
                Toast.makeText(this@MainActivity, "Subida completada", Toast.LENGTH_SHORT).show()

            } catch (e: UserRecoverableAuthIOException) {
                // El usuario debe aprobar el acceso a Drive en una pantalla de Google
                Log.w(TAG, "Se requiere consentimiento adicional del usuario", e)
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

    /**
     * Ejecuta la subida real. DEBE llamarse desde Dispatchers.IO.
     * @return el ID del archivo creado en Drive.
     */
    @Throws(IOException::class)
    private fun performUpload(account: GoogleSignInAccount, imageUri: Uri): String {
        // 1. Credencial OAuth2 con la cuenta del usuario
        val credential = GoogleAccountCredential
            .usingOAuth2(this, listOf(DriveScopes.DRIVE_FILE))
            .apply { selectedAccount = account.account }

        // 2. Cliente de Drive
        val driveService = Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(APP_NAME)
            .build()

        // 3. Metadatos del archivo (nombre + carpeta destino)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val metadata = DriveFile().apply {
            name = "Escaneo_$timestamp.jpg"
            mimeType = MIME_JPEG
            parents = listOf(FOLDER_ID)
        }

        // 4. Contenido: leemos el Uri devuelto por el escáner
        val inputStream = contentResolver.openInputStream(imageUri)
            ?: throw IOException("No se pudo abrir el archivo escaneado: $imageUri")

        return inputStream.use { stream ->
            val content = InputStreamContent(MIME_JPEG, stream)
            val uploaded = driveService.files()
                .create(metadata, content)
                .setFields("id, name")
                .execute()
            Log.i(TAG, "Subido a Drive: ${uploaded.name} (${uploaded.id})")
            uploaded.id
        }
    }

    // ---------- Utilidades de UI ----------

    private fun setStatus(message: String) {
        tvStatus.text = message
    }

    private fun showLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnScan.isEnabled = !loading && currentAccount != null
        btnSignIn.isEnabled = !loading
    }
}

