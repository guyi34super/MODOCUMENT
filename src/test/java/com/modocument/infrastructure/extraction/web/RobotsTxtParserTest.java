package com.modocument.infrastructure.extraction.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RobotsTxtParserTest {

    private final RobotsTxtParser parser = new RobotsTxtParser();

    @Test
    void disallowBlocksMatchingPrefix() {
        RobotsRules rules = parser.parse("""
                User-agent: *
                Disallow: /private/
                """, "MoDocumentBot");

        assertThat(rules.isAllowed("/private/report.html")).isFalse();
        assertThat(rules.isAllowed("/public/page.html")).isTrue();
    }

    @Test
    void allowWinsOverShorterDisallow() {
        RobotsRules rules = parser.parse("""
                User-agent: *
                Disallow: /docs/
                Allow: /docs/public/
                """, "MoDocumentBot");

        assertThat(rules.isAllowed("/docs/internal/a.html")).isFalse();
        assertThat(rules.isAllowed("/docs/public/a.html")).isTrue();
    }

    @Test
    void specificUserAgentGroupBeatsWildcard() {
        RobotsRules rules = parser.parse("""
                User-agent: *
                Disallow: /

                User-agent: modocumentbot
                Allow: /
                Crawl-delay: 2
                """, "MoDocumentBot");

        assertThat(rules.isAllowed("/anything")).isTrue();
        assertThat(rules.crawlDelayMillis()).isEqualTo(2000);
    }

    @Test
    void otherBotsGroupDoesNotApplyToUs() {
        RobotsRules rules = parser.parse("""
                User-agent: OtherBot
                Disallow: /
                """, "MoDocumentBot");

        assertThat(rules.isAllowed("/page")).isTrue();
    }

    @Test
    void emptyDisallowMeansEverythingAllowed() {
        RobotsRules rules = parser.parse("""
                User-agent: *
                Disallow:
                """, "MoDocumentBot");

        assertThat(rules.isAllowed("/any/path")).isTrue();
    }

    @Test
    void wildcardAndAnchorPatternsMatch() {
        RobotsRules rules = parser.parse("""
                User-agent: *
                Disallow: /*.pdf$
                Disallow: /search*results
                """, "MoDocumentBot");

        assertThat(rules.isAllowed("/files/report.pdf")).isFalse();
        assertThat(rules.isAllowed("/files/report.pdf.html")).isTrue();
        assertThat(rules.isAllowed("/search/all/results")).isFalse();
    }

    @Test
    void commentsAndUnknownDirectivesAreIgnored() {
        RobotsRules rules = parser.parse("""
                # global rules
                User-agent: * # everyone
                Sitemap: https://example.com/sitemap.xml
                Disallow: /tmp/
                """, "MoDocumentBot");

        assertThat(rules.isAllowed("/tmp/x")).isFalse();
        assertThat(rules.isAllowed("/")).isTrue();
    }

    @Test
    void sharedGroupOfUserAgentsApplies() {
        RobotsRules rules = parser.parse("""
                User-agent: OtherBot
                User-agent: MoDocumentBot
                Disallow: /blocked/
                """, "MoDocumentBot");

        assertThat(rules.isAllowed("/blocked/x")).isFalse();
    }

    @Test
    void noApplicableGroupAllowsAll() {
        RobotsRules rules = parser.parse("just some malformed content", "MoDocumentBot");

        assertThat(rules.isAllowed("/anything")).isTrue();
    }
}
