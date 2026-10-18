package fr.adrien.cfcsearch.stats;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A tiny .xlsx writer (Office Open XML written by hand, no library): sheets with inline strings, numbers,
 * a few styles, frozen header, auto-filter, and bar / line charts on a sheet.
 */
public final class XlsxWriter {

    /** Cell styles defined in {@link #styles()}. */
    public static final int STYLE_DEFAULT = 0, STYLE_HEADER = 1, STYLE_PERCENT = 2, STYLE_DECIMAL = 3, STYLE_WRAP = 4;

    public record Cell(Object value, int style) {}

    public static Cell text(String s) { return new Cell(s, STYLE_DEFAULT); }
    public static Cell num(double d) { return new Cell(d, STYLE_DEFAULT); }
    public static Cell percent(double d) { return new Cell(d, STYLE_PERCENT); }
    public static Cell decimal(double d) { return new Cell(d, STYLE_DECIMAL); }
    public static Cell wrap(String s) { return new Cell(s, STYLE_WRAP); }

    /** A chart drawn on a sheet. Ranges are 1-based, inclusive, on {@code dataSheet}. */
    public record Chart(boolean line, String title, String dataSheet, int categoryCol, int firstRow, int lastRow,
                        List<Integer> valueCols, int fromCol, int fromRow, int toCol, int toRow, List<String> pointColors) {
        public Chart(boolean line, String title, String dataSheet, int categoryCol, int firstRow, int lastRow,
                     List<Integer> valueCols, int fromCol, int fromRow, int toCol, int toRow) {
            this(line, title, dataSheet, categoryCol, firstRow, lastRow, valueCols, fromCol, fromRow, toCol, toRow, List.of());
        }
    }

    public static final class Sheet {
        final String name;
        final List<List<Cell>> rows = new ArrayList<>();
        final List<Integer> widths = new ArrayList<>();
        final List<Chart> charts = new ArrayList<>();
        final List<String[]> help = new ArrayList<>();   // {cell ref, title, text}
        boolean filter = true;

        Sheet(String name) {
            this.name = name.length() > 31 ? name.substring(0, 31) : name;
        }

        public Sheet widths(int... w) {
            for (int x : w) widths.add(x);
            return this;
        }

        public Sheet header(String... names) {
            List<Cell> row = new ArrayList<>();
            for (String n : names) row.add(new Cell(n, STYLE_HEADER));
            rows.add(row);
            return this;
        }

        public Sheet row(Cell... cells) {
            rows.add(List.of(cells));
            return this;
        }

        /** Explanations shown by Excel when a header cell is selected (one per column, "" to skip). */
        public Sheet headerHelp(String... texts) {
            if (rows.isEmpty()) return this;
            List<Cell> header = rows.getFirst();
            for (int i = 0; i < texts.length && i < header.size(); i++)
                if (texts[i] != null && !texts[i].isBlank())
                    help.add(new String[]{col(i + 1) + "1", String.valueOf(header.get(i).value()), texts[i]});
            return this;
        }

        /** Mixed layouts (title rows, blocks) shouldn't get an auto-filter over the whole sheet. */
        public Sheet noFilter() {
            filter = false;
            return this;
        }

        public Sheet chart(Chart chart) {
            charts.add(chart);
            return this;
        }

        public String name() {
            return name;
        }

        public int rowCount() {
            return rows.size();
        }
    }

    private final List<Sheet> sheets = new ArrayList<>();

    public Sheet sheet(String name) {
        Sheet s = new Sheet(name);
        sheets.add(s);
        return s;
    }

    // ── Writing ───────────────────────────────────────────────────────────

    public void write(Path file) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            int chartCount = 0;
            List<Integer> drawingOf = new ArrayList<>();   // drawing number per sheet, 0 = none
            for (Sheet s : sheets) {
                if (s.charts.isEmpty()) drawingOf.add(0);
                else drawingOf.add(drawingOf.stream().mapToInt(Integer::intValue).max().orElse(0) + 1);
            }

            StringBuilder types = new StringBuilder(XML_HEAD).append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                    .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                    .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                    .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                    .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
            StringBuilder workbook = new StringBuilder(XML_HEAD)
                    .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder workbookRels = new StringBuilder(XML_HEAD).append(RELS_OPEN);

