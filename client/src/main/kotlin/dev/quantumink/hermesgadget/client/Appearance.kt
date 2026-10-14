package dev.quantumink.hermesgadget.client

/** Explicit, independently persisted device appearances; no wallpaper-derived overrides. */
enum class Appearance { LIGHT, DARK, MOONLIGHT }

/** Android/Compose-free ARGB semantic inputs shared by the two native adapters. */
data class AppearanceTokens(
    val background: Int,
    val surface: Int,
    val text: Int,
    val secondaryText: Int,
    val accent: Int,
    val onAccent: Int,
    val supportingAccent: Int,
    val onSupportingAccent: Int,
    val accentContainer: Int,
    val onAccentContainer: Int,
    val surfaceLow: Int,
    val surfaceHigh: Int,
    val surfaceHighest: Int,
    val outline: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val inverseSurface: Int,
    val inverseText: Int,
    val inverseAccent: Int
)

fun appearanceTokens(mode: Appearance, watch: Boolean = false): AppearanceTokens {
    val tokens = when (mode) {
        Appearance.LIGHT -> AppearanceTokens(
            background = 0xfff7f8fc.toInt(), surface = 0xffffffff.toInt(),
            text = 0xff1d263d.toInt(), secondaryText = 0xff535e76.toInt(),
            accent = 0xff345fc8.toInt(), onAccent = 0xffffffff.toInt(),
            supportingAccent = 0xff086c88.toInt(), onSupportingAccent = 0xffffffff.toInt(),
            accentContainer = 0xffe4ebfc.toInt(), onAccentContainer = 0xff1d263d.toInt(),
            surfaceLow = 0xfff7f8fc.toInt(), surfaceHigh = 0xffedf0f8.toInt(),
            surfaceHighest = 0xffe4e8f2.toInt(), outline = 0xff68738b.toInt(),
            error = 0xffb3261e.toInt(), onError = 0xffffffff.toInt(),
            errorContainer = 0xfff9dedc.toInt(), onErrorContainer = 0xff410e0b.toInt(),
            inverseSurface = 0xff1d263d.toInt(), inverseText = 0xffffffff.toInt(),
            inverseAccent = 0xffaac6ff.toInt()
        )
        Appearance.DARK -> AppearanceTokens(
            background = 0xff0b0d12.toInt(), surface = 0xff171b23.toInt(),
            text = 0xffe7ebf4.toInt(), secondaryText = 0xffaab4c7.toInt(),
            accent = 0xffaac6ff.toInt(), onAccent = 0xff101b31.toInt(),
            supportingAccent = 0xff80cde5.toInt(), onSupportingAccent = 0xff101b31.toInt(),
            accentContainer = 0xff263852.toInt(), onAccentContainer = 0xffe7ebf4.toInt(),
            surfaceLow = 0xff11151d.toInt(), surfaceHigh = 0xff222834.toInt(),
            surfaceHighest = 0xff2b3240.toInt(), outline = 0xff8490a6.toInt(),
            error = 0xffffb4ab.toInt(), onError = 0xff410e0b.toInt(),
            errorContainer = 0xff652d28.toInt(), onErrorContainer = 0xffffdad6.toInt(),
            inverseSurface = 0xffe7ebf4.toInt(), inverseText = 0xff1d263d.toInt(),
            inverseAccent = 0xff345fc8.toInt()
        )
        Appearance.MOONLIGHT -> AppearanceTokens(
            background = 0xff1d1f31.toInt(), surface = 0xff26293e.toInt(),
            text = 0xffc8d3f5.toInt(), secondaryText = 0xffa9b6dd.toInt(),
            accent = 0xff82aaff.toInt(), onAccent = 0xff1d1f31.toInt(),
            supportingAccent = 0xff86e1fc.toInt(), onSupportingAccent = 0xff1d1f31.toInt(),
            accentContainer = 0xff354567.toInt(), onAccentContainer = 0xffc8d3f5.toInt(),
            surfaceLow = 0xff222438.toInt(), surfaceHigh = 0xff2e324a.toInt(),
            surfaceHighest = 0xff363b54.toInt(), outline = 0xff8997bc.toInt(),
            error = 0xffffb4ab.toInt(), onError = 0xff410e0b.toInt(),
            errorContainer = 0xff652d28.toInt(), onErrorContainer = 0xffffdad6.toInt(),
            inverseSurface = 0xffc8d3f5.toInt(), inverseText = 0xff1d1f31.toInt(),
            inverseAccent = 0xff345fc8.toInt()
        )
    }
    return if (watch && mode != Appearance.LIGHT) {
        tokens.copy(background = 0xff000000.toInt())
    } else {
        tokens
    }
}
