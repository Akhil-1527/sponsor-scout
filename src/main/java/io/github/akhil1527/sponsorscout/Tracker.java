package io.github.akhil1527.sponsorscout;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The job seeker's profile and application tracker. Lives in SQLite, so it survives across conversations. */
@Component
class Tracker {

    static final List<String> STATUSES = List.of("saved", "applied", "interviewing", "offer", "rejected", "withdrawn");

    record Profile(List<String> roles, List<String> skills, Integer years, List<String> states, String visa,
            String updated) {
    }

    record SavedJob(String jobId, String company, String title, String location, String url, String status,
            String note, String followUp, String updated) {
    }

    private final JdbcTemplate db;

    Tracker(JdbcTemplate db) {
        this.db = db;
    }

    Profile profile() {
        List<Profile> found = db.query("SELECT * FROM profile WHERE id = 1", (rs, i) -> {
            int years = rs.getInt("years");
            return new Profile(split(rs.getString("roles")), split(rs.getString("skills")),
                    rs.wasNull() ? null : years, split(rs.getString("states")), rs.getString("visa"),
                    rs.getString("updated"));
        });
        return found.isEmpty() ? null : found.get(0);
    }

    /** Changes only the fields given; null keeps the saved value. */
    Profile saveProfile(String roles, String skills, Integer years, String states, String visa) {
        Profile old = profile();
        db.update("INSERT OR REPLACE INTO profile VALUES (1, ?, ?, ?, ?, ?, ?)",
                roles != null ? roles : old == null ? null : String.join(",", old.roles()),
                skills != null ? skills : old == null ? null : String.join(",", old.skills()),
                years != null ? years : old == null ? null : old.years(),
                states != null ? states : old == null ? null : String.join(",", old.states()),
                visa != null ? visa : old == null ? null : old.visa(),
                Instant.now().toString());
        return profile();
    }

    SavedJob save(JobBoards.Job j, String note) {
        db.update("INSERT INTO saved_job VALUES (?, ?, ?, ?, ?, 'saved', ?, NULL, ?) "
                + "ON CONFLICT (job_id) DO UPDATE SET note = COALESCE(excluded.note, note), updated = excluded.updated",
                j.id(), j.company(), j.title(), j.location(), j.url(), note, Instant.now().toString());
        return get(j.id());
    }

    /** Returns null when the job was never saved. */
    SavedJob setStatus(String jobId, String status, String note, String followUp) {
        int n = db.update("UPDATE saved_job SET status = ?, note = COALESCE(?, note), follow_up = COALESCE(?, follow_up), "
                + "updated = ? WHERE job_id = ?", status, note, followUp, Instant.now().toString(), jobId);
        return n == 0 ? null : get(jobId);
    }

    SavedJob get(String jobId) {
        List<SavedJob> found = db.query("SELECT * FROM saved_job WHERE job_id = ?", Tracker::savedJob, jobId);
        return found.isEmpty() ? null : found.get(0);
    }

    List<SavedJob> list(String status) {
        return db.query("SELECT * FROM saved_job WHERE (? IS NULL OR status = ?) ORDER BY updated DESC",
                Tracker::savedJob, status, status);
    }

    List<SavedJob> followUpsDue(LocalDate day) {
        return db.query("SELECT * FROM saved_job WHERE follow_up <= ? AND status IN ('saved', 'applied', 'interviewing') "
                + "ORDER BY follow_up", Tracker::savedJob, day.toString());
    }

    Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        db.query("SELECT status, COUNT(*) n FROM saved_job GROUP BY status ORDER BY n DESC",
                rs -> {
                    out.put(rs.getString("status"), rs.getInt("n"));
                });
        return out;
    }

    /** Records the ids as seen and returns the ones that were new. */
    List<String> markSeen(List<String> ids) {
        List<String> fresh = new ArrayList<>();
        String now = Instant.now().toString();
        for (String id : ids) {
            if (db.update("INSERT OR IGNORE INTO seen_job VALUES (?, ?)", id, now) == 1) {
                fresh.add(id);
            }
        }
        return fresh;
    }

    private static SavedJob savedJob(ResultSet rs, int i) throws SQLException {
        return new SavedJob(rs.getString("job_id"), rs.getString("company"), rs.getString("title"),
                rs.getString("location"), rs.getString("url"), rs.getString("status"), rs.getString("note"),
                rs.getString("follow_up"), rs.getString("updated"));
    }

    private static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
