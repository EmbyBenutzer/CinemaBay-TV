package org.siloserver.silo.tv.ui.screens.profiles

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.tv.R

/** A bundled index of remote character portraits; image bytes load only when displayed. */
object TvProfileAvatarPresets {
    data class Style(val id: String, @StringRes val label: Int, val summary: String)
    data class Portrait(val provider: String, val collection: String, val name: String, val url: String, val id: String)
    private var appContext: Context? = null
    fun initialize(context: Context) {
        appContext = context.applicationContext
        org.siloserver.silo.common.ui.components.AppProfileAvatarCatalog.install(::catalogImageUrl)
    }
    /** A valid, namespaced seed preserves compatibility with the server preset validator. */
    private const val CatalogRefPrefix = "preset:dicebear:identicon:cinemabay-"
    fun catalogImageUrl(ref: String): String? {
        if (!ref.startsWith(CatalogRefPrefix)) return null
        val id = ref.removePrefix(CatalogRefPrefix)
        if (!Regex("[a-f0-9]{20}").matches(id)) return null
        return byId[id]?.url
    }
    private val catalog: List<Portrait> by lazy {
        val json = requireNotNull(appContext) { "Avatar catalog was not initialized" }
            .assets.open("cinemabay/avatars.json").bufferedReader().use { it.readText() }
        parseCatalog(json)
    }
    internal fun parseCatalog(json: String): List<Portrait> = Json.parseToJsonElement(json).jsonArray.map {
        val o = it.jsonObject
        fun field(key: String) = requireNotNull(o[key]).jsonPrimitive.content
        Portrait(field("provider"), field("collection"), field("name"), field("url"), field("id"))
            .also { p -> require(p.url.startsWith("https://") && p.id.isNotBlank()) }
    }
    private val byId by lazy { catalog.associateBy { it.id } }
    private val byUrl by lazy { catalog.associateBy { it.url } }
    data class Preset(val styleId: String, val seed: String) {
        private val portrait get() = if (styles.any { it.id == styleId }) byId[seed] else null
        val id: String get() = "$styleId:$seed"
        val ref: String get() = portrait?.let { CatalogRefPrefix + it.id } ?: "preset:dicebear:$styleId:$seed"
        val previewUrl: String get() = portrait?.url ?: imageUrl(styleId, seed)
        val name: String get() = portrait?.name ?: seed
        val collection: String? get() = portrait?.collection
    }
    val styles = listOf(
        Style("netflix", R.string.cinemabay_avatars_netflix, "Netflix"),
        Style("disney", R.string.cinemabay_avatars_disney, "Disney+"),
        Style("prime", R.string.cinemabay_avatars_prime, "Prime Video"),
        Style("hbo", R.string.cinemabay_avatars_hbo, "HBO / Max"),
    )
    const val DefaultStyleId = "netflix"
    const val OptionCount = 18
    fun groups(styleId: String): List<String> = catalog.filter { it.provider == styleId }
        .map { it.collection }.distinct().sortedBy { it.lowercase() }
    fun groupSize(styleId: String, group: String): Int = catalog.count { it.provider == styleId && it.collection == group }
    fun pageCount(styleId: String, group: String): Int = ((groupSize(styleId, group) + OptionCount - 1) / OptionCount).coerceAtLeast(1)
    fun batch(styleId: String, batch: Int = 0, group: String? = null): List<Preset> {
        val style = styleId.takeIf { id -> styles.any { it.id == id } } ?: DefaultStyleId
        val entries = catalog.filter { it.provider == style && (group == null || it.collection == group) }
        if (entries.isEmpty()) return emptyList()
        val pages = (entries.size + OptionCount - 1) / OptionCount
        val page = Math.floorMod(batch, pages)
        return entries.drop(page * OptionCount).take(OptionCount).map { Preset(style, it.id) }
    }
    fun presetRef(styleId: String, seed: String): String = Preset(styleId, seed).ref
    /** Legacy refs remain readable so existing profiles never lose their avatar. */
    fun imageUrl(styleId: String, seed: String, size: Int = 256): String =
        byId[seed]?.url ?: "https://api.dicebear.com/9.x/${Uri.encode(styleId)}/png?seed=${Uri.encode(seed)}&size=$size"
    fun parseRef(value: String?): Preset? {
        if (value?.startsWith(CatalogRefPrefix) == true) {
            byId[value.removePrefix(CatalogRefPrefix)]?.let { return Preset(it.provider, it.id) }
        }
        if (value?.trim()?.startsWith("https://") == true) {
            byUrl[value.trim()]?.let { return Preset(it.provider, it.id) }
        }
        val parts = value?.trim()?.split(':', limit = 4).orEmpty()
        if (parts.size != 4 || !parts[0].equals("preset", true) || !parts[1].equals("dicebear", true)) return null
        if (parts[2].isBlank() || parts[3].isBlank()) return null
        return Preset(parts[2], parts[3])
    }
    fun effectiveAvatarRef(styleId: String, selectedSeed: String?, batch: Int, name: String, fallbackAvatar: String?): String? {
        selectedSeed?.takeIf { Regex("[a-f0-9]{20}").matches(it) }?.let { byId[it] }?.let { return Preset(it.provider, it.id).ref }
        fallbackAvatar?.takeIf { it.isNotBlank() }?.let { return it }
        return batch(styleId, batch).firstOrNull()?.ref
    }
}
