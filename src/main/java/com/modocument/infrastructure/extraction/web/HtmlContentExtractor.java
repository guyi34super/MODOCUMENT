package com.modocument.infrastructure.extraction.web;

import com.modocument.domain.model.UrlNormalizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic HTML normalization with jsoup: tables become rows keyed by
 * their headers; pages without tables become one record (url/title/text).
 * Links are extracted for the crawl frontier.
 */
public final class HtmlContentExtractor {

    /** Keeps a no-table page record bounded. */
    static final int MAX_TEXT_CHARS = 20000;

    private HtmlContentExtractor() {
    }

    public static List<Map<String, String>> rows(String html, String url) {
        Document doc = Jsoup.parse(html, url);
        Elements tables = doc.select("table");
        List<Map<String, String>> rows = new ArrayList<>();
        for (Element table : tables) {
            rows.addAll(tableRows(table));
        }
        if (!rows.isEmpty()) {
            return rows;
        }
        Map<String, String> page = new LinkedHashMap<>();
        page.put("url", url);
        page.put("title", doc.title());
        String text = doc.body() == null ? "" : doc.body().text();
        page.put("text", text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text);
        return List.of(page);
    }

    private static List<Map<String, String>> tableRows(Element table) {
        Elements trs = table.select("tr");
        if (trs.isEmpty()) {
            return List.of();
        }
        List<String> headers = new ArrayList<>();
        for (Element cell : trs.get(0).select("th, td")) {
            String header = cell.text().trim();
            headers.add(header.isEmpty() ? "column" + (headers.size() + 1) : header);
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < trs.size(); i++) {
            Elements cells = trs.get(i).select("td, th");
            if (cells.isEmpty()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < cells.size(); c++) {
                String header = c < headers.size() ? headers.get(c) : "column" + (c + 1);
                row.put(header, cells.get(c).text().trim());
            }
            rows.add(row);
        }
        return rows;
    }

    public static List<String> links(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        Set<String> links = new LinkedHashSet<>();
        for (Element anchor : doc.select("a[href]")) {
            String resolved = UrlNormalizer.resolve(baseUrl, anchor.attr("href"));
            if (resolved != null) {
                links.add(resolved);
            }
        }
        return new ArrayList<>(links);
    }
}
