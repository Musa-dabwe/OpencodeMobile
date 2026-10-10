package com.musa.opencodeharness.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Turns theme JSON into an [OpenCodeTheme].
 *
 * ## What it accepts
 *
 * The value forms an OpenCode V2 theme file is allowed to use, so a real one can be dropped in:
 *
 *  - hex: `#RGB`, `#RGBA`, `#RRGGBB`, `#RRGGBBAA`
 *  - the keyword `transparent`
 *  - references into the file's own hue palettes, `$hue.<name>.<step>`
 *  - state keys inside a colour group: `{"base": "#0C0C0E", "$hovered": "#111113"}`
 *
 * ## What it rejects
 *
 * Everything else, loudly, with the JSON path in the message. An unknown key is a typo or a token
 * nobody has validated; silently dropping it would put an unvalidated colour into the app, which
 * is the one failure mode this loader exists to prevent.
 *
 * `$`-prefixed keys are metadata rather than tokens (`$schema`, `$comment`, `$light`/`$dark`
 * sections), so they are skipped at the root. They are still errors inside a colour group unless
 * they declare a state.
 */
internal object ThemeParser {

    private const val HUE = "hue"

    /** Envelope keys a V2 theme file carries about itself, which are not tokens. */
    private val ROOT_METADATA = setOf("version", "source", "hue", "variant", "name")

    private val ROOT_GROUPS = setOf(
        "color", "typography", "shape", "spacing", "elevation", "motion",
    )

    private val BACKGROUND_FIELDS = listOf("base", "raised", "raisedAlt", "overlay", "scrim", "inset")
    private val BORDER_FIELDS = listOf("subtle", "strong")
    private val TEXT_FIELDS = listOf("primary", "secondary", "disabled", "sectionHeader", "onAccent")
    private val ACCENT_FIELDS = listOf("base", "hover", "pressed", "muted")
    private val STATUS_FIELDS = listOf("idle", "running", "error")
    private val AGENT_FIELDS = listOf("primary", "subagent", "experimental")
    private val ACTION_FIELDS = listOf("rename", "renameBg", "delete", "deleteBg", "blueFg")

    fun parse(raw: String): OpenCodeTheme {
        val root = try {
            Json.parseToJsonElement(raw)
        } catch (e: IllegalArgumentException) {
            throw ThemeFormatException("theme is not valid JSON: ${e.message}")
        }
        val obj = root as? JsonObject
            ?: throw ThemeFormatException("theme root must be a JSON object, found ${describe(root)}")

        // Hue palettes first: colour values elsewhere may reference them, and they may not
        // reference each other, so they are built with an empty table.
        val hues = hueTable(obj[HUE])
        rejectUnknownRootKeys(obj)

        val colors = obj["color"].asObject("color")
        val background = colorGroup(colors["background"], "color.background", BACKGROUND_FIELDS, hues)
        val border = colorGroup(colors["border"], "color.border", BORDER_FIELDS, hues)
        val text = colorGroup(colors["text"], "color.text", TEXT_FIELDS, hues)
        val accent = colorGroup(colors["accent"], "color.accent", ACCENT_FIELDS, hues)
        val status = colorGroup(colors["status"], "color.status", STATUS_FIELDS, hues)
        val agent = colorGroup(colors["agent"], "color.agent", AGENT_FIELDS, hues)
        val action = colorGroup(colors["action"], "color.action", ACTION_FIELDS, hues)

        return OpenCodeTheme(
            version = obj["version"].asInt("version"),
            source = obj["source"].asString("source"),
            color = ColorTokens(
                background = BackgroundColors(
                    base = background["base"],
                    raised = background["raised"],
                    raisedAlt = background["raisedAlt"],
                    overlay = background["overlay"],
                    scrim = background["scrim"],
                    inset = background["inset"],
                    states = background.states,
                ),
                border = BorderColors(
                    subtle = border["subtle"],
                    strong = border["strong"],
                    states = border.states,
                ),
                text = TextColors(
                    primary = text["primary"],
                    secondary = text["secondary"],
                    disabled = text["disabled"],
                    sectionHeader = text["sectionHeader"],
                    onAccent = text["onAccent"],
                    states = text.states,
                ),
                accent = AccentColors(
                    base = accent["base"],
                    hover = accent["hover"],
                    pressed = accent["pressed"],
                    muted = accent["muted"],
                    states = accent.states,
                ),
                status = StatusColors(
                    idle = status["idle"],
                    running = status["running"],
                    error = status["error"],
                    states = status.states,
                ),
                agent = AgentColors(
                    primary = agent["primary"],
                    subagent = agent["subagent"],
                    experimental = agent["experimental"],
                    states = agent.states,
                ),
                action = ActionColors(
                    rename = action["rename"],
                    renameBg = action["renameBg"],
                    delete = action["delete"],
                    deleteBg = action["deleteBg"],
                    blueFg = action["blueFg"],
                    states = action.states,
                ),
            ),
            typography = typography(obj["typography"].asObject("typography")),
            shape = shape(obj["shape"].asObject("shape")),
            spacing = spacing(obj["spacing"].asObject("spacing")),
            elevation = elevation(obj["elevation"].asObject("elevation"), hues),
            motion = motion(obj["motion"].asObject("motion")),
        )
    }

