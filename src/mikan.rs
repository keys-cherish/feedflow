/// 蜜柑计划/动漫 release 标题解析器
/// 从字幕组发布标题中提取番剧名、集数、分辨率等信息
use regex::Regex;
use std::sync::LazyLock;

#[derive(Debug, Clone, serde::Serialize)]
pub struct ParsedAnimeTitle {
    /// 提取的番剧原名
    pub title: String,
    /// 集数（如有）
    pub episode: Option<String>,
    /// 季度（如有）
    pub season: Option<String>,
    /// 字幕组
    pub fansub: Option<String>,
    /// 分辨率
    pub resolution: Option<String>,
    /// 文件大小
    pub file_size: Option<String>,
}

// 预编译正则
static RE_BRACKETS: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"[\[【\(（]([^\]】\)）]*)[\]】\)）]").unwrap()
});
static RE_RESOLUTION: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)(4K|2160[Pp]|1080[Pp]|720[Pp]|576[Pp]|480[Pp])").unwrap()
});
static RE_EPISODE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)(?:第?(\d{1,4})[话話集]|[- ](\d{1,4})(?:\s|$|v\d)|EP?\.?(\d{1,4})|S\d+E(\d{1,4}))").unwrap()
});
static RE_SEASON: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)(?:第([一二三四五六七八九十\d]+)[季期]|Season\s*(\d+)|S(\d+))").unwrap()
});
static RE_SIZE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)\[?(\d+(?:\.\d+)?\s*(?:GB|MB|TB))\]?").unwrap()
});
// 需要从标题中移除的噪声
static RE_NOISE: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(r"(?i)(x26[45]|HEVC|AVC|AAC|FLAC|AC3|Hi10[Pp]|10bit|ASSx2|BDRip|WEBRip|DVDRip|Fin|MKV|MP4|简体|繁体|简日|繁日|简繁|简中|繁中|中文|内嵌|内封|外挂|字幕|双语|CHT|CHS|GB|BIG5|v\d)").unwrap()
});

/// 解析蜜柑/动漫发布标题
pub fn parse_anime_title(raw: &str) -> ParsedAnimeTitle {
    let mut title = raw.to_string();

    // 提取字幕组（通常在第一个方括号内）
    let fansub = RE_BRACKETS.captures(&title).map(|c| c[1].to_string());

    // 提取分辨率
    let resolution = RE_RESOLUTION.captures(&title).map(|c| c[1].to_string());

    // 提取文件大小
    let file_size = RE_SIZE.captures(&title).map(|c| c[1].to_string());

    // 提取集数
    let episode = RE_EPISODE.captures(&title).and_then(|c| {
        c.get(1).or(c.get(2)).or(c.get(3)).or(c.get(4)).map(|m| m.as_str().to_string())
    });

    // 提取季度
    let season = RE_SEASON.captures(&title).and_then(|c| {
        c.get(1).or(c.get(2)).or(c.get(3)).map(|m| m.as_str().to_string())
    });

    // 移除方括号内容
    title = RE_BRACKETS.replace_all(&title, " ").to_string();

    // 移除编码/格式噪声
    title = RE_NOISE.replace_all(&title, "").to_string();

    // 移除分辨率
    title = RE_RESOLUTION.replace_all(&title, "").to_string();

    // 移除集数标记
    title = RE_EPISODE.replace_all(&title, "").to_string();

    // 移除文件大小
    title = RE_SIZE.replace_all(&title, "").to_string();

    // 清理：★分隔符、多余空格、前后空白
    title = title.replace('★', " ");
    title = title.replace('/', " ");
    // 移除连续的标点和空格
    let title = title
        .split_whitespace()
        .filter(|s| !s.is_empty() && *s != "-" && *s != "_")
        .collect::<Vec<_>>()
        .join(" ")
        .trim()
        .to_string();

    // 如果标题中含有 " - " 取第一部分（通常是日文名 - 英文名格式）
    let title = if let Some(idx) = title.find(" - ") {
        let left = title[..idx].trim();
        if left.len() >= 2 { left.to_string() } else { title }
    } else {
        title
    };

    ParsedAnimeTitle {
        title,
        episode,
        season,
        fansub,
        resolution,
        file_size,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mikan_title() {
        let parsed = parse_anime_title(
            "奶活家教AI组★风与木之诗 Kaze to Ki no Uta - Seinaru ka na 1987 ★OVA★DVD576P★x265 AC3 MKV★简体中文"
        );
        assert!(parsed.title.contains("风与木之诗"));
        assert_eq!(parsed.resolution.as_deref(), Some("576P"));
    }

    #[test]
    fn test_bracket_title() {
        let parsed = parse_anime_title(
            "[Lilith-Raws] Sousou no Frieren / 葬送的芙莉莲 - 24 [1080p][ASSx2]"
        );
        assert!(parsed.title.contains("葬送的芙莉莲") || parsed.title.contains("Frieren"));
        assert_eq!(parsed.episode.as_deref(), Some("24"));
        assert_eq!(parsed.resolution.as_deref(), Some("1080p"));
    }
}
