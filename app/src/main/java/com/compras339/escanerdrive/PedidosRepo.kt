package com.compras339.escanerdrive

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.model.AddSheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.ValueRange
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Acceso a la hoja "planificación de compras": cada pestaña es una semana; la app usa
 * la última pestaña (la de más a la derecha) que no sea "Observaciones".
 *
 * Fila 1 = encabezados. Columnas reconocidas por su nombre (sin acentos/mayúsculas):
 *   Nº Control · Fecha pedido · Proveedor · Sede · Nº ítem · Código · Descripción ·
 *   Cantidad · Unidad · Estado · Fecha vencimiento (la crea la app si no existe)
 */
class PedidosRepo(context: Context, account: GoogleSignInAccount) {

    companion object {
        const val OBS_SHEET = "Observaciones"
        const val COL_VENC = "Fecha vencimiento"
        const val ESTADO_PENDIENTE = "PENDIENTE"
        const val ESTADO_CON_SOPORTE = "CON SOPORTE"
        private val OBS_HEADER = listOf("Fecha y hora", "Semana", "Pedido", "Proveedor", "Sede", "Observación", "Usuario")

        /** Índice de columna (0 = A) → letra A1 ("A", "B", ..., "AA"). */
        fun colLetter(index: Int): String {
            var i = index
            val sb = StringBuilder()
            do { sb.insert(0, ('A' + i % 26)); i = i / 26 - 1 } while (i >= 0)
            return sb.toString()
        }

        fun norm(s: String?): String = Brands.slug(s ?: "").lowercase(Locale.ROOT)
    }

    data class Linea(
        val fila: Int,            // número de fila en la hoja (1 = encabezado)
        val numero: String, val codigo: String, val descripcion: String,
        val cantidad: String, val unidad: String, var vencimiento: String
    )

    data class Pedido(
        val control: String, val fecha: String, val proveedor: String, val sede: String,
        var estado: String, val lineas: MutableList<Linea>
    ) {
        /** Clave estable del pedido para fotos y observaciones: proveedor normalizado (o el nº de control si existe). */
        val clave: String get() = Brands.slug(proveedor).take(24).ifBlank { Brands.slug(control) }

        fun toJson(): String = JSONObject().apply {
            put("control", control); put("fecha", fecha); put("proveedor", proveedor); put("sede", sede); put("estado", estado)
            put("lineas", JSONArray().apply { lineas.forEach { l -> put(JSONObject().apply {
                put("fila", l.fila); put("numero", l.numero); put("codigo", l.codigo); put("descripcion", l.descripcion)
                put("cantidad", l.cantidad); put("unidad", l.unidad); put("vencimiento", l.vencimiento) }) } })
        }.toString()

        companion object {
            fun fromJson(json: String): Pedido {
                val o = JSONObject(json)
                val arr = o.getJSONArray("lineas")
                val lineas = (0 until arr.length()).map { i -> val l = arr.getJSONObject(i)
                    Linea(l.getInt("fila"), l.getString("numero"), l.getString("codigo"), l.getString("descripcion"),
                        l.getString("cantidad"), l.getString("unidad"), l.getString("vencimiento")) }.toMutableList()
                return Pedido(o.getString("control"), o.getString("fecha"), o.getString("proveedor"), o.getString("sede"), o.getString("estado"), lineas)
            }
        }
    }

    data class Semana(val tab: String, val pedidos: List<Pedido>, val columnas: Map<String, Int>)
    data class Observacion(val fechaHora: String, val semana: String, val control: String, val texto: String, val usuario: String)

