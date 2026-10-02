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
    val aliases: Set<String>,
    val short: String = label   // nombre corto sin marca (para Gastos: "Sambil", "La Granja", "CP"...)
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
    val disabledText: Int = Color.parseColor("#9E9E9E"),
    val cardColor: Int? = null,              // fondo de la tarjeta por marca en la vista mixta (null = sin tarjeta)
    val headerColor: Int? = null,            // color del nombre de marca en la vista mixta (null = textColor)
    val tag: String = ""                     // etiqueta corta para nombres de archivo de Gastos (AE, VESUVIO...)
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
            Sede("CP", "CP", setOf("cp"), short = "CP")
        ),
        aliases = setOf("alimentos", "alimentosexpress", "ae"),
        tag = "AE"
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
            Sede("TFBEventos", "TFB EVENTOS", setOf("tfbeventos", "eventos"), short = "Eventos"),
            Sede("TFBGuataparo", "TFB GUATAPARO", setOf("tfbguataparo", "guataparo"), short = "Guataparo"),
            Sede("TFBLaGranja", "TFB LA GRANJA", setOf("tfblagranja", "lagranja", "granja"), short = "La Granja"),
            Sede("TFBLaVina", "TFB LA VIÑA", setOf("tfblavina", "lavina"), short = "La Viña"),
            Sede("TFBManongo", "TFB MAÑONGO", setOf("tfbmanongo", "manongo"), short = "Mañongo"),
            Sede("TFBSambil", "TFB SAMBIL", setOf("tfbsambil"), short = "Sambil")
        ),
        aliases = setOf("tfb", "trinchero"),
        tag = "TRINCHERO"
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
            Sede("Pilar", "PILAR Y JUANITO", setOf("pilar", "pilaryjuanito"), short = "Pilar y Juanito")
        ),
        aliases = setOf("pilaryjuanito", "asador"),
        backgroundRes = R.drawable.bg_brasas,
        backgroundOverlay = c("#A614100C"),   // ~65% de velo oscuro sobre las brasas
        statusBarColor = c("#0E0B08"),
        disabledBg = c("#3A332C"),
        disabledText = c("#8A8077"),
        tag = "PILARYJUANITO"
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
            Sede("VVinedo", "VESUVIO VIÑEDO", setOf("vvinedo", "vinedo", "vvinedos", "vinedos", "vesuviovinedo"), short = "Viñedo"),
            Sede("VSambil", "VESUVIO SAMBIL", setOf("vsambil", "sambil", "vesuviosambil"), short = "Sambil")
        ),
        aliases = setOf("vesuvio", "pizzeria"),
        backgroundRes = R.drawable.bg_pizza,
        backgroundOverlay = c("#B80E1620"),   // ~72% de velo azul oscuro sobre la foto
        statusBarColor = c("#0A111A"),
        disabledBg = c("#2A3A4A"),
        disabledText = c("#7A8A99"),
        tag = "VESUVIO"
    )

    /**
     * Tema de la vista mixta (usuario con TODAS las sedes o con sedes de varias marcas).
     * Misma estética que la pantalla de bienvenida. No tiene sedes propias.
     */
    val MIXED = BrandTheme(
        id = "mixed",
        name = "Todas las sedes",
        primaryColor = c("#2A3A4E"),      // botones pizarra
        onPrimaryColor = c("#F4F1EA"),
        backgroundColor = c("#16212E"),   // azul noche
        statusBoxColor = c("#223347"),
        tonalColor = c("#223347"),
        textColor = c("#F4F1EA"),
        logoRes = R.drawable.logo_alimentos_light,
        sedes = emptyList(),
        aliases = emptySet(),
        statusBarColor = c("#16212E"),
        disabledBg = c("#1B2838"),
        disabledText = c("#5F6E7E"),
        cardColor = c("#1E2C3C"),
        headerColor = c("#F5A524")        // ámbar
    )

    /** Marcas con sedes. La primera es la marca por defecto. */
    val ALL: List<BrandTheme> = listOf(ALIMENTOS, PILAR, VESUVIO, TRINCHERO)
    val DEFAULT: BrandTheme = ALIMENTOS

    fun allSedes(): List<Sede> = ALL.flatMap { it.sedes }

    fun byId(id: String?): BrandTheme = (ALL + MIXED).firstOrNull { it.id == id } ?: DEFAULT

    fun brandOf(sede: Sede): BrandTheme = ALL.first { sede in it.sedes }

    fun sedeByKey(key: String): Sede? = allSedes().firstOrNull { it.key.equals(key, ignoreCase = true) }

    /**
     * Tema a aplicar según las sedes permitidas:
     *  - vacío -> marca por defecto
     *  - todas las sedes de una sola marca -> esa marca
     *  - sedes de varias marcas (o TODAS) -> tema mixto
     */
    fun resolveTheme(allowed: Set<Sede>): BrandTheme {
        if (allowed.isEmpty()) return DEFAULT
        val brands = allowed.map { brandOf(it) }.toSet()
        return if (brands.size == 1) brands.first() else MIXED
    }

    /**
     * Sedes que se muestran en pantalla (habilitadas o en gris):
     *  - marca concreta -> todas sus sedes
     *  - tema mixto -> todas las sedes de cada marca en la que el usuario tenga permiso
     */
    fun visibleSedes(allowed: Set<Sede>, brand: BrandTheme): List<Sede> {
        if (brand != MIXED) return brand.sedes
        return ALL.filter { b -> b.sedes.any { it in allowed } }.flatMap { it.sedes }
    }

    // ---------- Módulos (columna "Módulos" de la hoja de permisos) ----------
    const val MODULE_GASTOS = "GASTOS"
    const val MODULE_CIERRE = "CIERRE"
    /** Acceso exclusivo a Gastos: oculta escaneo, cierre y galería; abre Gastos al entrar. */
    const val MODULE_SOLO_GASTOS = "SOLO_GASTOS"
    val ALL_MODULES = setOf(MODULE_GASTOS, MODULE_CIERRE)

    /** Traduce una palabra de la hoja (ya normalizada) a módulos; vacío si no es un módulo. */
    fun modulesFromToken(normalizedToken: String): Set<String> = when (normalizedToken) {
        "gastos", "gasto" -> setOf(MODULE_GASTOS)
        "sologastos", "sologasto", "gastossolo", "solo" -> setOf(MODULE_GASTOS, MODULE_SOLO_GASTOS)
        "cierre", "cierres", "cierredecaja" -> setOf(MODULE_CIERRE)
        "todos" -> ALL_MODULES
        else -> emptySet()
    }

    /** Etiqueta de sede para nombres de archivo de Gastos: AE-CP, VESUVIO-SAMBIL, PILARYJUANITO... */
    fun gastoTag(sede: Sede): String {
        val brand = brandOf(sede)
        val solo = sede.short.equals(brand.name, ignoreCase = true)
        return if (solo) brand.tag else brand.tag + "-" + slug(sede.short)
    }

    /** Ruta de carpeta de Gastos para una sede: [Marca, Sede] o solo [Marca] si la marca tiene una única sede con su nombre. */
    fun gastoFolder(sede: Sede): List<String> {
        val brand = brandOf(sede)
        val solo = sede.short.equals(brand.name, ignoreCase = true)
        return if (solo) listOf(brand.name) else listOf(brand.name, sede.short)
    }

    /** MAYÚSCULAS sin acentos ni símbolos. */
    fun slug(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .uppercase(java.util.Locale.ROOT)
            .replace(Regex("[^A-Z0-9]"), "")

    /** Traduce una palabra de la hoja de permisos (ya normalizada) a sedes. */
    fun sedesFromToken(normalizedToken: String): List<Sede> {
        if (normalizedToken == "todas") return allSedes()
        ALL.firstOrNull { normalizedToken in it.aliases }?.let { return it.sedes }
        return listOfNotNull(allSedes().firstOrNull { it.matches(normalizedToken) })
    }
}