    // --- root ------------------------------------------------------------------------------

    private fun rejectUnknownRootKeys(root: JsonObject) {
        root.keys.forEach { key ->
            if (key.startsWith("$")) return@forEach // $schema / $comment / $light / $dark
            if (key in ROOT_METADATA || key in ROOT_GROUPS) return@forEach
            throw ThemeFormatException(
                "unknown key \"$key\" at the theme root; expected one of " +
                    (ROOT_METADATA + ROOT_GROUPS).sorted().joinToString(", "),
            )
        }
    }

    /**
     * `"hue": { "blue": { "100": "#...", ..., "900": "#..." } }`, flattened to hue -> step -> colour.
     *
     * Only the two-level form is accepted because that is what a `$hue.<name>.<step>` reference
     * names; anything shallower or deeper cannot be referenced by one.
     */
    private fun hueTable(element: JsonElement?): Map<String, Map<String, Color>> {
        if (element == null) return emptyMap()
        val hues = element.asObject(HUE)
        return hues.mapValues { (name, value) ->
            value.asObject("$HUE.$name").mapValues { (step, colour) ->
                colour.asColor("$HUE.$name.$step", emptyMap())
            }
        }
    }

    // --- colour groups ---------------------------------------------------------------------

    /**
     * One colour group: its named roles plus any `$`-prefixed state variants.
     *
     * State keys are collected rather than typed, because the set of states a V2 theme may declare
     * is open-ended; keeping them in a map means an unknown state is data, not a parse failure.
     */
    private fun colorGroup(
        element: JsonElement?,
        path: String,
        fields: List<String>,
        hues: Map<String, Map<String, Color>>,
    ): ParsedGroup {
        val obj = element.asObject(path)
        val values = LinkedHashMap<String, Color>()
        val states = LinkedHashMap<String, Color>()
        obj.forEach { (key, value) ->
            val where = "$path.$key"
            when {
                key.startsWith("$") -> states[key.substring(1)] = value.asColor(where, hues)
                key in fields -> values[key] = value.asColor(where, hues)
                else -> throw ThemeFormatException(
                    "$path: unknown key \"$key\"; this group has ${fields.joinToString(", ")}",
                )
            }
        }
        val missing = fields.filterNot { it in values }
        if (missing.isNotEmpty()) {
            throw ThemeFormatException("$path is missing ${missing.joinToString(", ")}")
        }
        return ParsedGroup(values, states)
    }

    /** Resolved roles, plus a lookup that fails with the group's path rather than an index error. */
    private class ParsedGroup(
        private val values: Map<String, Color>,
        val states: Map<String, Color>,
    ) {
        operator fun get(name: String): Color =
            values[name] ?: throw ThemeFormatException("colour \"$name\" was not resolved")
    }

