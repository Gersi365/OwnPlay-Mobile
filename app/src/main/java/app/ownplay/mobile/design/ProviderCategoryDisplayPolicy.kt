package app.ownplay.mobile.design

import java.text.Normalizer
import java.util.Locale

data class ProviderCategoryPresentation(
    val displayName: String,
    val countryCode: String?,
    val flagEmoji: String?,
) {
    val label: String
        get() = listOfNotNull(flagEmoji, displayName.takeIf(String::isNotBlank))
            .joinToString(" ")
}

object ProviderCategoryDisplayPolicy {
    fun label(
        rawName: String,
        hideRegionPrefix: Boolean,
        showFlag: Boolean,
    ): String {
        val presentation = present(
            rawName = rawName,
            hideRegionPrefix = hideRegionPrefix || showFlag,
        )
        return if (showFlag) presentation.label else presentation.displayName
    }

    fun present(
        rawName: String,
        hideRegionPrefix: Boolean = true,
    ): ProviderCategoryPresentation {
        val trimmed = rawName.trim()
        val regionNormalizedName = if (hideRegionPrefix) {
            stripKnownRegionPrefixes(trimmed)
        } else {
            trimmed
        }
        val countryCode = findCountryCode(regionNormalizedName) ?: findCountryCode(trimmed)
        val displayName = if (hideRegionPrefix && countryCode != null) {
            stripLeadingCountryPrefix(regionNormalizedName, countryCode)
        } else {
            regionNormalizedName
        }
        val flag = countryCode
            ?.takeUnless { containsRegionalFlag(displayName) }
            ?.let(::flagEmoji)

        return ProviderCategoryPresentation(
            displayName = displayName,
            countryCode = countryCode,
            flagEmoji = flag,
        )
    }

    internal fun stripKnownRegionPrefixes(rawName: String): String {
        var current = rawName.trim()
        repeat(MAX_PREFIX_SEGMENTS) {
            val normalizedStart = trimLeadingDecorations(current)
            val match = SEPARATOR.find(normalizedStart) ?: return current
            val prefix = normalizedStart.substring(0, match.range.first).trim()
            if (normalize(prefix) !in KNOWN_REGION_PREFIXES) return current
            val remainder = normalizedStart.substring(match.range.last + 1).trim()
            if (remainder.isBlank()) return current
            current = remainder
        }
        return current
    }

    internal fun findCountryCode(rawName: String): String? {
        COUNTRY_DEFINITIONS.forEach { country ->
            if (containsUpperCode(rawName, country.iso2)) return country.iso2
            country.iso3
                ?.takeIf(String::isNotBlank)
                ?.let { if (containsUpperCode(rawName, it)) return country.iso2 }
        }

        val normalized = normalize(rawName)
        COUNTRY_ALIASES.forEach { (alias, countryCode) ->
            if (containsPhrase(normalized, alias)) return countryCode
        }
        return null
    }

    internal fun stripLeadingCountryPrefix(rawName: String, countryCode: String): String {
        val country = COUNTRY_DEFINITIONS.firstOrNull { it.iso2 == countryCode } ?: return rawName.trim()
        val withoutFlag = stripLeadingRegionalFlag(rawName.trim())
        val normalizedStart = trimLeadingDecorations(withoutFlag)
        val candidates = buildSet {
            add(country.iso2)
            country.iso3?.takeIf(String::isNotBlank)?.let(::add)
            addAll(country.names)
        }.sortedByDescending(String::length)

        candidates.forEach { candidate ->
            if (!normalizedStart.regionMatches(0, candidate, 0, candidate.length, ignoreCase = true)) {
                return@forEach
            }
            val boundaryIndex = candidate.length
            if (
                boundaryIndex < normalizedStart.length &&
                normalizedStart[boundaryIndex].isLetterOrDigit()
            ) {
                return@forEach
            }
            return normalizedStart
                .substring(boundaryIndex)
                .trimStart()
                .replaceFirst(LEADING_WRAPPER_OR_SEPARATOR, "")
                .trim()
        }
        return rawName.trim()
    }

    private fun trimLeadingDecorations(value: String): String =
        value.replaceFirst(LEADING_DECORATIONS, "").trimStart()

    internal fun flagEmoji(countryCode: String): String? {
        val code = countryCode.trim().uppercase(Locale.ROOT)
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return null
        return code.map { letter ->
            String(Character.toChars(REGIONAL_INDICATOR_A + (letter.code - 'A'.code)))
        }.joinToString("")
    }

    private fun containsUpperCode(raw: String, code: String): Boolean =
        Regex("(?<![A-Za-z])${Regex.escape(code)}(?![A-Za-z])").containsMatchIn(raw)

    private fun containsPhrase(normalizedText: String, normalizedPhrase: String): Boolean =
        " $normalizedText ".contains(" $normalizedPhrase ")

