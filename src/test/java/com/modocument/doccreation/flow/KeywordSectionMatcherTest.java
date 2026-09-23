package com.modocument.doccreation.flow;

import com.modocument.doccreation.entity.DocBlueprintSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordSectionMatcherTest {

    private final KeywordSectionMatcher matcher = new KeywordSectionMatcher();

    private static DocBlueprintSection section(int id, int order, String keywords) {
        DocBlueprintSection s = new DocBlueprintSection();
        s.setDocBlueprintSectionId(id);
        s.setOrderIndex(order);
        s.setKeywords(keywords);
        return s;
    }

    @Test
    void matchesKeywordsAsSubstringsOfTheFileName() {
        List<DocBlueprintSection> sections = List.of(
                section(1, 0, "roof,tiles"),
                section(2, 1, "dc2,routing"));

        assertThat(matcher.match("Roof_East_02.jpg", sections)).contains(1);
        assertThat(matcher.match("dc2-overview.png", sections)).contains(2);
    }

    @Test
    void higherKeywordScoreWins() {
        List<DocBlueprintSection> sections = List.of(
                section(1, 0, "roof"),
                section(2, 1, "roof,tiles"));

        assertThat(matcher.match("roof_tiles.png", sections)).contains(2);
    }

    @Test
    void tieGoesToTheEarlierSection() {
        List<DocBlueprintSection> sections = List.of(
                section(5, 0, "roof"),
                section(6, 1, "roof"));

        assertThat(matcher.match("roof.png", sections)).contains(5);
    }

    @Test
    void noMatchMeansUnsortedNotLost() {
        List<DocBlueprintSection> sections = List.of(section(1, 0, "roof"));

        assertThat(matcher.match("IMG_2041.jpg", sections)).isEmpty();
        assertThat(matcher.match(null, sections)).isEmpty();
    }

    @Test
    void blankKeywordsNeverMatch() {
        List<DocBlueprintSection> sections = List.of(section(1, 0, null), section(2, 1, " , "));

        assertThat(matcher.match("anything.png", sections)).isEmpty();
    }
}