    /**
     * The value forms: a hex string, the `transparent` keyword, or a `$hue.<name>.<step>` reference.
     *
     * Anything else is a loud failure naming the path and what was found there.
     */
    private fun JsonElement?.asColor(path: String, hues: Map<String, Map<String, Color>>): Color {
        val element = this ?: throw ThemeFormatException("$path is missing")
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?: throw ThemeFormatException("$path must be a colour, found ${describe(element)}")
        return when {
            text == TRANSPARENT_KEYWORD -> Color.Transparent
            text.startsWith("#") -> parseHexColor(text, path)
            text.startsWith("$") -> resolveReference(text, path, hues)
            else -> throw ThemeFormatException(
                "$path: \"$text\" is not a colour -- expected #RRGGBB, \"$TRANSPARENT_KEYWORD\", " +
                    "or a reference such as \$hue.blue.400",
            )
        }
    }

    private fun resolveReference(
        reference: String,
        path: String,
        hues: Map<String, Map<String, Color>>,
    ): Color {
        val parts = reference.substring(1).split(".")
        if (parts.size != 3 || parts[0] != HUE) {
            throw ThemeFormatException(
                "$path: \"$reference\" is not a reference this loader understands -- the only " +
                    "supported form is \$hue.<name>.<step>, for example \$hue.blue.400",
            )
        }
        val (hueName, step) = parts[1] to parts[2]
        val palette = hues[hueName] ?: throw ThemeFormatException(
            "$path: \$$HUE.$hueName.$step does not resolve -- the theme declares no \"$HUE.$hueName\" " +
                "palette (it has ${keysOrNone(hues.keys)})",
        )
        return palette[step] ?: throw ThemeFormatException(
            "$path: \$$HUE.$hueName.$step does not resolve -- \"$HUE.$hueName\" has steps " +
                palette.keys.sorted().joinToString(", "),
        )
    }

    // --- typography, shape, spacing, elevation, motion ---------------------------------------

    private fun typography(obj: JsonObject): TypeTokens {
        val typeface = obj["typeface"].asString("typography.typeface")
        if (typeface != "system") {
            throw ThemeFormatException(
                "typography.typeface is \"$typeface\", and only \"system\" is supported -- no font " +
                    "is bundled in this app, so there is no other typeface to resolve to",
            )
        }
        fun style(role: TypeRole): TypeStyle {
            val path = "typography.${role.jsonKey}"
            val spec = obj[role.jsonKey].asObject(path)
            val weight = spec["weight"].asInt("$path.weight")
            if (weight !in 100..900 || weight % 100 != 0) {
                throw ThemeFormatException(
                    "$path.weight is $weight -- weights are 100..900 in steps of 100",
                )
            }
            return TypeStyle(
                role = role,
                sizeSp = spec["sizeSp"].asDouble("$path.sizeSp").toFloat(),
                weightHundreds = weight,
                trackingEm = spec["trackingEm"].asDouble("$path.trackingEm"),
                usage = spec["role"].asString("$path.role"),
            )
        }
        return TypeTokens(
            typeface = typeface,
            screenTitle = style(TypeRole.SCREEN_TITLE),
            rowTitle = style(TypeRole.ROW_TITLE),
            meta = style(TypeRole.META),
            sectionHeader = style(TypeRole.SECTION_HEADER),
            body = style(TypeRole.BODY),
            label = style(TypeRole.LABEL),
            mono = style(TypeRole.MONO),
        )
    }

    private fun shape(obj: JsonObject) = ShapeTokens(
        capsuleDp = obj["capsule"].asDouble("shape.capsule").toFloat(),
        cardDp = obj["card"].asDouble("shape.card").toFloat(),
        chipDp = obj["chip"].asDouble("shape.chip").toFloat(),
        fabHeightDp = obj["fabHeightDp"].asDouble("shape.fabHeightDp").toFloat(),
        fabWidthDp = obj["fabWidthDp"].asDouble("shape.fabWidthDp").toFloat(),
    )