            for (int i = 0; i < sheets.size(); i++) {
                Sheet s = sheets.get(i);
                int n = i + 1;
                types.append("<Override PartName=\"/xl/worksheets/sheet").append(n)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                workbook.append("<sheet name=\"").append(esc(s.name)).append("\" sheetId=\"").append(n)
                        .append("\" r:id=\"rId").append(n).append("\"/>");
                workbookRels.append(rel("rId" + n, "worksheet", "worksheets/sheet" + n + ".xml"));
                put(zip, "xl/worksheets/sheet" + n + ".xml", sheetXml(s, drawingOf.get(i) > 0));

                int drawing = drawingOf.get(i);
                if (drawing == 0) continue;
                types.append("<Override PartName=\"/xl/drawings/drawing").append(drawing)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
                put(zip, "xl/worksheets/_rels/sheet" + n + ".xml.rels",
                        XML_HEAD + RELS_OPEN + rel("rId1", "drawing", "../drawings/drawing" + drawing + ".xml") + "</Relationships>");
                StringBuilder drawingRels = new StringBuilder(XML_HEAD).append(RELS_OPEN);
                StringBuilder anchors = new StringBuilder();
                for (int c = 0; c < s.charts.size(); c++) {
                    chartCount++;
                    Chart chart = s.charts.get(c);
                    types.append("<Override PartName=\"/xl/charts/chart").append(chartCount)
                            .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>");
                    drawingRels.append(rel("rId" + (c + 1), "chart", "../charts/chart" + chartCount + ".xml"));
                    anchors.append(anchorXml(chart, c + 1));
                    put(zip, "xl/charts/chart" + chartCount + ".xml", chartXml(chart));
                }
                put(zip, "xl/drawings/_rels/drawing" + drawing + ".xml.rels", drawingRels.append("</Relationships>").toString());
                put(zip, "xl/drawings/drawing" + drawing + ".xml", XML_HEAD
                        + "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\""
                        + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                        + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                        + " xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">" + anchors + "</xdr:wsDr>");
            }
            workbookRels.append(rel("rId" + (sheets.size() + 1), "styles", "styles.xml")).append("</Relationships>");

            put(zip, "[Content_Types].xml", types.append("</Types>").toString());
            put(zip, "_rels/.rels", XML_HEAD + RELS_OPEN + rel("rId1", "officeDocument", "xl/workbook.xml") + "</Relationships>");
            put(zip, "xl/workbook.xml", workbook.append("</sheets></workbook>").toString());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels.toString());
            put(zip, "xl/styles.xml", styles());
        }
    }

    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String RELS_OPEN = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">";

    private static String rel(String id, String type, String target) {
        String base = type.equals("officeDocument")
                ? "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                : "http://schemas.openxmlformats.org/officeDocument/2006/relationships/" + type;
        return "<Relationship Id=\"" + id + "\" Type=\"" + base + "\" Target=\"" + target + "\"/>";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sheetXml(Sheet s, boolean hasDrawing) {
        StringBuilder sb = new StringBuilder(XML_HEAD)
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        if (!s.widths.isEmpty()) {
            sb.append("<cols>");
            for (int i = 0; i < s.widths.size(); i++)
                sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                        .append(s.widths.get(i)).append("\" customWidth=\"1\"/>");
            sb.append("</cols>");
        }
        sb.append("<sheetData>");
        int maxCols = 0;
        for (int r = 0; r < s.rows.size(); r++) {
            List<Cell> row = s.rows.get(r);
            maxCols = Math.max(maxCols, row.size());
            sb.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < row.size(); c++) {
                Cell cell = row.get(c);
                if (cell == null || cell.value() == null) continue;
                String ref = col(c + 1) + (r + 1);
                if (cell.value() instanceof Number number) {
                    double d = number.doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                    sb.append("<c r=\"").append(ref).append("\" s=\"").append(cell.style()).append("\"><v>")
                            .append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d))
                            .append("</v></c>");
                } else {
                    sb.append("<c r=\"").append(ref).append("\" s=\"").append(cell.style()).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(esc(String.valueOf(cell.value()))).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        if (s.filter && s.rows.size() > 1 && maxCols > 0)
            sb.append("<autoFilter ref=\"A1:").append(col(maxCols)).append(s.rows.size()).append("\"/>");
        if (!s.help.isEmpty()) {
            sb.append("<dataValidations count=\"").append(s.help.size()).append("\">");
            for (String[] h : s.help)
                sb.append("<dataValidation allowBlank=\"1\" showInputMessage=\"1\" promptTitle=\"")
                        .append(esc(cut(h[1], 32))).append("\" prompt=\"").append(esc(cut(h[2], 255)))
                        .append("\" sqref=\"").append(h[0]).append("\"/>");
            sb.append("</dataValidations>");
        }
        if (hasDrawing) sb.append("<drawing r:id=\"rId1\"/>");
        return sb.append("</worksheet>").toString();
    }

    private static String anchorXml(Chart chart, int relId) {
        return "<xdr:twoCellAnchor>"
                + "<xdr:from><xdr:col>" + chart.fromCol() + "</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>" + chart.fromRow()
                + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>"
                + "<xdr:to><xdr:col>" + chart.toCol() + "</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>" + chart.toRow()
                + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>"
                + "<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"" + (relId + 1) + "\" name=\"Chart " + relId
                + "\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>"
                + "<xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart r:id=\"rId"
                + relId + "\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>";
    }

    private String chartXml(Chart chart) {
        Sheet data = sheets.stream().filter(s -> s.name.equals(chart.dataSheet())).findFirst().orElseThrow();
        String sheetRef = "'" + chart.dataSheet().replace("'", "''") + "'!";
        String cats = sheetRef + "$" + col(chart.categoryCol()) + "$" + chart.firstRow() + ":$" + col(chart.categoryCol()) + "$" + chart.lastRow();
        StringBuilder series = new StringBuilder();
        for (int i = 0; i < chart.valueCols().size(); i++) {
            int vc = chart.valueCols().get(i);
            String name = data.rows.isEmpty() || data.rows.getFirst().size() < vc ? "" : String.valueOf(data.rows.getFirst().get(vc - 1).value());
            series.append("<c:ser><c:idx val=\"").append(i).append("\"/><c:order val=\"").append(i).append("\"/>")
                    .append("<c:tx><c:v>").append(esc(name)).append("</c:v></c:tx>");
            if (chart.line()) series.append("<c:marker><c:symbol val=\"none\"/></c:marker>");
            else {
                series.append("<c:invertIfNegative val=\"0\"/>");
                for (int p = 0; p < chart.pointColors().size(); p++)
                    series.append("<c:dPt><c:idx val=\"").append(p).append("\"/><c:invertIfNegative val=\"0\"/><c:bubble3D val=\"0\"/>")
                            .append("<c:spPr><a:solidFill><a:srgbClr val=\"").append(chart.pointColors().get(p))
                            .append("\"/></a:solidFill></c:spPr></c:dPt>");
            }
            series.append(chart.line() ? "<c:cat><c:numRef><c:f>" : "<c:cat><c:strRef><c:f>").append(esc(cats))
                    .append(chart.line() ? "</c:f></c:numRef></c:cat>" : "</c:f></c:strRef></c:cat>")
                    .append("<c:val><c:numRef><c:f>").append(esc(sheetRef + "$" + col(vc) + "$" + chart.firstRow() + ":$" + col(vc) + "$" + chart.lastRow()))
                    .append("</c:f></c:numRef></c:val>");
            if (chart.line()) series.append("<c:smooth val=\"0\"/>");
            series.append("</c:ser>");
        }
        String plot = chart.line()
                ? "<c:lineChart><c:grouping val=\"standard\"/><c:varyColors val=\"0\"/>" + series
                  + "<c:marker val=\"1\"/><c:axId val=\"1\"/><c:axId val=\"2\"/></c:lineChart>"
                : "<c:barChart><c:barDir val=\"bar\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>" + series
                  + "<c:gapWidth val=\"60\"/><c:axId val=\"1\"/><c:axId val=\"2\"/></c:barChart>";
        String catAx = "<c:catAx><c:axId val=\"1\"/><c:scaling><c:orientation val=\"" + (chart.line() ? "minMax" : "maxMin")
                + "\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"" + (chart.line() ? "b" : "l")
                + "\"/><c:numFmt formatCode=\"General\" sourceLinked=\"1\"/><c:tickLblPos val=\"low\"/><c:crossAx val=\"2\"/><c:crosses val=\"autoZero\"/></c:catAx>";
        String valAx = "<c:valAx><c:axId val=\"2\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\""
                + (chart.line() ? "l" : "b") + "\"/><c:majorGridlines/><c:numFmt formatCode=\"General\" sourceLinked=\"1\"/>"
                + "<c:crossAx val=\"1\"/><c:crosses val=\"" + (chart.line() ? "autoZero" : "max") + "\"/></c:valAx>";
        String legend = chart.valueCols().size() > 1
                ? "<c:legend><c:legendPos val=\"b\"/><c:overlay val=\"0\"/></c:legend>" : "";
        return XML_HEAD + "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>" + esc(chart.title())
                + "</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title><c:autoTitleDeleted val=\"0\"/>"
                + "<c:plotArea><c:layout/>" + plot + catAx + valAx + "</c:plotArea>" + legend
                + "<c:plotVisOnly val=\"1\"/></c:chart></c:chartSpace>";
    }

    private static String styles() {
        return XML_HEAD + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.0\"/></numFmts>"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9E1F2\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"5\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment vertical=\"top\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment wrapText=\"1\" vertical=\"top\"/></xf>"
                + "<xf numFmtId=\"9\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\"/></xf>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment wrapText=\"1\" vertical=\"top\"/></xf>"
                + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 1 → A, 27 → AA. */
    static String col(int n) {
        StringBuilder sb = new StringBuilder();
        while (n > 0) {
            int r = (n - 1) % 26;
            sb.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
}
