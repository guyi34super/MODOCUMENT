package com.modocument.application.privacy;

import com.modocument.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed masking matrix (docs/security/masking-matrix.md) IS the
 * code's matrix: the table between the GENERATED MATRIX markers must equal
 * {@link MaskingPolicy#renderCanonicalMatrix()} byte-for-byte, so any change
 * to the policy is a visible, reviewable diff of the document — and any edit
 * to the document that the code does not back fails the build. Same pinning
 * pattern as the role-permission matrix CSV.
 */
class MaskingMatrixDocTest {

    private static final Path DOC = Path.of("docs", "security", "masking-matrix.md");
    private static final String BEGIN = "<!-- BEGIN GENERATED MATRIX -->\n";
    private static final String END = "<!-- END GENERATED MATRIX -->";

    @Test
    void committedMatrixTableMatchesTheCodeExactly() throws IOException {
        assertThat(DOC)
                .as("docs/security/masking-matrix.md must exist (its table is generated "
                        + "from MaskingPolicy.renderCanonicalMatrix; see this test's javadoc)")
                .exists();
        String committed = new String(Files.readAllBytes(DOC), StandardCharsets.UTF_8);

        int begin = committed.indexOf(BEGIN);
        int end = committed.indexOf(END);
        assertThat(begin).as("BEGIN GENERATED MATRIX marker present").isGreaterThanOrEqualTo(0);
        assertThat(end).as("END GENERATED MATRIX marker present").isGreaterThan(begin);

        assertThat(committed.substring(begin + BEGIN.length(), end))
                .as("the masking-matrix doc has drifted from MaskingPolicy. Expected table:%n%s",
                        MaskingPolicy.renderCanonicalMatrix())
                .isEqualTo(MaskingPolicy.renderCanonicalMatrix());
    }

    @Test
    void policySemanticsHold() {
        MaskingPolicy defaults = new MaskingPolicy(new MaskingProperties());

        for (Role role : Role.values()) {
            // The gate's wording: masked for EVERY role in screens (read-only),
            // reports and exports.
            assertThat(defaults.mask(MaskingPolicy.Channel.EXPORT, role)).isTrue();
            assertThat(defaults.mask(MaskingPolicy.Channel.REPORT, role)).isTrue();
            assertThat(defaults.mask(MaskingPolicy.Channel.SCREEN_READONLY, role)).isTrue();
            // The documented exceptions.
            assertThat(defaults.mask(MaskingPolicy.Channel.SCREEN_WORKING, role)).isFalse();
            assertThat(defaults.mask(MaskingPolicy.Channel.DSR, role)).isFalse();
        }

        // modocument.privacy.masking.screen-working=true force-masks the wizard.
        MaskingProperties forced = new MaskingProperties();
        forced.setScreenWorking(true);
        assertThat(new MaskingPolicy(forced)
                .mask(MaskingPolicy.Channel.SCREEN_WORKING, Role.READ_WRITE)).isTrue();

        // Channel selection: READ_ONLY (or an unknown role — fail closed)
        // lands on the masked read-only screen; writing roles keep working.
        assertThat(defaults.screenChannel(Role.READ_ONLY))
                .isEqualTo(MaskingPolicy.Channel.SCREEN_READONLY);
        assertThat(defaults.screenChannel(null))
                .isEqualTo(MaskingPolicy.Channel.SCREEN_READONLY);
        assertThat(defaults.screenChannel(Role.READ_WRITE))
                .isEqualTo(MaskingPolicy.Channel.SCREEN_WORKING);
        assertThat(defaults.screenChannel(Role.ADMIN))
                .isEqualTo(MaskingPolicy.Channel.SCREEN_WORKING);
    }
}
