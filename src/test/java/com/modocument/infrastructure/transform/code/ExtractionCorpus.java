package com.modocument.infrastructure.transform.code;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modocument.application.dto.TableExtractionHints;
import com.modocument.application.mapping.ValueShapeScorer;
import com.modocument.application.port.ValidationRuleRepository;
import com.modocument.infrastructure.file.FileTextReader;
import com.modocument.infrastructure.file.MarkdownTableMaterializer;
import com.modocument.infrastructure.file.OcrService;
import com.modocument.infrastructure.file.RowLineTableMaterializer;
import com.modocument.infrastructure.file.TableExtractionHintsBuilder;
import com.modocument.infrastructure.file.TabularNormalizer;
import com.modocument.infrastructure.file.VisionOcrService;
import com.modocument.infrastructure.rule.DateFormatRule;
import com.modocument.infrastructure.rule.RegexRule;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * The extraction golden corpus: each case is a JSON file under
 * {@code src/test/resources/extraction-corpus/expected/} (template fields,
 * expected strategy, confidence range, golden cells), and its document is
 * generated here into a temp dir with POI / PDFBox / XWPF — no binary
 * fixtures in the repository. Text is read the way the parse stage reads it
 * ({@link FileTextReader}) and hints come from {@link TableExtractionHintsBuilder}.
 */
final class ExtractionCorpus {

    static final List<String> CASES = List.of(
            "semicolon-statement-csv",
            "cp1252-customers-csv",
            "payroll-xlsx",
            "employees-table-pdf",
            "statement-fixed-width-pdf",
            "employees-docx",
            "customer-form-pdf",
            "statement-blocks-docx",
            "remittance-regex-pdf");

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** One golden case. Rows are keyed by field name in {@code fields} order. */
    record Case(String name,
                String file,
                CodeStrategy strategy,
                double minConfidence,
                double maxConfidence,
                List<FieldSpec> fields,
                Map<String, String> globalAliases,
                List<List<String>> rows) {
    }

    private ExtractionCorpus() {
    }

    static CodeTableExtractor extractor() {
        return new CodeTableExtractor(new TabularNormalizer(), new MarkdownTableMaterializer(),
                new RowLineTableMaterializer(),
                new ValueShapeScorer(mock(ValidationRuleRepository.class),
                        List.of(new RegexRule(), new DateFormatRule())));
    }

