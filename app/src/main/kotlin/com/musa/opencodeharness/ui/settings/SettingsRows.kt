package com.musa.opencodeharness.ui.settings

/**
 * One settings row, as data.
 *
 * ## The two fields that carry the honesty rules
 *
 *  - [value] is null when nothing real backs one. The design's column is full of values
 *    (`sonnet-4.5`, `3 connected`, `9 available`, `212 MB`); not one of them can be produced by
 *    this app today, and a plausible-looking stand-in is the specific failure this phase forbids.
 *    Null renders NOTHING -- not a dash, not `0`, not an em dash that reads as a value.
 *  - [enabled] is false when there is no destination to go to. There is no navigation graph in this
 *    app (Task 2's ruling), so every row here is a label today. A disabled row draws no chevron,
 *    takes no click and carries disabled semantics: the chevron is an affordance, and offering one
 *    that leads nowhere is how a screen ships a promise.
 */
internal data class SettingsRow(
    val label: String,
    /** Right-aligned and muted; null means the row shows no value at all. */
    val value: String?,
    val enabled: Boolean,
)

/** A labelled group of rows, rendered as one `surface` card with hairline dividers. */
internal data class SettingsSection(
    val title: String,
    val rows: List<SettingsRow>,
)

/**
 * The design's own value for `Appearance`, and the only one this app can honestly show.
 *
 * `opencode · dark` in the design names the theme file and the mode. The app ships exactly ONE
 * palette and it is dark -- there is no light scheme, no dynamic colour and no system setting to
 * follow, which is a decision recorded on `OpenCodeTheme` itself. So `dark` is a fact about the
 * app rather than a fabricated reading of a preference that does not exist. The row stays disabled:
 * there is nothing to switch TO.
 */
internal const val APPEARANCE_VALUE: String = "dark"

/** A row that has no destination and no value. The default, and the overwhelming majority. */
private fun row(label: String, value: String? = null) = SettingsRow(label = label, value = value, enabled = false)

/**
 * The grouped rows from design section 3.7, in the design's order.
 *
 * Every row is disabled and every value is null but one, and that is the point: the brief for this
 * task says rows that cannot honestly be populated are shown disabled rather than rendered with
 * invented values. Listing them is still useful -- it says what the product will offer -- while
 * rendering `3 connected` or `212 MB` would be a claim this build cannot back.
 *
 * `SettingsScreenTest` asserts the exact set of labels and that not one row carries a value other
 * than [APPEARANCE_VALUE], so a future edit that fills one in has to say so deliberately.
 */
internal fun settingsSections(): List<SettingsSection> = listOf(
    SettingsSection(
        title = "MODELS",
        rows = listOf(
            row("Providers & models"),
            row("Agents & subagents"),
        ),
    ),
    SettingsSection(
        title = "SAFETY",
        rows = listOf(
            row("Permissions"),
            row("MCP servers"),
            row("Skills & commands"),
        ),
    ),
    SettingsSection(
        title = "APP",
        rows = listOf(
            row("Appearance", APPEARANCE_VALUE),
            row("Notifications"),
            row("Sessions & data"),
            row("Server & runtime"),
        ),
    ),
)