    private val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE))
        .apply { selectedAccount = account.account }
    val sheets: Sheets = Sheets.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
        .setApplicationName("DocScanner").build()
    val drive: Drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
        .setApplicationName("DocScanner").build()
    private val sheetId = MainActivity.PEDIDOS_SHEET_ID

    // ---------- Lectura ----------

    // La lista de pestañas cambia una vez por semana: se consulta como mucho cada 10 minutos (ahorra cuota de Google)
    private var tabCache: String? = null
    private var tabCacheAt = 0L

    /** Nombre de la última pestaña (semana vigente), ignorando "Observaciones". */
    fun ultimaPestana(forzar: Boolean = false): String {
        val cached = tabCache
        if (!forzar && cached != null && System.currentTimeMillis() - tabCacheAt < 10 * 60_000L) return cached
        val meta = sheets.spreadsheets().get(sheetId).setFields("sheets.properties.title").execute()
        val titles = meta.sheets.map { it.properties.title }
        val tab = titles.lastOrNull { !it.equals(OBS_SHEET, ignoreCase = true) }
            ?: throw IOException("La hoja no tiene pestañas de pedidos")
        tabCache = tab; tabCacheAt = System.currentTimeMillis()
        return tab
    }

    /** Lee la semana vigente y agrupa las filas en pedidos (Nº Control + Proveedor). */
    fun cargarSemana(forzarPestanas: Boolean = false): Semana {
        val tab = ultimaPestana(forzarPestanas)
        val values = try {
            sheets.spreadsheets().values().get(sheetId, "'$tab'!A1:Z").execute().getValues() ?: emptyList()
        } catch (e: com.google.api.client.googleapis.json.GoogleJsonResponseException) {
            // La pestaña guardada ya no existe (renombrada/borrada): vuelve a leer la lista
            if (e.statusCode == 400 && !forzarPestanas) return cargarSemana(true) else throw e
        }
        if (values.isEmpty()) return Semana(tab, emptyList(), emptyMap())

        val header = values[0].map { norm(it?.toString()) }
        /** Busca la columna cuyo encabezado sea igual o contenga alguno de los nombres (sin acentos/espacios). */
        fun col(vararg names: String): Int {
            val keys = names.map { norm(it) }
            keys.forEach { k -> header.indexOf(k).takeIf { it >= 0 }?.let { return it } }
            keys.forEach { k -> header.indexOfFirst { h -> h.isNotEmpty() && (h.startsWith(k) || h.contains(k)) }.takeIf { it >= 0 }?.let { return it } }
            return -1
        }
        val columnas = mapOf(
            "control" to col("Nº Control", "N° Control", "No Control", "Control", "Pedido"),
            "fecha" to col("Fecha pedido", "Fecha"),
            "proveedor" to col("Proveedor asignado", "Proveedor"),
            "sede" to col("Sede"),
            "numero" to col("Nº ítem", "N° ítem", "No item", "Nº"),
            "codigo" to col("Código", "Codigo", "ID"),
            "descripcion" to col("Descripción del ítem", "Descripción", "Descripcion", "Item", "Producto"),
            "cantidad" to col("Cantidad", "Cant"),
            "unidad" to col("Unidad de medida", "Unidad", "Und"),
            "estado" to col("Estado"),
            "venc" to col(COL_VENC, "Vencimiento")
        )
        if (columnas["proveedor"]!! < 0) {
            throw IOException("La pestaña '$tab' no tiene una columna de proveedor en la fila 1")
        }
        fun cell(row: List<Any?>, key: String): String {
            val i = columnas[key] ?: -1
            return if (i in row.indices) (row[i]?.toString() ?: "").trim() else ""
        }

        val pedidos = linkedMapOf<String, Pedido>()
        val sinControl = columnas["control"]!! < 0
        values.drop(1).forEachIndexed { idx, row ->
            val filaHoja = idx + 2
            val proveedor = cell(row, "proveedor")
            if (proveedor.isEmpty() && cell(row, "descripcion").isEmpty()) return@forEachIndexed
            // Sin columna de control: un pedido por proveedor, numerado por orden de aparición
            val control = if (sinControl) "" else cell(row, "control")
            val key = if (sinControl) norm(proveedor) else norm(control) + "|" + norm(proveedor)
            val pedido = pedidos.getOrPut(key) {
                val numero = if (sinControl) String.format(Locale.ROOT, "%03d", pedidos.size + 1) else control
                Pedido(numero, cell(row, "fecha").ifBlank { tab }, proveedor.ifBlank { "(sin proveedor)" }, cell(row, "sede"),
                    cell(row, "estado").ifBlank { ESTADO_PENDIENTE }, mutableListOf())
            }
            pedido.lineas += Linea(filaHoja, cell(row, "numero").ifBlank { (pedido.lineas.size + 1).toString() },
                cell(row, "codigo"), cell(row, "descripcion"), cell(row, "cantidad"), cell(row, "unidad"), cell(row, "venc"))
            if (cell(row, "estado").equals(ESTADO_CON_SOPORTE, ignoreCase = true)) pedido.estado = ESTADO_CON_SOPORTE
        }
        return Semana(tab, pedidos.values.toList(), columnas)
    }

    fun observaciones(semana: String, clave: String): List<Observacion> = try {
        val values = sheets.spreadsheets().values().get(sheetId, "'$OBS_SHEET'!A2:G").execute().getValues() ?: emptyList()
        values.mapNotNull { r ->
            fun c(i: Int) = if (i in r.indices) (r[i]?.toString() ?: "") else ""
            val o = Observacion(c(0), c(1), c(2), c(5), c(6))
            if (norm(o.control) == norm(clave) && norm(o.semana) == norm(semana)) o else null
        }
    } catch (e: Exception) { emptyList() }   // la pestaña aún no existe

    // ---------- Escritura ----------

    /** Escribe las fechas de vencimiento; crea la columna "Fecha vencimiento" al final si no existe. */
    fun guardarVencimientos(semana: Semana, pedido: Pedido) {
        var vencCol = semana.columnas["venc"] ?: -1
        if (vencCol < 0) {
            val header = sheets.spreadsheets().values().get(sheetId, "'${semana.tab}'!1:1").execute().getValues()?.firstOrNull() ?: emptyList()
            vencCol = header.size
            sheets.spreadsheets().values()
                .update(sheetId, "'${semana.tab}'!${colLetter(vencCol)}1", ValueRange().setValues(listOf(listOf(COL_VENC))))
                .setValueInputOption("USER_ENTERED").execute()
        }
        val letter = colLetter(vencCol)
        val data = pedido.lineas.map { l ->
            ValueRange().setRange("'${semana.tab}'!$letter${l.fila}").setValues(listOf(listOf(l.vencimiento)))
        }
        if (data.isNotEmpty()) {
            sheets.spreadsheets().values().batchUpdate(sheetId,
                com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest().setValueInputOption("USER_ENTERED").setData(data)
            ).execute()
        }
    }

    /** Marca todas las filas del pedido con el estado dado (crea la columna Estado si falta). */
    fun marcarEstado(semana: Semana, pedido: Pedido, estado: String) {
        var col = semana.columnas["estado"] ?: -1
        if (col < 0) {
            val header = sheets.spreadsheets().values().get(sheetId, "'${semana.tab}'!1:1").execute().getValues()?.firstOrNull() ?: emptyList()
            col = header.size
            sheets.spreadsheets().values()
                .update(sheetId, "'${semana.tab}'!${colLetter(col)}1", ValueRange().setValues(listOf(listOf("Estado"))))
                .setValueInputOption("USER_ENTERED").execute()
        }
        val letter = colLetter(col)
        val data = pedido.lineas.map { l -> ValueRange().setRange("'${semana.tab}'!$letter${l.fila}").setValues(listOf(listOf(estado))) }
        sheets.spreadsheets().values().batchUpdate(sheetId,
            com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest().setValueInputOption("USER_ENTERED").setData(data)
        ).execute()
        pedido.estado = estado
    }

    /** Añade una fila a la pestaña "Observaciones" (la crea con encabezados si no existe). */
    fun guardarObservacion(semana: String, pedido: Pedido, texto: String, usuario: String) {
        val meta = sheets.spreadsheets().get(sheetId).setFields("sheets.properties.title").execute()
        if (meta.sheets.none { it.properties.title.equals(OBS_SHEET, ignoreCase = true) }) {
            sheets.spreadsheets().batchUpdate(sheetId, BatchUpdateSpreadsheetRequest().setRequests(listOf(
                Request().setAddSheet(AddSheetRequest().setProperties(SheetProperties().setTitle(OBS_SHEET)))
            ))).execute()
            sheets.spreadsheets().values().update(sheetId, "'$OBS_SHEET'!A1", ValueRange().setValues(listOf(OBS_HEADER)))
                .setValueInputOption("USER_ENTERED").execute()
        }
        val fechaHora = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        val row = listOf(fechaHora, semana, pedido.clave, pedido.proveedor, pedido.sede, texto, usuario)
        sheets.spreadsheets().values().append(sheetId, "'$OBS_SHEET'!A1", ValueRange().setValues(listOf(row)))
            .setValueInputOption("USER_ENTERED").setInsertDataOption("INSERT_ROWS").execute()
    }
}