    static Case load(String name) {
        String resource = "/extraction-corpus/expected/" + name + ".json";
        try (InputStream in = ExtractionCorpus.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing golden file " + resource);
            }
            return JSON.readValue(in, Case.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The input the parse step would build for this document. */
    static ExtractionInput input(Case golden, Path file) {
        String text = readText(file);
        TableExtractionHints hints;
        try {
            hints = new TableExtractionHintsBuilder().build(file, text, "local");
        } catch (UncheckedIOException e) {
            // The pre-scan reads CSV as UTF-8 and throws on windows-1252 bytes.
            hints = null;
        }
        return ExtractionInput.builder()
                .text(text)
                .file(file)
                .fields(golden.fields())
                .hints(hints)
                .globalAliases(golden.globalAliases())
                .build();
    }

    /** Text as the parse stage reads it; empty when the reader fails (e.g. a cp1252 CSV). */
    static String readText(Path file) {
        FileTextReader reader = new FileTextReader(mock(OcrService.class), mock(VisionOcrService.class));
        try {
            return reader.readAsText(file.toString());
        } catch (UncheckedIOException e) {
            return "";
        }
    }

    /** Share of golden cells reproduced (extra or missing rows count as misses). */
    static double cellAccuracy(Case golden, CodeExtraction extraction) {
        Map<String, String> columnByField = new HashMap<>();
        extraction.suggestedMapping().forEach((column, field) -> columnByField.put(field, column));
        int rows = Math.max(golden.rows().size(), extraction.rows().size());
        int total = rows * golden.fields().size();
        int matched = 0;
        for (int r = 0; r < golden.rows().size() && r < extraction.rows().size(); r++) {
            List<String> expected = golden.rows().get(r);
            Map<String, String> actual = extraction.rows().get(r);
            for (int f = 0; f < golden.fields().size(); f++) {
                String column = columnByField.get(golden.fields().get(f).name());
                String value = column == null ? "" : actual.getOrDefault(column, "");
                if (expected.get(f).equals(value)) {
                    matched++;
                }
            }
        }
        return total == 0 ? 0 : (double) matched / total;
    }

    static Path generate(String fileName, Path dir) throws IOException {
        Path file = dir.resolve(fileName);
        switch (fileName) {
            case "semicolon-statement.csv" -> semicolonStatement(file);
            case "cp1252-customers.csv" -> cp1252Customers(file);
            case "payroll.xlsx" -> payrollWorkbook(file);
            case "employees-table.pdf" -> employeesTablePdf(file);
            case "statement-fixed-width.pdf" -> fixedWidthStatementPdf(file);
            case "employees.docx" -> employeesDocx(file);
            case "customer-form.pdf" -> customerFormPdf(file);
            case "statement-blocks.docx" -> statementBlocksDocx(file);
            case "remittance.pdf" -> remittancePdf(file);
            default -> throw new IllegalArgumentException("No generator for " + fileName);
        }
        return file;
    }

    // ---- CSV -----------------------------------------------------------------

    /** UTF-8 with BOM, ';' delimiter, decimal comma, quoted ';' and newline, title, totals, footer. */
    private static void semicolonStatement(Path file) throws IOException {
        String text = String.join("\r\n",
                "ACME Savings Bank - Account statement",
                "Account;MU-0042-7781",
                "Date;Description;Amount;Balance",
                "03/01/2024;Opening deposit;\"1.000,00\";\"1.000,00\"",
                "05/01/2024;\"Salary; January\";\"2.500,00\";\"3.500,00\"",
                "08/01/2024;\"Grocery",
                "store purchase\";-45,10;\"3.454,90\"",
                "15/01/2024;ATM withdrawal;-200,00;\"3.254,90\"",
                "22/01/2024;\"Transfer to \"\"Savings\"\"\";-500,00;\"2.754,90\"",
                "29/01/2024;Interest;12,35;\"2.767,25\"",
                "Total;;\"2.767,25\";",
                "Generated on 2024-02-01",
                "");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        bytes.write(text.getBytes(StandardCharsets.UTF_8));
        Files.write(file, bytes.toByteArray());
    }

    /** windows-1252 bytes (accented names), comma delimiter, quoted thousands, dotted dates, TOTAL row. */
    private static void cp1252Customers(Path file) throws IOException {
        String text = String.join("\n",
                "Customer Name,City,Balance,Opened",
                "Zoë Lefèvre,Curepipe,\"12,500.00\",03.04.2021",
                "José Müller,Port Louis,980.5,15.11.2019",
                "Ana Šimek,Moka,\"1,000\",01.12.2022",
                "Björn Ångström,Quatre Bornes,0.75,28.02.2020",
                "\"O'Neil, Seán\",Mahébourg,\"-3,250.40\",07.07.2023",
                "François Dupré,Rose-Hill,42.10,30.09.2018",
                "TOTAL,,\"11,022.85\",",
                "");
        Files.write(file, text.getBytes(TextDecoding.WINDOWS_1252));
    }

    // ---- XLSX ----------------------------------------------------------------

    /** Cover sheet first; data sheet with a merged title, header on row 4, dates, money, a SUM total. */
    private static void payrollWorkbook(Path file) throws IOException {
        String[][] people = {
                {"E-1001", "Aarav Ramsamy", "Finance", "2021-03-15", "45000"},
                {"E-1002", "Bella Chung", "Operations", "2019-11-04", "38500.5"},
                {"E-1003", "Chloé Desvaux", "IT", "2022-01-20", "52000"},
                {"E-1004", "Dev Patel", "Finance", "2020-06-08", "41000"},
                {"E-1005", "Emma Li", "HR", "2018-09-30", "36000"},
                {"E-1006", "Farid Joomun", "IT", "2023-02-14", "49500"},
                {"E-1007", "Grace Moreau", "Operations", "2017-12-01", "39999.99"},
                {"E-1008", "Hugo Lam", "Risk", "2021-07-19", "58000"},
                {"E-1009", "Isha Gopal", "Risk", "2016-04-25", "61000"},
                {"E-1010", "Jonah Baptiste", "Finance", "2024-01-08", "33000"},
                {"E-1011", "Kiran Dookun", "IT", "2015-10-13", "64000"},
                {"E-1012", "Lea Fok", "HR", "2020-02-29", "37000"}};
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Sheet cover = workbook.createSheet("Cover");
            cover.createRow(0).createCell(0).setCellValue("Payroll export");
            cover.createRow(1).createCell(0).setCellValue("Prepared by HR");
            cover.createRow(2).createCell(0).setCellValue("Confidential");

            Sheet sheet = workbook.createSheet("Payroll");
            CreationHelper helper = workbook.getCreationHelper();
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("dd/mm/yyyy"));
            CellStyle moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(helper.createDataFormat().getFormat("#,##0.00"));

            sheet.createRow(0).createCell(0).setCellValue("ACME Ltd - Payroll January 2024");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
            sheet.createRow(2).createCell(0).setCellValue("Generated 2024-02-01");
            Row header = sheet.createRow(3);
            String[] headers = {"Emp ID", "Employee Name", "Department", "Hire Date", "Monthly Salary"};
            for (int c = 0; c < headers.length; c++) {
                header.createCell(c).setCellValue(headers[c]);
            }
            int r = 4;
            for (String[] person : people) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(person[0]);
                row.createCell(1).setCellValue(person[1]);
                row.createCell(2).setCellValue(person[2]);
                var date = row.createCell(3);
                date.setCellValue(Date.from(LocalDate.parse(person[3]).atStartOfDay().toInstant(ZoneOffset.UTC)));
                date.setCellStyle(dateStyle);
                var salary = row.createCell(4);
                salary.setCellValue(Double.parseDouble(person[4]));
                salary.setCellStyle(moneyStyle);
            }
            Row total = sheet.createRow(r);
            total.createCell(0).setCellValue("Total");
            var sum = total.createCell(4);
            sum.setCellFormula("SUM(E5:E" + r + ")");
            sum.setCellStyle(moneyStyle);
            workbook.write(out);
        }
    }

    // ---- PDF -----------------------------------------------------------------

    /** A Helvetica table: every cell drawn at its column's x, as report generators do. */
    private static void employeesTablePdf(Path file) throws IOException {
        String[][] rows = {
                {"Name", "Date of Birth", "Department", "Salary"},
                {"Alice Smith", "15 Jan 1990", "Finance", "1,234.56"},
                {"Bob Lee", "1 Dec 1985", "IT", "999.00"},
                {"Chandra Ramgoolam", "28 Feb 1979", "Risk Management", "12,500.00"},
                {"Diane Ng", "3 Mar 1992", "Operations", "2,075.10"},
                {"Ethan Ho", "30 Jun 1988", "IT", "3,400.00"},
                {"Fatima Rawat", "9 Sep 1995", "Finance", "1,980.25"},
                {"George Li", "21 Nov 1983", "Legal", "4,150.00"},
                {"Hana Bhujun", "7 Jul 1990", "Operations", "2,210.75"},
                {"Ivan Petit", "12 Apr 1987", "Risk Management", "5,005.05"},
                {"Julie Tang", "5 May 1993", "HR", "1,875.00"}};
        float[] xs = {50, 200, 320, 470};
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                text(content, bold, 14, 50, 740, "Employee register - March 2024");
                float y = 700;
                for (int r = 0; r < rows.length; r++) {
                    for (int c = 0; c < rows[r].length; c++) {
                        text(content, r == 0 ? bold : regular, 10, xs[c], y, rows[r][c]);
                    }
                    y -= 16;
                }
                text(content, regular, 8, 480, 60, "Page 1 of 1");
            }
            document.save(file.toFile());
        }
    }

    /** A Courier bank statement over two pages: padded columns, wrapped description, repeated header. */
    private static void fixedWidthStatementPdf(Path file) throws IOException {
        String header = line("Date", "Description", "Debit", "Credit", "Balance");
        List<List<String>> pages = List.of(
                List.of(
                        "ACME BANK LTD",
                        "Statement of account",
                        "Account: 000123456789          Period: 01 Jan 2024 - 31 Jan 2024",
                        "",
                        header,
                        line("02 Jan 2024", "Opening balance", "", "", "1,000.00"),
                        line("05 Jan 2024", "Salary ACME Ltd", "", "2,500.00", "3,500.00"),
                        line("08 Jan 2024", "Grocery store", "45.10", "", "3,454.90"),
                        line("", "(card 4417)", "", "", ""),
                        line("11 Jan 2024", "ATM withdrawal", "200.00", "", "3,254.90"),
                        line("15 Jan 2024", "Electricity bill", "1,120.35", "", "2,134.55"),
                        "",
                        "                                                    Page 1 of 2"),
                List.of(
                        "ACME BANK LTD",
                        header,
                        line("19 Jan 2024", "Refund - online order", "", "64.99", "2,199.54"),
                        line("22 Jan 2024", "Transfer to savings", "500.00", "", "1,699.54"),
                        line("26 Jan 2024", "Interest", "", "3.12", "1,702.66"),
                        line("31 Jan 2024", "Monthly fee", "15.00", "", "1,687.66"),
                        "",
                        "                                                    Page 2 of 2"));
        try (PDDocument document = new PDDocument()) {
            PDType1Font courier = new PDType1Font(Standard14Fonts.FontName.COURIER);
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    float y = 740;
                    for (String text : lines) {
                        if (!text.isEmpty()) {
                            text(content, courier, 9, 40, y, text);
                        }
                        y -= 12;
                    }
                }
            }
            document.save(file.toFile());
        }
    }

    private static String line(String date, String description, String debit, String credit, String balance) {
        return String.format("%-13s%-28s%12s%12s%12s", date, description, debit, credit, balance)
                .replaceAll("\\s+$", "");
    }

    /** A key:value form with ':', '-', '=', next-line value, two labels on one line, currency. */
    private static void customerFormPdf(Path file) throws IOException {
        String[] lines = {
                "Customer onboarding form",
                "Customer Name: Jane Doe",
                "Date of Birth: 12/31/1985",
                "Account Number - 000123456",
                "Branch = Port Louis",
                "Email Address",
                "jane.doe@example.com",
                "Account Type: Savings      Currency: MUR",
                "Monthly Income:  MUR 45,000.00",
                "Signature: ____________________"};
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = 720;
                for (String text : lines) {
                    text(content, font, 11, 60, y, text);
                    y -= 22;
                }
            }
            document.save(file.toFile());
        }
    }

    /** Free text where each payment line carries the values; only per-field regexes can read it. */
    private static void remittancePdf(Path file) throws IOException {
        String[] lines = {
                "Remittance advice - ACME Ltd",
                "The following payments were received this week:",
                "Payment REF-2024-0001 of MUR 1,250.00 received on 2024-01-15 from BLUE OCEAN TRADING.",
                "Payment REF-2024-0002 of MUR 980.40 received on 2024-01-17 from KESTREL PARTNERS.",
                "Payment REF-2024-0003 of MUR 12,000.00 received on 2024-01-18 from SUNRISE FOODS.",
                "Payment REF-2024-0004 of MUR 75.25 received on 2024-01-22 from ISLAND DIVE CENTRE.",
                "Payment REF-2024-0005 of MUR 3,410.90 received on 2024-01-26 from NORTH COAST HOTELS.",
                "Please contact accounts receivable for any query."};
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float y = 720;
                for (String text : lines) {
                    text(content, font, 9, 40, y, text);
                    y -= 16;
                }
            }
            document.save(file.toFile());
        }
    }

    private static void text(PDPageContentStream content, PDType1Font font, float size, float x, float y,
                             String value) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }

    // ---- DOCX ----------------------------------------------------------------

    /** A Word table between an intro and a sign-off paragraph. */
    private static void employeesDocx(Path file) throws IOException {
        String[][] rows = {
                {"Emp No", "Name", "Start Date", "Email"},
                {"1001", "Aisha Khan", "March 5, 2022", "aisha.khan@example.com"},
                {"1002", "Ben Carter", "January 17, 2020", "ben.carter@example.com"},
                {"1003", "Clara Wong", "July 1, 2019", "clara.wong@example.com"},
                {"1004", "Daniel Roy", "October 30, 2021", "daniel.roy@example.com"},
                {"1005", "Elif Sahin", "February 2, 2023", "elif.sahin@example.com"},
                {"1006", "Femi Ade", "December 12, 2018", "femi.ade@example.com"},
                {"1007", "Gita Rao", "August 8, 2022", "gita.rao@example.com"},
                {"1008", "Hiro Sato", "May 20, 2017", "hiro.sato@example.com"}};
        try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("Staff list - Finance department");
            XWPFTable table = document.createTable(rows.length, rows[0].length);
            for (int r = 0; r < rows.length; r++) {
                XWPFTableRow row = table.getRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.getCell(c).setText(rows[r][c]);
                }
            }
            document.createParagraph().createRun().setText("Approved by: J. Doe");
            document.write(out);
        }
    }

    /** A statement as repeated label blocks with an account preamble; one label uses a TAB. */
    private static void statementBlocksDocx(Path file) throws IOException {
        List<String[]> blocks = new ArrayList<>();
        blocks.add(new String[]{"03/01/2024", "Opening deposit", "1,000.00", "REF-0001"});
        blocks.add(new String[]{"15/01/2024", "Salary - January", "2,500.00", "REF-0002"});
        blocks.add(new String[]{"18/01/2024", "Card payment, supermarket", "-84.35", "REF-0003"});
        blocks.add(new String[]{"02/02/2024", "Standing order", "-250.00", "REF-0004"});
        try (XWPFDocument document = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            paragraph(document, "Monthly transactions summary");
            paragraph(document, "Account Number: MU-0042-7781");
            paragraph(document, "Statement Period: January 2024");
            for (String[] block : blocks) {
                paragraph(document, "");
                paragraph(document, "Transaction Date: " + block[0]);
                paragraph(document, "Description: " + block[1]);
                paragraph(document, "Amount: " + block[2]);
                XWPFParagraph reference = document.createParagraph();
                XWPFRun run = reference.createRun();
                run.setText("Ref.");
                run.addTab();
                run.setText(block[3]);
            }
            document.write(out);
        }
    }

    private static void paragraph(XWPFDocument document, String text) {
        document.createParagraph().createRun().setText(text);
    }
}
