package com.compras339.escanerdrive

import android.graphics.Color

/**
 * Una sede (restaurante). `key` se usa como prefijo del nombre de archivo en Drive
 * y `aliases` son las formas aceptadas en la hoja "Permisos Escaner" (ya normalizadas:
 * minúsculas, sin acentos, sin espacios ni símbolos).
 */
data class Sede(
    val key: String,
    val label: String,
    val aliases: Set<String>
) {
    fun matches(normalizedToken: String): Boolean = normalizedToken in aliases
}

/**
 * Tema visual de una marca. Para agregar una marca nueva basta con crear otro
 * BrandTheme en [Brands] y añadirlo a [Brands.ALL].
 */
data class BrandTheme(
    val id: String,
    val name: String,
    val primaryColor: Int,      // botones, título, barra de estado
    val onPrimaryColor: Int,    // texto sobre primaryColor
    val backgroundColor: Int,   // fondo de la pantalla
    val statusBoxColor: Int,    // fondo del recuadro de estado
    val tonalColor: Int,        // fondo del botón "Ver galería"
    val textColor: Int,         // texto general
    val logoRes: Int,           // drawable del logo
    val sedes: List<Sede>,
    val aliases: Set<String>,   // palabras en la hoja que otorgan TODAS las sedes de esta marca
    val backgroundRes: Int? = null,          // imagen de fondo opcional (se dibuja detrás de todo)
    val backgroundOverlay: Int = 0,          // velo de color sobre la imagen (ARGB, p. ej. 65% negro)
    val statusBarColor: Int = primaryColor,  // color de la barra de estado
    val disabledBg: Int = Color.parseColor("#E0DDE3"),   // botón de sede sin permiso
    val disabledText: Int = Color.parseColor("#9E9E9E")
)

object Brands {

    private fun c(hex: String) = Color.parseColor(hex)

    val ALIMENTOS = BrandTheme(
        id = "alimentos",
        name = "Alimentos Express",
        primaryColor = c("#122235"),
        onPrimaryColor = c("#FFFFFF"),
        backgroundColor = c("#FCF8FD"),
        statusBoxColor = c("#E7E0EC"),
        tonalColor = c("#DCE4EE"),
        textColor = c("#1C1B1F"),
        logoRes = R.drawable.logo_alimentos,
        sedes = listOf(
            Sede("CP", "CP", setOf("cp"))
        ),
        aliases = setOf("alimentos", "alimentosexpress", "ae")
    )

    val TRINCHERO = BrandTheme(
        id = "trinchero",
        name = "Trinchero",
        primaryColor = c("#E3241B"),
        onPrimaryColor = c("#FFFFFF"),
        backgroundColor = c("#F5ECD7"),
        statusBoxColor = c("#EDE0C4"),
        tonalColor = c("#F9D3CF"),
        textColor = c("#3A1A14"),
        logoRes = R.drawable.logo_trinchero,
        sedes = listOf(
            Sede("TFBEventos", "TFB EVENTOS", setOf("tfbeventos", "eventos")),
            Sede("TFBGuataparo", "TFB GUATAPARO", setOf("tfbguataparo", "guataparo")),
            Sede("TFBLaGranja", "TFB LA GRANJA", setOf("tfblagranja", "lagranja", "granja")),
            Sede("TFBLaVina", "TFB LA VIÑA", setOf("tfblavina", "lavina")),
            Sede("TFBManongo", "TFB MAÑONGO", setOf("tfbmanongo", "manongo")),
            Sede("TFBSambil", "TFB SAMBIL", setOf("tfbsambil"))
        ),
        aliases = setOf("tfb", "trinchero")
    )

