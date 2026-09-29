package io.github.akhil1527.sponsorscout;

import com.github.pjfanning.xlsx.StreamingReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;

/**
 * Loads DOL LCA disclosure files (xlsx, from dol.gov/agencies/eta/foreign-labor/performance) into SQLite.
 * Keeps certified H-1B cases only, and only the columns the tools use. No contact names or emails.
 */
final class LcaIngest {

    private LcaIngest() {
    }

    static void run(String dbUrl, List<Path> files) throws Exception {
        try (Connection c = DriverManager.getConnection(dbUrl)) {
            String schema = new String(LcaIngest.class.getResourceAsStream("/schema.sql").readAllBytes(),
                    StandardCharsets.UTF_8);
            try (Statement st = c.createStatement()) {
                for (String sql : schema.split(";")) {
                    if (!sql.isBlank()) {
                        st.execute(sql);
                    }
                }
            }
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO lca VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (Path f : files) {
                    load(f, ps);
                    c.commit();
                }
            }
        }
    }

    private static void load(Path file, PreparedStatement ps) throws Exception {
        long rows = 0;
        long kept = 0;
        try (InputStream in = Files.newInputStream(file);
                Workbook wb = StreamingReader.builder().rowCacheSize(500).bufferSize(1 << 16).open(in)) {
            Map<String, Integer> col = null;
            for (Row r : wb.getSheetAt(0)) {
                if (col == null) {
                    col = new HashMap<>();
                    for (Cell h : r) {
                        col.put(h.getStringCellValue().trim(), h.getColumnIndex());
                    }
                    continue;
                }
                rows++;
                if (!"Certified".equalsIgnoreCase(text(r, col, "CASE_STATUS"))
                        || !"H-1B".equals(text(r, col, "VISA_CLASS"))) {
                    continue;
                }
                String employer = text(r, col, "EMPLOYER_NAME");
                ps.setString(1, text(r, col, "CASE_NUMBER"));
                ps.setString(2, employer);
                ps.setString(3, Filters.employerKey(employer));
                ps.setString(4, text(r, col, "JOB_TITLE"));
                ps.setString(5, text(r, col, "SOC_CODE"));
                ps.setString(6, text(r, col, "SOC_TITLE"));
                ps.setString(7, text(r, col, "WORKSITE_CITY"));
                ps.setString(8, text(r, col, "WORKSITE_STATE").toUpperCase(Locale.ROOT));
                ps.setObject(9, annualWage(number(r, col, "WAGE_RATE_OF_PAY_FROM"), text(r, col, "WAGE_UNIT_OF_PAY")));
                ps.setString(10, text(r, col, "PW_WAGE_LEVEL"));
                ps.setInt(11, (int) number(r, col, "NEW_EMPLOYMENT"));
                ps.setInt(12, (int) number(r, col, "CHANGE_EMPLOYER"));
                ps.setString(13, date(r, col, "DECISION_DATE"));
                ps.addBatch();
                if (++kept % 5000 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        System.out.printf("%s: %d rows read, %d certified H-1B cases kept%n", file.getFileName(), rows, kept);
    }

    /** Yearly wage, or null when the number is missing or not believable for a full-time role. */
    static Long annualWage(double amount, String unit) {
        double perYear = switch (unit.toLowerCase(Locale.ROOT)) {
            case "hour" -> amount * 2080;
            case "week" -> amount * 52;
            case "bi-weekly" -> amount * 26;
            case "month" -> amount * 12;
            default -> amount;
        };
        return perYear >= 20_000 && perYear <= 1_000_000 ? Math.round(perYear) : null;
    }

    private static Cell cell(Row r, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null ? null : r.getCell(i);
    }

    private static String text(Row r, Map<String, Integer> col, String name) {
        Cell c = cell(r, col, name);
        if (c == null) {
            return "";
        }
        if (c.getCellType() == CellType.NUMERIC) {
            double d = c.getNumericCellValue();
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        return c.getStringCellValue().trim();
    }

    private static double number(Row r, Map<String, Integer> col, String name) {
        Cell c = cell(r, col, name);
        if (c == null) {
            return 0;
        }
        if (c.getCellType() == CellType.NUMERIC) {
            return c.getNumericCellValue();
        }
        try {
            return Double.parseDouble(c.getStringCellValue().replaceAll("[$,\\s]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String date(Row r, Map<String, Integer> col, String name) {
        Cell c = cell(r, col, name);
        if (c == null) {
            return null;
        }
        if (c.getCellType() == CellType.NUMERIC) {
            return DateUtil.getLocalDateTime(c.getNumericCellValue()).toLocalDate().toString();
        }
        String s = c.getStringCellValue().trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.contains("/") ? LocalDate.parse(s, DateTimeFormatter.ofPattern("M/d/yyyy")).toString()
                : s.substring(0, Math.min(10, s.length()));
    }
}
