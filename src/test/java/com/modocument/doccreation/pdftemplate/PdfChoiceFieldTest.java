package com.modocument.doccreation.pdftemplate;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ws33 (field-validation-contract §2): AcroForm dropdowns and list boxes
 * become CHOICE regions with their options; a CHOICE value that is one of
 * the field's options fills the field, any other value is drawn as
 * single-line text over the cleared field; a CHOICE region without a form
 * field is drawn like TEXT.
 */
class PdfChoiceFieldTest {

    private static final FillOptions VERIFY = new FillOptions(6f, true, true);
    private final DefaultPdfTemplateEngine engine = CorpusSupport.engine();

    /** A form with a dropdown (display texts differ from export values) and a list box. */
    private static byte[] choiceForm() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDFont helv = PdfCorpus.helv();
            PDAcroForm form = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(form);
            PDResources dr = new PDResources();
            dr.put(COSName.getPDFName("Helv"), helv);
            form.setDefaultResources(dr);
            form.setDefaultAppearance("/Helv 0 Tf 0 g");
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                PdfCorpus.text(cs, PdfCorpus.helvBold(), 16, 72, 780, "Customer form");
                PdfCorpus.text(cs, helv, 10, 72, 724, "Marital status:");
                PdfCorpus.text(cs, helv, 10, 72, 664, "Favourite colour:");
            }
            PDComboBox marital = new PDComboBox(form);
            marital.setPartialName("marital");
            marital.setDefaultAppearance("/Helv 10 Tf 0 g");
            marital.setOptions(List.of("S", "M", "D"), List.of("Single", "Married", "Divorced"));
            place(marital, page, 160, 718, 200, 16);
            form.getFields().add(marital);

            PDListBox colour = new PDListBox(form);
            colour.setPartialName("colour");
            colour.setDefaultAppearance("/Helv 10 Tf 0 g");
            colour.setOptions(List.of("Red", "Green", "Blue", " ", "Red"));
            place(colour, page, 160, 640, 200, 40);
            form.getFields().add(colour);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static void place(PDChoice field, PDPage page, float x, float y, float w, float h)
            throws IOException {
        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(x, y, w, h));
        widget.setPage(page);
        widget.setPrinted(true);
        page.getAnnotations().add(widget);
    }

    private static TemplateRegion byAcroName(AnalysisResult a, String name) {
        return a.regions().stream().filter(r -> name.equals(r.acroFieldName())).findFirst()
                .orElseThrow(() -> new AssertionError("no region for " + name + " in " + a.regions()));
    }

    @Test
    void comboAndListBoxesBecomeChoiceRegionsWithTheirOptions() throws IOException {
        byte[] pdf = choiceForm();
        AnalysisResult a = engine.analyze(pdf, UploadFlow.BLANK, CorpusSupport.codeOnly());

        TemplateRegion marital = byAcroName(a, "marital");
        assertThat(marital.kind()).isEqualTo(RegionKind.CHOICE);
        assertThat(marital.maxLines()).isEqualTo(1);
        assertThat(marital.source()).isEqualTo(RegionSource.ACROFORM);
        assertThat(byAcroName(a, "colour").kind()).isEqualTo(RegionKind.CHOICE);

        Map<String, List<String>> options = AcroChoiceOptions.read(pdf);
        assertThat(options.get("marital")).as("the display texts").containsExactly("Single", "Married", "Divorced");
        assertThat(options.get("colour")).as("blank and duplicate entries dropped")
                .containsExactly("Red", "Green", "Blue");
        assertThat(AcroChoiceOptions.read("not a pdf".getBytes())).isEmpty();
    }

    @Test
    void anOptionFillsTheFieldAndAnythingElseIsDrawnAsText() throws IOException {
        byte[] pdf = choiceForm();
        AnalysisResult a = engine.analyze(pdf, UploadFlow.BLANK, CorpusSupport.codeOnly());
        TemplateRegion marital = byAcroName(a, "marital");
        TemplateRegion colour = byAcroName(a, "colour");

        FillResult byOption = engine.fill(pdf, a.regions(),
                Map.of(marital.key(), "Married", colour.key(), "Green"), Map.of(), VERIFY);
        assertThat(byOption.report().exact()).as("%d", byOption.report().differingPixels()).isTrue();
        try (PDDocument d = Loader.loadPDF(byOption.pdf())) {
            PDAcroForm form = d.getDocumentCatalog().getAcroForm();
            assertThat(form == null || form.getFields().isEmpty()).as("flattened").isTrue();
        }
        assertThat(CorpusSupport.text(byOption.pdf())).contains("Married");

        FillResult drawn = engine.fill(pdf, a.regions(), Map.of(marital.key(), "Separated"), Map.of(), VERIFY);
        assertThat(drawn.report().exact()).as("%d", drawn.report().differingPixels()).isTrue();
        String text = CorpusSupport.text(drawn.pdf());
        assertThat(text).contains("Separated");
        assertThat(text).doesNotContain("Single").doesNotContain("Divorced");
    }

    @Test
    void exportValuesAndDisplayTextsBothCountAsOptions() throws IOException {
        try (PDDocument doc = Loader.loadPDF(choiceForm())) {
            PDChoice marital = (PDChoice) doc.getDocumentCatalog().getAcroForm().getField("marital");
            assertThat(PdfTemplateFiller.choiceOption(marital, "M")).isEqualTo("M");
            assertThat(PdfTemplateFiller.choiceOption(marital, " Married ")).isEqualTo("M");
            assertThat(PdfTemplateFiller.choiceOption(marital, "married")).as("exact").isNull();
            assertThat(PdfTemplateFiller.choiceOption(marital, "Separated")).isNull();
        }
    }

    @Test
    void aChoiceRegionWithoutAFormFieldIsDrawnLikeText() {
        byte[] blank = PdfCorpus.flatForm().blank();
        TemplateRegion choice = new TemplateRegion("plan", "Plan", null, RegionKind.CHOICE,
                new PdfBox(0, 300, 300, 200, 20), "LiberationSans", 0f, "#000000", Align.LEFT, 1, null, false, 0,
                RegionSource.MANUAL, 1.0, null);
        FillResult r = engine.fill(blank, List.of(choice), Map.of("plan", "Gold plan"), Map.of(), VERIFY);
        assertThat(CorpusSupport.text(r.pdf())).contains("Gold plan");
        assertThat(r.report().exact()).isTrue();
        assertThat(r.addedPages()).isZero();
    }
}
