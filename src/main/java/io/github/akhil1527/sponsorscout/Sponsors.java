package io.github.akhil1527.sponsorscout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Questions answered from DOL LCA disclosure data (certified H-1B cases only). */
@Component
class Sponsors {

    record Sponsor(String employer, int filings, int newHires, int transfers, Long avgWage,
            List<String> commonTitles, List<String> topCities) {
    }

    record TitleStat(String title, int filings, Long avgWage) {
    }

    record SponsorRecord(String employer, int filings, int newHires, int transfers, Long avgWage,
            String role, int roleFilings, Long roleAvgWage, List<TitleStat> commonTitles, List<String> topCities) {
    }

    private final JdbcTemplate db;
    private volatile String coverage;

    Sponsors(JdbcTemplate db) {
        this.db = db;
    }

    /** Employers with the most certified H-1B LCAs for titles containing every word of the role. */
    List<Sponsor> top(String role, String state, int limit) {
        Where w = Where.role(role);
        String code = Filters.stateCode(state);
        if (code != null) {
            w.and("state = ?", code);
        }
        List<Sponsor> out = new ArrayList<>();
        for (Map<String, Object> r : db.queryForList("SELECT employer_key, MAX(employer) employer, COUNT(*) n, "
                + "SUM(new_hires) nh, SUM(transfers) tr, CAST(AVG(wage) AS INTEGER) w FROM lca WHERE " + w.sql
                + " GROUP BY employer_key ORDER BY n DESC LIMIT ?", w.args(limit))) {
            Where e = Where.role(role).and("employer_key = ?", r.get("employer_key"));
            if (code != null) {
                e.and("state = ?", code);
            }
            out.add(new Sponsor((String) r.get("employer"), num(r.get("n")), num(r.get("nh")), num(r.get("tr")),
                    wage(r.get("w")),
                    titles(e, 3).stream().map(TitleStat::title).toList(), cities(e, 3)));
        }
        return out;
    }

    /** One employer's filing record, or null when the data has no filings under that name. */
    SponsorRecord record(String company, String role) {
        String key = Filters.employerKey(company);
        if (key.isEmpty()) {
            return null;
        }
        Where e = new Where().and("(employer_key = ? OR employer_key LIKE ?)", key, key + " %");
        Map<String, Object> t = db.queryForMap("SELECT MAX(employer) employer, COUNT(*) n, SUM(new_hires) nh, "
                + "SUM(transfers) tr, CAST(AVG(wage) AS INTEGER) w FROM lca WHERE " + e.sql, e.args());
        if (num(t.get("n")) == 0) {
            return null;
        }
        int roleFilings = 0;
        Long roleWage = null;
        List<TitleStat> titles = List.of();
        if (role != null && !role.isBlank()) {
            Where er = Where.role(role).and("(employer_key = ? OR employer_key LIKE ?)", key, key + " %");
            Map<String, Object> r = db.queryForMap("SELECT COUNT(*) n, CAST(AVG(wage) AS INTEGER) w FROM lca WHERE "
                    + er.sql, er.args());
            roleFilings = num(r.get("n"));
            roleWage = wage(r.get("w"));
            titles = titles(er, 5);
        }
        if (titles.isEmpty()) {
            titles = titles(e, 5);
        }
        return new SponsorRecord((String) t.get("employer"), num(t.get("n")), num(t.get("nh")), num(t.get("tr")),
                wage(t.get("w")), role, roleFilings, roleWage, titles, cities(e, 3));
    }

    /** What the data covers, for honest answers: "12,345 certified H-1B filings decided 2025-07-01 to 2025-09-30". */
    String coverage() {
        if (coverage == null) {
            Map<String, Object> r = db.queryForMap("SELECT COUNT(*) n, MIN(decided) a, MAX(decided) b FROM lca");
            coverage = num(r.get("n")) == 0 ? null
                    : String.format(Locale.US, "%,d certified H-1B filings decided %s to %s", num(r.get("n")),
                            r.get("a"), r.get("b"));
        }
        return coverage;
    }

    private List<TitleStat> titles(Where w, int limit) {
        return db.query("SELECT MAX(job_title) t, COUNT(*) n, CAST(AVG(wage) AS INTEGER) w FROM lca WHERE " + w.sql
                + " GROUP BY lower(job_title) ORDER BY n DESC LIMIT ?",
                (rs, i) -> new TitleStat(rs.getString("t"), rs.getInt("n"), wage(rs.getObject("w"))), w.args(limit));
    }

    private List<String> cities(Where w, int limit) {
        return db.query("SELECT city || ', ' || state c FROM lca WHERE " + w.sql
                + " GROUP BY lower(city), state ORDER BY COUNT(*) DESC LIMIT ?",
                (rs, i) -> rs.getString("c"), w.args(limit));
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static Long wage(Object o) {
        return o == null ? null : Math.round(((Number) o).doubleValue() / 1000.0) * 1000;
    }

    /** SQL WHERE clause built from fixed fragments and bound parameters only. */
    private static final class Where {
        private String sql = "1 = 1";
        private final List<Object> args = new ArrayList<>();

        static Where role(String role) {
            Where w = new Where();
            if (role != null) {
                Arrays.stream(role.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.]+")).filter(s -> !s.isEmpty())
                        .forEach(word -> w.and("lower(job_title) LIKE ?", "%" + word + "%"));
            }
            return w;
        }

        Where and(String clause, Object... values) {
            sql = sql + " AND " + clause;
            args.addAll(List.of(values));
            return this;
        }

        Object[] args(Object... extra) {
            List<Object> all = new ArrayList<>(args);
            all.addAll(List.of(extra));
            return all.toArray();
        }
    }
}
