package com.musa.opencodeharness.ui.theme

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads theme JSON once and keeps it.
 *
 * Parsing is cheap but not free, and the theme is needed by every composable, so the parsed result
 * is cached per asset path: a `CompositionLocal` re-reads nothing on recomposition, and neither does
 * a second screen. Keyed by path rather than a single slot so a later theme-import screen can add
 * files without invalidating the bundled one.
 */
object ThemeLoader {

    /** The design extracted from `docs/ui/OpenCode Mobile – UI design.pdf`. */
    const val BUNDLED_THEME_PATH: String = "themes/opencode-mobile.json"

    private val cache = ConcurrentHashMap<String, OpenCodeTheme>()

    /** Parse from a string. Used by tests and, later, by theme import. */
    fun parse(json: String): OpenCodeTheme = ThemeParser.parse(json)

    /** Parse from assets, cached. [context] may be any context; the application one is used. */
    fun load(context: Context, path: String = BUNDLED_THEME_PATH): OpenCodeTheme =
        cache.getOrPut(path) { parse(readAsset(context.applicationContext, path)) }

    /**
     * Read failure is loud too. A missing or unreadable asset is a packaging bug, and falling back
     * to a default palette here would hide it behind a plausible-looking screen.
     */
    private fun readAsset(context: Context, path: String): String = try {
        context.assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: java.io.IOException) {
        throw ThemeFormatException("cannot read theme asset \"$path\": ${e.message}")
    }
}
