package com.modocument.doccreation.pdftemplate;

import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.AiPurpose;
import com.modocument.application.port.PromptEnvelope;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.infrastructure.ai.AiOutputRejectedException;
import com.modocument.infrastructure.ratelimit.LlmRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The AI step with a stubbed gateway: valid replies improve the result, anything else leaves the code result. */
class AiRegionAdvisorTest {

    private AiFilesService ai;
    private DefaultPdfTemplateEngine engine;
    private final List<PromptEnvelope> prompts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ai = mock(AiFilesService.class);
        when(ai.isConfigured()).thenReturn(true);
        AiRegionAdvisor advisor = new AiRegionAdvisor(ai, new LlmRateLimiter(1000, 3_600_000, 100), () -> 7,
                new PiiTokenizer(new PrivacyProperties()));
        engine = new DefaultPdfTemplateEngine(CorpusSupport.ocr(), advisor);
    }

    private static final AnalysisOptions AI = new AnalysisOptions(true, "en");

    @SuppressWarnings("unchecked")
    private void reply(Function<PromptEnvelope, Object> regions, Function<PromptEnvelope, Object> data) {
        when(ai.sendForObject(any(PromptEnvelope.class), any(ParameterizedTypeReference.class))).thenAnswer(inv -> {
            PromptEnvelope env = inv.getArgument(0);
            prompts.add(env);
            return env.purpose() == AiPurpose.DOC_PDF_REGIONS ? regions.apply(env) : data.apply(env);
        });
    }

    /** The id of the candidate (or run) line whose quoted text matches. */
    private static String idOf(String prompt, String section, String quoted) {
        String part = prompt.substring(prompt.indexOf(section));
        Matcher m = Pattern.compile("(?m)^(\\w+) p\\d.*\"" + Pattern.quote(quoted) + "\"$").matcher(part);
        if (!m.find()) {
            throw new AssertionError("no line \"" + quoted + "\" in " + part);
        }
        return m.group(1);
    }

    @Test
    void validReplyImprovesLabelsGroupsAndKinds() {
        reply(env -> {
            String p = env.text();
            String id = idOf(p, "CANDIDATES", "Full name");
            String dept = idOf(p, "CANDIDATES", "Department");
            return new AiRegionAdvisor.RegionsReply(List.of(
                    new AiRegionAdvisor.RegionHint(id, "Employee full name", "Employee", "TEXT", null),
                    new AiRegionAdvisor.RegionHint(dept, "Department", "Employment", "MULTILINE", null)));
        }, env -> new AiRegionAdvisor.DataReply(List.of(), List.of(), List.of()));
        AnalysisResult r = engine.analyze(PdfCorpus.flatForm().blank(), UploadFlow.BLANK, AI);
        assertThat(r.detectedBy()).isEqualTo("CODE+AI");
        TemplateRegion name = r.regions().stream().filter(x -> x.label().equals("Employee full name")).findFirst()
                .orElseThrow();
        assertThat(name.group()).isEqualTo("Employee");
        assertThat(name.key()).isEqualTo("employee_full_name");
        TemplateRegion dept = r.regions().stream().filter(x -> x.key().equals("department")).findFirst().orElseThrow();
        assertThat(dept.kind()).isEqualTo(RegionKind.MULTILINE);
        assertThat(prompts).extracting(PromptEnvelope::purpose).containsExactly(AiPurpose.DOC_PDF_REGIONS);
        assertThat(prompts.get(0).userId()).isEqualTo(7);
    }

    @Test
    void replyWithAnUnknownIdIsRejectedWhole() {
        reply(env -> {
            String id = idOf(env.text(), "CANDIDATES", "Full name");
            return new AiRegionAdvisor.RegionsReply(List.of(
                    new AiRegionAdvisor.RegionHint(id, "Employee full name", null, null, null),
                    new AiRegionAdvisor.RegionHint("c9999", "Invented", null, "TEXT", null)));
        }, env -> null);
        AnalysisResult code = CorpusSupport.engine().analyze(PdfCorpus.flatForm().blank(), UploadFlow.BLANK,
                CorpusSupport.codeOnly());
        AnalysisResult r = engine.analyze(PdfCorpus.flatForm().blank(), UploadFlow.BLANK, AI);
        assertThat(r.detectedBy()).isEqualTo("CODE");
        assertThat(r.regions()).isEqualTo(code.regions());
        assertThat(r.warnings()).anyMatch(w -> w.contains("not usable"));
    }

    @Test
    void replyWithABadKindOrAMaskedLabelIsRejected() {
        reply(env -> new AiRegionAdvisor.RegionsReply(List.of(
                new AiRegionAdvisor.RegionHint(idOf(env.text(), "CANDIDATES", "Full name"), "Name [EMAIL]", null,
                        "TEXT", null))), env -> null);
        assertThat(engine.analyze(PdfCorpus.flatForm().blank(), UploadFlow.BLANK, AI).detectedBy()).isEqualTo("CODE");
        reply(env -> new AiRegionAdvisor.RegionsReply(List.of(
                new AiRegionAdvisor.RegionHint(idOf(env.text(), "CANDIDATES", "Full name"), "Name", null,
                        "PARAGRAPH", null))), env -> null);
        assertThat(engine.analyze(PdfCorpus.flatForm().blank(), UploadFlow.BLANK, AI).detectedBy()).isEqualTo("CODE");
    }

    @Test
    void gatewayFailureLeavesTheCodeResult() {
        when(ai.sendForObject(any(PromptEnvelope.class), any(ParameterizedTypeReference.class)))
                .thenThrow(new AiOutputRejectedException("blocked"));
        AnalysisResult code = CorpusSupport.engine().analyze(PdfCorpus.letterhead().filled(), UploadFlow.WITH_DATA,
                CorpusSupport.codeOnly());
        AnalysisResult r = engine.analyze(PdfCorpus.letterhead().filled(), UploadFlow.WITH_DATA, AI);
        assertThat(r.detectedBy()).isEqualTo("CODE");
        assertThat(r.regions()).isEqualTo(code.regions());
        assertThat(r.removals()).isEqualTo(code.removals());
    }

    @Test
    void aiIsNeverCalledWhenNotAllowedOrNotConfigured() {
        engine.analyze(PdfCorpus.flatForm().blank(), UploadFlow.BLANK, CorpusSupport.codeOnly());
        when(ai.isConfigured()).thenReturn(false);
        engine.analyze(PdfCorpus.flatForm().blank(), UploadFlow.BLANK, AI);
        verify(ai, never()).sendForObject(any(), any());
    }

    @Test
    void aiCanLiftAReviewItemAndAddRealRunsButNeverHeadings() {
        // the letterhead contact line is proposed for review only (0.45); the AI agrees it is data
        reply(env -> new AiRegionAdvisor.RegionsReply(List.of()), env -> {
            String tel = idStartingWith(env.text(), "Tel: ");
            return new AiRegionAdvisor.DataReply(List.of(tel), List.of(), List.of());
        });
        AnalysisResult r = engine.analyze(PdfCorpus.letterhead().filled(), UploadFlow.WITH_DATA, AI);
        assertThat(r.detectedBy()).isEqualTo("CODE+AI");
        assertThat(r.removals()).anyMatch(e -> e.removedText().contains("+44") && e.confidence() >= 0.6);
        // the masked listing never carries the raw phone number or e-mail address
        assertThat(prompts.get(0).text()).doesNotContain("+44 113 496 0000").doesNotContain("info@acme.example");

        // pointing at the title (a heading) rejects the whole reply
        prompts.clear();
        reply(env -> new AiRegionAdvisor.RegionsReply(List.of()), env -> new AiRegionAdvisor.DataReply(
                List.of(idOf(env.text(), "RUNS", "ACME Consulting Ltd")), List.of(), List.of()));
        AnalysisResult rejected = engine.analyze(PdfCorpus.letterhead().filled(), UploadFlow.WITH_DATA, AI);
        AnalysisResult code = CorpusSupport.engine().analyze(PdfCorpus.letterhead().filled(), UploadFlow.WITH_DATA,
                CorpusSupport.codeOnly());
        assertThat(rejected.removals()).isEqualTo(code.removals());
    }

    @Test
    void aiAddsAMissedValueWithGeometryFromTheRealGlyphs() {
        PdfCorpus.Doc doc = PdfCorpus.twoColumn();
        reply(env -> new AiRegionAdvisor.RegionsReply(List.of()), env -> {
            String p = env.text();
            // a narrative line is NOT data, but the AI may point at it; it can only use the real run
            String run = idOf(p, "RUNS", "No change request was raised.");
            String label = idOf(p, "RUNS", "Budget");
            return new AiRegionAdvisor.DataReply(List.of(run), List.of(),
                    List.of(new AiRegionAdvisor.LabelHint(run, label)));
        });
        AnalysisResult r = engine.analyze(doc.filled(), UploadFlow.WITH_DATA, AI);
        TemplateEdit added = r.removals().stream().filter(e -> "No change request was raised.".equals(e.removedText()))
                .findFirst().orElseThrow();
        assertThat(added.confidence()).isEqualTo(0.6);
        assertThat(r.regions()).anyMatch(x -> x.source() == RegionSource.AI && x.label().equals("Budget"));
        TemplateRegion region = r.regions().stream().filter(x -> x.source() == RegionSource.AI).findFirst().orElseThrow();
        // y of the run's baseline 623 (662 - 3 * 13) within the box
        assertThat(region.box().y()).isLessThan(623f);
        assertThat(region.box().y() + region.box().h()).isGreaterThan(623f);
    }

    private static String idStartingWith(String prompt, String prefix) {
        Matcher m = Pattern.compile("(?m)^(\\w+) p\\d[^\"]*\"" + Pattern.quote(prefix)).matcher(prompt);
        if (!m.find()) {
            throw new AssertionError("no run starting with " + prefix);
        }
        return m.group(1);
    }
}
