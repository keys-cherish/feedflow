package com.feedflow.app

/**
 * Mikan/anime release title parser.
 * Handles both bracket-style `[fansub] title - ep` and star-style `fansub★title★ep★info` formats.
 */
data class ParsedAnimeTitle(
    val title: String,
    val episode: String? = null,
    val season: String? = null,
    val fansub: String? = null,
    val resolution: String? = null,
    val fileSize: String? = null,
)

private val RE_BRACKETS = Regex("""[\[【(（]([^\]】)）]*)[\]】)）]""")
private val RE_RESOLUTION = Regex("""(?i)(4K|2160[Pp]|1080[Pp]|720[Pp]|576[Pp]|480[Pp])""")
private val RE_EPISODE = Regex("""(?i)(?:第?(\d{1,4})[话話集]|[- ](\d{1,4})(?:\s|$|v\d)|EP?\.?(\d{1,4})|S\d+E(\d{1,4}))""")
private val RE_SEASON = Regex("""(?i)(?:第([一二三四五六七八九十\d]+)[季期]|Season\s*(\d+)|S(\d+))""")
private val RE_SIZE = Regex("""(?i)\[?(\d+(?:\.\d+)?\s*(?:GB|MB|TB))]?""")
private val RE_NOISE = Regex("""(?i)(x26[45]|HEVC|AVC|AAC|FLAC|AC3|Hi10[Pp]|10bit|ASSx2|BDRip|WEBRip|DVDRip|WEB-DL|Fin|MKV|MP4|AV1|opus|简体|繁体|简日|繁日|简繁|简中|繁中|中文|内嵌|内封|外挂|字幕|双语|CHT|CHS|GB|BIG5|GB_CN|Baha|v\d)""")

fun parseMikanTitle(raw: String): ParsedAnimeTitle {
    // Normalize: replace ★ with standard separator for uniform parsing
    val normalized = raw.replace('★', ' ')

    // Extract fansub (usually first bracket group)
    val fansub = RE_BRACKETS.find(normalized)?.groupValues?.get(1)
        ?: run {
            // Star-format: first segment before the anime name is fansub
            // e.g. "字幕组★番名★..." → after ★ normalization, first word block
            val starParts = raw.split('★')
            if (starParts.size >= 3) starParts[0].trim().ifBlank { null } else null
        }

    // Extract resolution
    val resolution = RE_RESOLUTION.find(normalized)?.groupValues?.get(1)

    // Extract file size
    val fileSize = RE_SIZE.find(normalized)?.groupValues?.get(1)

    // Extract episode — first non-null capture group
    val episodeMatch = RE_EPISODE.find(normalized)
    val episode = episodeMatch?.let { m ->
        (1..4).firstNotNullOfOrNull { i -> m.groups[i]?.value }
    }

    // Extract season — first non-null capture group
    val seasonMatch = RE_SEASON.find(normalized)
    val season = seasonMatch?.let { m ->
        (1..3).firstNotNullOfOrNull { i -> m.groups[i]?.value }
    }

    // Clean title: remove brackets, noise, resolution, episode markers, size
    var title = RE_BRACKETS.replace(normalized, " ")
    title = RE_NOISE.replace(title, "")
    title = RE_RESOLUTION.replace(title, "")
    title = RE_EPISODE.replace(title, "")
    title = RE_SIZE.replace(title, "")
    title = title.replace('★', ' ').replace('/', ' ')

    // Remove known type markers
    title = title.replace(Regex("""(?i)\b(OVA|OAD|剧场版|Movie|Special|SP)\b"""), "")

    // Collapse whitespace and remove stray separators
    title = title.split(Regex("\\s+"))
        .filter { it.isNotEmpty() && it != "-" && it != "_" }
        .joinToString(" ")
        .trim()

    // If title has " - " take the left part (usually JP name - EN name)
    val dashIdx = title.indexOf(" - ")
    if (dashIdx > 1) {
        val left = title.substring(0, dashIdx).trim()
        if (left.length >= 2) title = left
    }

    // Remove fansub name from start of title if present
    if (fansub != null && title.startsWith(fansub, ignoreCase = true)) {
        title = title.removePrefix(fansub).trimStart(' ', ':', '：', '-', '_')
    }

    return ParsedAnimeTitle(
        title = title.ifBlank { raw.take(30) },
        episode = episode,
        season = season,
        fansub = fansub,
        resolution = resolution,
        fileSize = fileSize,
    )
}