    private fun stripLeadingRegionalFlag(value: String): String {
        if (value.isEmpty()) return value
        val first = value.codePointAt(0)
        if (first !in REGIONAL_INDICATOR_A..REGIONAL_INDICATOR_Z) return value
        val secondIndex = Character.charCount(first)
        if (secondIndex >= value.length) return value
        val second = value.codePointAt(secondIndex)
        if (second !in REGIONAL_INDICATOR_A..REGIONAL_INDICATOR_Z) return value
        val afterFlag = secondIndex + Character.charCount(second)
        return value.substring(afterFlag).trimStart()
    }

    private fun containsRegionalFlag(value: String): Boolean {
        val codePoints = value.codePoints().toArray()
        for (index in 0 until codePoints.lastIndex) {
            val first = codePoints[index]
            val second = codePoints[index + 1]
            if (
                first in REGIONAL_INDICATOR_A..REGIONAL_INDICATOR_Z &&
                second in REGIONAL_INDICATOR_A..REGIONAL_INDICATOR_Z
            ) {
                return true
            }
        }
        return false
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
        .replace(NON_ALPHANUMERIC, " ")
        .trim()
        .replace(MULTIPLE_SPACES, " ")

    private data class CountryDefinition(
        val iso2: String,
        val iso3: String?,
        val names: Set<String>,
    )

    private val COUNTRY_DEFINITIONS: List<CountryDefinition> by lazy {
        val standard = Locale.getISOCountries().map { iso2 ->
            val locale = Locale.Builder().setRegion(iso2).build()
            CountryDefinition(
                iso2 = iso2,
                iso3 = runCatching { locale.isO3Country }.getOrNull(),
                names = buildSet {
                    locale.getDisplayCountry(Locale.ENGLISH)
                        .takeIf(String::isNotBlank)
                        ?.let(::add)
                    addAll(LOCALIZED_COUNTRY_ALIASES[iso2].orEmpty())
                },
            )
        }.toMutableList()

        if (standard.none { it.iso2 == "XK" }) {
            standard += CountryDefinition(
                iso2 = "XK",
                iso3 = "XKX",
                names = setOf("Kosovo", "Kosova", "Kosove", "Kosovë"),
            )
        }
        standard
    }

    private val COUNTRY_ALIASES: List<Pair<String, String>> by lazy {
        COUNTRY_DEFINITIONS
            .flatMap { country ->
                country.names.mapNotNull { raw ->
                    normalize(raw)
                        .takeIf { it.length >= 3 }
                        ?.let { normalized -> normalized to country.iso2 }
                }
            }
            .distinct()
            .sortedByDescending { it.first.length }
    }

    private val KNOWN_REGION_PREFIXES: Set<String> = setOf(
        "africa",
        "african",
        "americas",
        "asia",
        "asian",
        "balkan",
        "balkans",
        "caribbean",
        "central america",
        "central europe",
        "east asia",
        "eastern europe",
        "eu",
        "europe",
        "european",
        "latam",
        "latin america",
        "middle east",
        "north america",
        "northern europe",
        "oceania",
        "scandinavia",
        "south america",
        "south asia",
        "southeast asia",
        "southern europe",
        "west europe",
        "western europe",
    )

    private val LOCALIZED_COUNTRY_ALIASES: Map<String, Set<String>> = mapOf(
        "AL" to setOf("Albania", "Shqiperi", "Shqipëri", "Shqiperia", "Shqipëria"),
        "AT" to setOf("Austria", "Österreich", "Osterreich"),
        "CH" to setOf("Switzerland", "Schweiz", "Suisse", "Svizzera"),
        "DE" to setOf("Germany", "Deutschland"),
        "ES" to setOf("Spain", "España", "Espana"),
        "FR" to setOf("France", "Français", "Francais"),
        "GB" to setOf("United Kingdom", "Great Britain", "Britain", "UK"),
        "GR" to setOf("Greece", "Hellas", "Ellada"),
        "IT" to setOf("Italy", "Italia"),
        "NL" to setOf("Netherlands", "Holland"),
        "SE" to setOf("Sweden", "Sverige"),
        "TR" to setOf("Turkey", "Türkiye", "Turkiye"),
        "US" to setOf("United States", "United States of America", "USA"),
    )

    private val SEPARATOR = Regex("""\s*(?:\||:|•|·|–|—)\s*|\s+-\s+""")
    private val LEADING_SEPARATOR = Regex("""^(?:\||:|•|·|–|—|-)\s*""")
    private val LEADING_DECORATIONS = Regex("""^[\s|:•·–—\-\[\](){}/\\]+""")
    private val LEADING_WRAPPER_OR_SEPARATOR =
        Regex("""^(?:[\])}]+\s*)?(?:\||:|•|·|–|—|-)?\s*""")
    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val MULTIPLE_SPACES = Regex("\\s+")

    private const val MAX_PREFIX_SEGMENTS = 2
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
    private const val REGIONAL_INDICATOR_Z = 0x1F1FF
}