    val PILAR = BrandTheme(
        id = "pilar",
        name = "Pilar y Juanito",
        primaryColor = c("#B08D4B"),      // dorado del logo
        onPrimaryColor = c("#1A1208"),    // texto oscuro sobre dorado
        backgroundColor = c("#14100C"),   // negro cálido (debajo de la imagen de brasas)
        statusBoxColor = c("#2A211A"),
        tonalColor = c("#3A2E1F"),
        textColor = c("#F3EADB"),         // crema
        logoRes = R.drawable.logo_pilar,
        sedes = listOf(
            Sede("Pilar", "PILAR Y JUANITO", setOf("pilar", "pilaryjuanito"))
        ),
        aliases = setOf("pilaryjuanito", "asador"),
        backgroundRes = R.drawable.bg_brasas,
        backgroundOverlay = c("#A614100C"),   // ~65% de velo oscuro sobre las brasas
        statusBarColor = c("#0E0B08"),
        disabledBg = c("#3A332C"),
        disabledText = c("#8A8077")
    )

    val VESUVIO = BrandTheme(
        id = "vesuvio",
        name = "Vesuvio Pizzería",
        primaryColor = c("#5FB3E6"),      // azul claro (botones, título)
        onPrimaryColor = c("#0B1E2E"),    // texto oscuro sobre azul claro
        backgroundColor = c("#0E1620"),   // azul noche (debajo de la foto)
        statusBoxColor = c("#1C2A38"),
        tonalColor = c("#24384A"),
        textColor = c("#E6F2FA"),
        logoRes = R.drawable.logo_vesuvio,
        sedes = listOf(
            Sede("VVinedo", "VESUVIO VIÑEDO", setOf("vvinedo", "vinedo", "vvinedos", "vinedos", "vesuviovinedo")),
            Sede("VSambil", "VESUVIO SAMBIL", setOf("vsambil", "sambil", "vesuviosambil"))
        ),
        aliases = setOf("vesuvio", "pizzeria"),
        backgroundRes = R.drawable.bg_pizza,
        backgroundOverlay = c("#B80E1620"),   // ~72% de velo azul oscuro sobre la foto
        statusBarColor = c("#0A111A"),
        disabledBg = c("#2A3A4A"),
        disabledText = c("#7A8A99")
    )

    /** Todas las marcas. La primera es la marca por defecto. */
    val ALL: List<BrandTheme> = listOf(ALIMENTOS, PILAR, VESUVIO, TRINCHERO)
    val DEFAULT: BrandTheme = ALIMENTOS

    fun allSedes(): List<Sede> = ALL.flatMap { it.sedes }

    fun byId(id: String?): BrandTheme = ALL.firstOrNull { it.id == id } ?: DEFAULT

    fun brandOf(sede: Sede): BrandTheme = ALL.first { sede in it.sedes }

    fun sedeByKey(key: String): Sede? = allSedes().firstOrNull { it.key.equals(key, ignoreCase = true) }

    /**
     * Tema a aplicar según las sedes permitidas:
     *  - todas las sedes pertenecen a una sola marca distinta de la por defecto -> esa marca
     *  - en cualquier otro caso (vacío, TODAS, mezcla) -> marca por defecto
     */
    fun resolveTheme(allowed: Set<Sede>): BrandTheme {
        if (allowed.isEmpty()) return DEFAULT
        val brands = allowed.map { brandOf(it) }.toSet()
        return if (brands.size == 1 && brands.first() != DEFAULT) brands.first() else DEFAULT
    }

    /**
     * Sedes que se muestran en pantalla (habilitadas o en gris):
     *  - marca específica -> solo sus sedes
     *  - marca por defecto -> sus sedes + las de cualquier otra marca en la que el usuario tenga permiso
     */
    fun visibleSedes(allowed: Set<Sede>, brand: BrandTheme): List<Sede> {
        if (brand != DEFAULT) return brand.sedes
        val others = ALL.filter { it != DEFAULT && it.sedes.any { s -> s in allowed } }
        return DEFAULT.sedes + others.flatMap { it.sedes }
    }

    /** Traduce una palabra de la hoja de permisos (ya normalizada) a sedes. */
    fun sedesFromToken(normalizedToken: String): List<Sede> {
        if (normalizedToken == "todas") return allSedes()
        ALL.firstOrNull { normalizedToken in it.aliases }?.let { return it.sedes }
        return listOfNotNull(allSedes().firstOrNull { it.matches(normalizedToken) })
    }
}
