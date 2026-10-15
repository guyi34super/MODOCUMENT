package com.modocument.doccreation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A full replacement section list, saved as a new blueprint version.
 * {@code baseVersion} is the version the editor started from; when someone
 * else saved in between, the save is refused (409) instead of overwriting.
 */
public class SaveSectionsRequest {

    @NotNull
    private Integer baseVersion;

    @Size(max = 500)
    private String note;

    @NotNull
    @Size(min = 1, max = 500)
    @Valid
    private List<SectionInput> sections;

    public Integer getBaseVersion() {
        return baseVersion;
    }

    public void setBaseVersion(Integer baseVersion) {
        this.baseVersion = baseVersion;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public List<SectionInput> getSections() {
        return sections;
    }

    public void setSections(List<SectionInput> sections) {
        this.sections = sections;
    }

    public static class SectionInput {

        @NotNull
        @Size(max = 500)
        private String title;

        private Integer headingLevel;

        @NotNull
        private Integer anchorParagraphIndex;

        @Size(max = 100)
        private List<String> keywords;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Integer getHeadingLevel() {
            return headingLevel;
        }

        public void setHeadingLevel(Integer headingLevel) {
            this.headingLevel = headingLevel;
        }

        public Integer getAnchorParagraphIndex() {
            return anchorParagraphIndex;
        }

        public void setAnchorParagraphIndex(Integer anchorParagraphIndex) {
            this.anchorParagraphIndex = anchorParagraphIndex;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }
    }
}