    private fun spacing(obj: JsonObject) = SpacingTokens(
        screenMarginDp = obj["screenMarginDp"].asDouble("spacing.screenMarginDp").toFloat(),
        sectionGapDp = obj["sectionGapDp"].asDouble("spacing.sectionGapDp").toFloat(),
        rowGapDp = obj["rowGapDp"].asDouble("spacing.rowGapDp").toFloat(),
        hairlineDp = obj["hairlineDp"].asDouble("spacing.hairlineDp").toFloat(),
    )

    private fun elevation(obj: JsonObject, hues: Map<String, Map<String, Color>>) = ElevationTokens(
        useShadows = obj["useShadows"].asBoolean("elevation.useShadows"),
        // The one colour token outside `color.*`: it has to go through the same resolver, or a
        // theme file could hide a malformed hex here.
        dividerColor = obj["dividerColor"].asColor("elevation.dividerColor", hues),
        dividerWidthDp = obj["dividerWidthDp"].asDouble("elevation.dividerWidthDp").toFloat(),
    )

    private fun motion(obj: JsonObject) = MotionTokens(
        fastMillis = obj["fast"].asInt("motion.fast"),
        mediumMillis = obj["medium"].asInt("motion.medium"),
        respectRemoveAnimations = obj["respectRemoveAnimations"].asBoolean("motion.respectRemoveAnimations"),
    )

    // --- scalar readers ----------------------------------------------------------------------

    private fun JsonElement?.asObject(path: String): JsonObject = this as? JsonObject
        ?: throw ThemeFormatException("$path must be a JSON object, found ${describe(this ?: JsonNull)}")

    /** Numbers and booleans are read as unquoted literals only: a quoted "12" is a mistake, not a 12. */
    private fun JsonElement?.asDouble(path: String): Double = literal(path).let {
        it.doubleOrNull ?: throw ThemeFormatException("$path must be a number, found \"${it.content}\"")
    }

    private fun JsonElement?.asInt(path: String): Int = literal(path).let {
        it.intOrNull ?: throw ThemeFormatException("$path must be an integer, found \"${it.content}\"")
    }

    private fun JsonElement?.asBoolean(path: String): Boolean = literal(path).let {
        it.booleanOrNull
            ?: throw ThemeFormatException("$path must be true or false, found \"${it.content}\"")
    }

    /** Strings are the one case that legitimately IS quoted -- `source`, `typeface`, `role`. */
    private fun JsonElement?.asString(path: String): String {
        val element = this ?: throw ThemeFormatException("$path is missing")
        val primitive = element as? JsonPrimitive
            ?: throw ThemeFormatException("$path must be a string, found ${describe(element)}")
        if (element is JsonNull || !primitive.isString) {
            throw ThemeFormatException("$path must be a string, found ${describe(element)}")
        }
        return primitive.content
    }

    /**
     * The literal behind a scalar key. Quoted numbers and booleans are rejected on purpose:
     * `"weight": "700"` is a mistake that would otherwise parse into a plausible-looking 700.
     */
    private fun JsonElement?.literal(path: String): JsonPrimitive {
        val element = this ?: throw ThemeFormatException("$path is missing")
        val primitive = element as? JsonPrimitive
            ?: throw ThemeFormatException("$path must be a value, found ${describe(element)}")
        if (element is JsonNull || primitive.isString) {
            throw ThemeFormatException("$path must be an unquoted value, found ${describe(element)}")
        }
        return primitive
    }

    private fun describe(element: JsonElement): String = when (element) {
        is JsonObject -> "an object"
        is JsonArray -> "an array"
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) "the string \"${element.content}\"" else "the literal ${element.content}"
    }

    private fun keysOrNone(keys: Set<String>): String =
        if (keys.isEmpty()) "none" else keys.sorted().joinToString(", ")
}
