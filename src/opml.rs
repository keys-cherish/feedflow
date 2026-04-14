use anyhow::Result;
use quick_xml::events::{BytesDecl, BytesEnd, BytesStart, BytesText, Event};
use quick_xml::{Reader, Writer};
use std::io::Cursor;

use crate::models::{Feed, FolderWithCount};

/// Parsed OPML outline entry
#[derive(Debug, Clone)]
pub struct OpmlOutline {
    pub text: String,
    pub xml_url: Option<String>,
    pub html_url: Option<String>,
    pub children: Vec<OpmlOutline>,
}

/// Generate OPML 2.0 XML from feeds, folders, and their associations
pub fn export_opml(
    feeds: &[Feed],
    folders: &[FolderWithCount],
    feed_folder_map: &[(String, String)],
) -> Result<String> {
    let mut writer = Writer::new_with_indent(Cursor::new(Vec::new()), b' ', 2);

    writer.write_event(Event::Decl(BytesDecl::new("1.0", Some("UTF-8"), None)))?;

    let mut opml = BytesStart::new("opml");
    opml.push_attribute(("version", "2.0"));
    writer.write_event(Event::Start(opml))?;

    // <head>
    writer.write_event(Event::Start(BytesStart::new("head")))?;
    writer.write_event(Event::Start(BytesStart::new("title")))?;
    writer.write_event(Event::Text(BytesText::new("FeedFlow Subscriptions")))?;
    writer.write_event(Event::End(BytesEnd::new("title")))?;
    writer.write_event(Event::Start(BytesStart::new("dateCreated")))?;
    writer.write_event(Event::Text(BytesText::new(
        &chrono::Utc::now().to_rfc2822(),
    )))?;
    writer.write_event(Event::End(BytesEnd::new("dateCreated")))?;
    writer.write_event(Event::End(BytesEnd::new("head")))?;

    // <body>
    writer.write_event(Event::Start(BytesStart::new("body")))?;

    // Build folder_id -> Vec<&Feed> mapping
    let mut folder_feeds: std::collections::HashMap<String, Vec<&Feed>> =
        std::collections::HashMap::new();
    let mut feeds_in_folders: std::collections::HashSet<String> =
        std::collections::HashSet::new();

    for (feed_id, folder_id) in feed_folder_map {
        if let Some(feed) = feeds.iter().find(|f| f.id.to_string() == *feed_id) {
            folder_feeds
                .entry(folder_id.clone())
                .or_default()
                .push(feed);
            feeds_in_folders.insert(feed_id.clone());
        }
    }

    // Write folders with their feeds
    for folder in folders {
        let folder_id = folder.id.to_string();
        let mut outline = BytesStart::new("outline");
        outline.push_attribute(("text", folder.name.as_str()));
        outline.push_attribute(("title", folder.name.as_str()));

        if let Some(entries) = folder_feeds.get(&folder_id) {
            writer.write_event(Event::Start(outline))?;
            for feed in entries {
                write_feed_outline(&mut writer, feed)?;
            }
            writer.write_event(Event::End(BytesEnd::new("outline")))?;
        } else {
            writer.write_event(Event::Start(outline))?;
            writer.write_event(Event::End(BytesEnd::new("outline")))?;
        }
    }

    // Write feeds not in any folder
    for feed in feeds {
        if !feeds_in_folders.contains(&feed.id.to_string()) {
            write_feed_outline(&mut writer, feed)?;
        }
    }

    writer.write_event(Event::End(BytesEnd::new("body")))?;
    writer.write_event(Event::End(BytesEnd::new("opml")))?;

    let result = writer.into_inner().into_inner();
    Ok(String::from_utf8(result)?)
}

fn write_feed_outline(writer: &mut Writer<Cursor<Vec<u8>>>, feed: &Feed) -> Result<()> {
    let mut outline = BytesStart::new("outline");
    outline.push_attribute(("type", "rss"));
    outline.push_attribute(("text", feed.title.as_str()));
    outline.push_attribute(("title", feed.title.as_str()));
    outline.push_attribute(("xmlUrl", feed.url.as_str()));
    if let Some(ref site_url) = feed.site_url {
        outline.push_attribute(("htmlUrl", site_url.as_str()));
    }
    writer.write_event(Event::Empty(outline))?;
    Ok(())
}

/// Parse OPML XML into a tree of outlines
pub fn parse_opml(xml: &str) -> Result<Vec<OpmlOutline>> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut in_body = false;
    let mut stack: Vec<OpmlOutline> = Vec::new();
    let mut result: Vec<OpmlOutline> = Vec::new();

    loop {
        match reader.read_event() {
            Ok(Event::Start(ref e)) => match e.name().as_ref() {
                b"body" => in_body = true,
                b"outline" if in_body => {
                    let outline = parse_outline_attrs(e)?;
                    stack.push(outline);
                }
                _ => {}
            },
            Ok(Event::Empty(ref e)) => {
                if e.name().as_ref() == b"outline" && in_body {
                    let outline = parse_outline_attrs(e)?;
                    if let Some(parent) = stack.last_mut() {
                        parent.children.push(outline);
                    } else {
                        result.push(outline);
                    }
                }
            }
            Ok(Event::End(ref e)) => match e.name().as_ref() {
                b"body" => in_body = false,
                b"outline" if in_body => {
                    if let Some(outline) = stack.pop() {
                        if let Some(parent) = stack.last_mut() {
                            parent.children.push(outline);
                        } else {
                            result.push(outline);
                        }
                    }
                }
                _ => {}
            },
            Ok(Event::Eof) => break,
            Err(e) => anyhow::bail!("Error parsing OPML: {}", e),
            _ => {}
        }
    }

    Ok(result)
}

fn parse_outline_attrs(e: &BytesStart) -> Result<OpmlOutline> {
    let mut text = String::new();
    let mut xml_url = None;
    let mut html_url = None;

    for attr in e.attributes() {
        let attr = attr?;
        let value = String::from_utf8_lossy(&attr.value).into_owned();
        match attr.key.as_ref() {
            b"text" | b"title" => {
                if text.is_empty() {
                    text = value;
                }
            }
            b"xmlUrl" | b"xmlurl" => {
                xml_url = Some(value);
            }
            b"htmlUrl" | b"htmlurl" => {
                html_url = Some(value);
            }
            _ => {}
        }
    }

    Ok(OpmlOutline {
        text,
        xml_url,
        html_url,
        children: Vec::new(),
    })
}
