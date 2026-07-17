package com.modocument.infrastructure.extraction.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlContentExtractorTest {

    @Test
    void tablesBecomeRowsKeyedByHeaders() {
        String html = """
                <html><body><table>
                <tr><th>Name</th><th>Age</th></tr>
                <tr><td>Ada</td><td>36</td></tr>
                <tr><td>Alan</td><td>41</td></tr>
                </table></body></html>
                """;

        List<Map<String, String>> rows = HtmlContentExtractor.rows(html, "https://example.com/p");

        assertThat(rows).containsExactly(
                Map.of("Name", "Ada", "Age", "36"),
                Map.of("Name", "Alan", "Age", "41"));
    }

    @Test
    void pageWithoutTablesBecomesSingleTextRecord() {
        String html = "<html><head><title>Docs</title></head><body><p>Hello world</p></body></html>";

        List<Map<String, String>> rows = HtmlContentExtractor.rows(html, "https://example.com/p");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("url", "https://example.com/p")
                .containsEntry("title", "Docs")
                .containsEntry("text", "Hello world");
    }

    @Test
    void extraCellsGetGeneratedHeaders() {
        String html = """
                <table><tr><th>A</th></tr><tr><td>1</td><td>2</td></tr></table>
                """;

        List<Map<String, String>> rows = HtmlContentExtractor.rows(html, "https://example.com/p");

        assertThat(rows.get(0)).containsEntry("A", "1").containsEntry("column2", "2");
    }

    @Test
    void linksAreResolvedNormalizedAndDeduplicated() {
        String html = """
                <a href="page2.html">two</a>
                <a href="/page3.html#frag">three</a>
                <a href="PAGE2.html">dup-ish</a>
                <a href="page2.html">dup</a>
                <a href="mailto:x@y.z">mail</a>
                """;

        List<String> links = HtmlContentExtractor.links(html, "https://example.com/docs/index.html");

        assertThat(links).containsExactly(
                "https://example.com/docs/page2.html",
                "https://example.com/page3.html",
                "https://example.com/docs/PAGE2.html");
    }
}
