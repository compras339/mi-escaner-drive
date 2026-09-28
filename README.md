# Mi Escáner Drive

App Android nativa (Kotlin) que escanea un documento con el escáner de Google Play Services
y lo sube automáticamente a una carpeta de Google Drive.

## Configuración

1. En `app/src/main/java/com/compras339/escanerdrive/MainActivity.kt` reemplaza `FOLDER_ID`
   por el ID de tu carpeta de Drive.
2. En Google Cloud Console:
   - Habilita **Google Drive API**.
   - Crea una credencial OAuth 2.0 de tipo **Android** con el package `com.compras339.escanerdrive`
     y el SHA-1 de tu keystore (`./gradlew signingReport`).
   - Añade tu cuenta como usuario de prueba en la pantalla de consentimiento.
3. Compila: `./gradlew assembleDebug`
