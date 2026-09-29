package io.github.akhil1527.sponsorscout;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP tools. Every result carries a short "speech" line meant to be read aloud by a voice assistant,
 * plus structured data for a screen.
 */
@Component
public class ScoutTools {

    public record ProfileResult(String speech, Tracker.Profile profile) {
    }

    public record SponsorsResult(String speech, String source, List<Sponsors.Sponsor> sponsors) {
    }

    public record SponsorResult(String speech, String source, Sponsors.SponsorRecord record) {
    }

    public record Opening(String jobId, String title, String company, String location, String posted, String url,
            int companyFilings, int roleFilings, Long roleAvgWage) {
    }

    public record OpeningsResult(String speech, List<Opening> openings) {
    }

    public record JobResult(String speech, Tracker.SavedJob job) {
    }

    public record ApplicationsResult(String speech, Map<String, Integer> counts, List<Tracker.SavedJob> jobs) {
    }

    public record Briefing(String speech, List<Opening> newOpenings, List<Tracker.SavedJob> followUpsDue,
            Map<String, Integer> pipeline) {
    }

    public record Prep(String speech, Opening job, Sponsors.SponsorRecord sponsor, List<String> matchingSkills,
            Integer yearsAsked, boolean saysNoSponsorship, String posting) {
    }

    private static final DateTimeFormatter SPOKEN_DAY = DateTimeFormatter.ofPattern("MMMM d", Locale.US);

    private final Sponsors sponsors;
    private final JobBoards boards;
    private final Tracker tracker;

    ScoutTools(Sponsors sponsors, JobBoards boards, Tracker tracker) {
        this.sponsors = sponsors;
        this.boards = boards;
        this.tracker = tracker;
    }

    @McpTool(name = "set_profile", description = "Save or update the job seeker's profile. Only the fields given "
            + "change. The profile is remembered between conversations and drives job matching.")
    public ProfileResult setProfile(
            @McpToolParam(description = "Target job titles, comma separated, e.g. 'java developer, backend engineer'",
                    required = false) String roles,
            @McpToolParam(description = "Key skills, comma separated, e.g. 'Java, Spring Boot, SQL'",
                    required = false) String skills,
            @McpToolParam(description = "Years of professional experience", required = false) Integer years,
            @McpToolParam(description = "Preferred US states as codes or names, comma separated. Remote US roles "
                    + "are always included.", required = false) String states,
            @McpToolParam(description = "Work authorization, e.g. 'F-1 OPT', 'STEM OPT', 'H-1B transfer'",
                    required = false) String visa) {
        Tracker.Profile p = tracker.saveProfile(roles, skills, years, states, visa);
        return new ProfileResult("Got it. I'll look for " + spokenList(p.roles())
                + (p.years() == null ? "" : " roles that fit " + p.years() + " years of experience") + ".", p);
    }

    @McpTool(name = "get_profile", description = "Read the saved profile. Call this at the start of a conversation "
            + "to pick up where the user left off.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public ProfileResult getProfile() {
        Tracker.Profile p = tracker.profile();
        if (p == null || p.roles().isEmpty()) {
            return new ProfileResult("I don't have a profile yet. Tell me the roles you want, your years of "
                    + "experience and your visa status, and I'll remember them.", p);
        }
        return new ProfileResult("You're looking for " + spokenList(p.roles())
                + (p.visa() == null ? "" : " on " + p.visa()) + ".", p);
    }

    @McpTool(name = "find_sponsors", description = "Employers that filed the most certified H-1B labor condition "
            + "applications for a job title, optionally in one US state. Use for 'who sponsors data analysts in Texas'.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public SponsorsResult findSponsors(
            @McpToolParam(description = "Job title words, e.g. 'data analyst'") String role,
            @McpToolParam(description = "US state code or name", required = false) String state,
            @McpToolParam(description = "How many employers, default 5, max 20", required = false) Integer limit) {
        String source = requireData();
        List<Sponsors.Sponsor> list = sponsors.top(role, state, clamp(limit, 5, 20));
        if (list.isEmpty()) {
            return new SponsorsResult("I found no certified H-1B filings for " + role
                    + (state == null ? "" : " in " + state) + ". Try a broader title.", source, list);
        }
        String names = spokenList(list.stream().limit(3)
                .map(s -> Filters.spokenName(s.employer()) + " with " + s.filings() + " filings").toList());
        return new SponsorsResult("Top H-1B filers for " + role + (state == null ? "" : " in " + state) + ": "
                + names + ".", source, list);
    }

    @McpTool(name = "check_sponsor", description = "One employer's H-1B filing record: total certified filings, "
            + "new hires versus transfers, common titles, wages and cities. Use for 'does Stripe sponsor H-1B'.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public SponsorResult checkSponsor(
            @McpToolParam(description = "Company name") String company,
            @McpToolParam(description = "Optional job title words to narrow the record", required = false)
            String role) {
        String source = requireData();
        Sponsors.SponsorRecord r = sponsors.record(company, role);
        if (r == null) {
            return new SponsorResult("I found no certified H-1B filings for " + company + " in the Labor Department "
                    + "data I have. That doesn't prove they never sponsor, so check the posting too.", source, null);
        }
        String speech = Filters.spokenName(r.employer()) + " has " + r.filings() + " certified H-1B filings, " + r.newHires()
                + " for new hires and " + r.transfers() + " for transfers"
                + (role == null || role.isBlank() ? "" : ", " + r.roleFilings() + " of them for " + role + " titles")
                + (money(r.roleAvgWage() != null ? r.roleAvgWage() : r.avgWage()).isEmpty() ? "."
                        : ", with an average listed wage of "
                                + money(r.roleAvgWage() != null ? r.roleAvgWage() : r.avgWage()) + ".");
        return new SponsorResult(speech, source, r);
    }

    @McpTool(name = "find_openings", description = "Live US job openings from company career boards, matched to "
            + "the saved profile. Drops postings that refuse visa sponsorship or ask for far more experience, and "
            + "ranks employers with H-1B filings for similar titles first.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = true))
    public OpeningsResult findOpenings(
            @McpToolParam(description = "Job title words; defaults to the profile's roles", required = false)
            String role,
            @McpToolParam(description = "US state code or name; defaults to the profile's states", required = false)
            String state,
            @McpToolParam(description = "How many openings, default 5, max 15", required = false) Integer limit) {
        List<Opening> list = openings(role, state, clamp(limit, 5, 15));
        tracker.markSeen(list.stream().map(Opening::jobId).toList());
        if (list.isEmpty()) {
            return new OpeningsResult("No matching openings right now on the boards I watch. I'll keep checking.",
                    list);
        }
        Opening top = list.get(0);
        return new OpeningsResult("I found " + list.size() + " openings. The first is " + top.title() + " at "
                + top.company() + ", " + place(top.location()) + sponsorNote(top) + ".", list);
    }

    @McpTool(name = "save_job", description = "Save an opening to the user's tracker so it's remembered.")
    public JobResult saveJob(
            @McpToolParam(description = "jobId from find_openings or daily_briefing") String jobId,
            @McpToolParam(description = "Optional note", required = false) String note) {
        JobBoards.Job j = boards.find(jobId);
        if (j == null) {
            return new JobResult("I can't find that job on the boards anymore. It may have closed.", null);
        }
        return new JobResult("Saved " + j.title() + " at " + j.company() + ".", tracker.save(j, note));
    }

    @McpTool(name = "update_status", description = "Record progress on a job: saved, applied, interviewing, offer, "
            + "rejected or withdrawn. Saves the job first if needed. Marking applied sets a follow-up reminder in "
            + "7 days unless a date is given.")
    public JobResult updateStatus(
            @McpToolParam(description = "jobId of the job") String jobId,
            @McpToolParam(description = "saved, applied, interviewing, offer, rejected or withdrawn") String status,
            @McpToolParam(description = "Optional note, e.g. the recruiter's name", required = false) String note,
            @McpToolParam(description = "Optional follow-up date, YYYY-MM-DD", required = false) String followUp) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (!Tracker.STATUSES.contains(s)) {
            throw new IllegalArgumentException("status must be one of " + Tracker.STATUSES);
        }
        if (followUp != null) {
            LocalDate.parse(followUp);
        } else if (s.equals("applied")) {
            followUp = LocalDate.now().plusDays(7).toString();
        }
        if (tracker.get(jobId) == null) {
            JobBoards.Job j = boards.find(jobId);
            if (j == null) {
                return new JobResult("I can't find that job. Search again and I'll save it.", null);
            }
            tracker.save(j, null);
        }
        Tracker.SavedJob job = tracker.setStatus(jobId, s, note, followUp);
        return new JobResult("Marked " + job.title() + " at " + job.company() + " as " + s
                + (job.followUp() == null || !List.of("saved", "applied", "interviewing").contains(s) ? "."
                        : ". I'll remind you to follow up on " + spokenDay(job.followUp()) + "."), job);
    }

    @McpTool(name = "list_applications", description = "The user's tracked jobs, newest first, optionally one status.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public ApplicationsResult listApplications(
            @McpToolParam(description = "Optional status filter", required = false) String status) {
        List<Tracker.SavedJob> jobs = tracker.list(status == null || status.isBlank() ? null
                : status.trim().toLowerCase(Locale.ROOT));
        Map<String, Integer> counts = tracker.counts();
        if (counts.isEmpty()) {
            return new ApplicationsResult("You haven't saved any jobs yet.", counts, jobs);
        }
        return new ApplicationsResult("You're tracking " + counts.values().stream().mapToInt(Integer::intValue).sum()
                + " jobs: " + spokenList(counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList())
                + ".", counts, jobs);
    }

    @McpTool(name = "daily_briefing", description = "Morning briefing: openings that are new since the user last "
            + "looked, follow-ups that are due, and the application pipeline. Use for 'what's new today'.")
    public Briefing dailyBriefing() {
        Tracker.Profile p = tracker.profile();
        if (p == null || p.roles().isEmpty()) {
            return new Briefing("I don't know what you're looking for yet. Tell me your target roles, experience "
                    + "and visa status, and I'll remember them.", List.of(), List.of(), Map.of());
        }
        List<Opening> all = openings(null, null, 25);
        Set<String> fresh = new HashSet<>(tracker.markSeen(all.stream().map(Opening::jobId).toList()));
        List<Opening> news = all.stream().filter(o -> fresh.contains(o.jobId())).limit(5).toList();
        List<Tracker.SavedJob> due = tracker.followUpsDue(LocalDate.now());
        Map<String, Integer> pipeline = tracker.counts();

        List<String> parts = new ArrayList<>();
        if (news.isEmpty()) {
            parts.add("No new openings since last time");
        } else {
            Opening top = news.get(0);
            parts.add(fresh.size() + " new " + (fresh.size() == 1 ? "opening" : "openings")
                    + ". The top one is " + top.title() + " at " + top.company() + sponsorNote(top));
        }
        if (!due.isEmpty()) {
            parts.add(due.size() + " follow-up" + (due.size() == 1 ? "" : "s") + " due, starting with "
                    + due.get(0).title() + " at " + due.get(0).company());
        }
        if (!pipeline.isEmpty()) {
            parts.add("Your pipeline: " + spokenList(pipeline.entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey()).toList()));
        }
        return new Briefing(String.join(". ", parts) + ".", news, due, pipeline);
    }

    @McpTool(name = "prep_application", description = "Everything needed to tailor an application: the posting "
            + "text, the employer's H-1B record for similar titles, which of the user's skills the posting mentions, "
            + "and the experience it asks for.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, openWorldHint = true))
    public Prep prepApplication(@McpToolParam(description = "jobId of the job") String jobId) {
        JobBoards.Job j = boards.find(jobId);
        if (j == null) {
            return new Prep("I can't find that posting anymore. It may have closed.", null, null, List.of(), null,
                    false, null);
        }
        String text = boards.texts(List.of(j)).get(j.id());
        Tracker.Profile p = tracker.profile();
        String role = matchedRole(j, p == null ? List.of() : p.roles());
        Sponsors.SponsorRecord r = sponsors.record(j.company(), role);
        List<String> skills = p == null ? List.of() : p.skills().stream()
                .filter(s -> Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(s) + "(?![A-Za-z0-9])",
                        Pattern.CASE_INSENSITIVE).matcher(text).find())
                .toList();
        Integer years = Filters.minYears(text);
        boolean refuses = Filters.noSponsor(text);

        List<String> parts = new ArrayList<>();
        parts.add(r == null ? j.company() + " has no certified H-1B filings in my data"
                : Filters.spokenName(r.employer()) + " has " + r.filings() + " certified H-1B filings"
                        + (role == null ? "" : ", " + r.roleFilings() + " for " + role + " titles"));
        if (refuses) {
            parts.add("Careful, this posting says it won't sponsor");
        }
        if (!skills.isEmpty()) {
            parts.add("The posting mentions your skills " + spokenList(skills));
        }
        if (years != null) {
            parts.add("It asks for about " + years + " years of experience");
        }
        Opening o = opening(j, r);
        return new Prep(String.join(". ", parts) + ".", o, r, skills, years, refuses,
                text.length() > 6000 ? text.substring(0, 6000) : text);
    }

    // ---- shared by find_openings and daily_briefing ----

    private List<Opening> openings(String role, String state, int limit) {
        Tracker.Profile p = tracker.profile();
        List<String> roles = role != null && !role.isBlank() ? List.of(role)
                : p == null ? List.of() : p.roles();
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("Give a role to search for, or set a profile first.");
        }
        Integer years = p == null ? null : p.years();
        List<String> states = state != null && !state.isBlank() ? List.of(state)
                : p == null ? List.of() : p.states();

        List<JobBoards.Job> candidates = boards.all().stream()
                .filter(j -> Filters.isUs(j.location()))
                .filter(j -> matchedRole(j, roles) != null)
                .filter(j -> Filters.fitsLevel(j.title(), years))
                .filter(j -> states.isEmpty() || states.stream().anyMatch(s -> Filters.inState(j.location(), s)))
                .sorted(Comparator.comparing(JobBoards.Job::posted, Comparator.nullsLast(Comparator.reverseOrder())))
                // posting text is fetched for at most this many; widen if the text gates drop too many
                .limit(limit * 6L)
                .toList();
        Map<String, String> texts = boards.texts(candidates);
        Map<String, Sponsors.SponsorRecord> records = new HashMap<>();
        Map<String, Integer> perCompany = new HashMap<>();
        return candidates.stream()
                .filter(j -> !Filters.noSponsor(texts.get(j.id())))
                .filter(j -> {
                    Integer need = Filters.minYears(texts.get(j.id()));
                    return years == null || need == null || need <= years + 1;
                })
                .map(j -> opening(j, records.computeIfAbsent(j.company() + "|" + matchedRole(j, roles),
                        k -> sponsors.record(j.company(), matchedRole(j, roles)))))
                .sorted(Comparator.comparingInt(Opening::roleFilings).reversed()
                        .thenComparing(Comparator.comparingInt(Opening::companyFilings).reversed()))
                .filter(o -> perCompany.merge(o.company(), 1, Integer::sum) <= 2) // variety beats five DoorDash jobs
                .limit(limit)
                .toList();
    }

    private static Opening opening(JobBoards.Job j, Sponsors.SponsorRecord r) {
        return new Opening(j.id(), j.title(), j.company(), j.location(), j.posted(), j.url(),
                r == null ? 0 : r.filings(), r == null ? 0 : r.roleFilings(), r == null ? null : r.roleAvgWage());
    }

    private static String matchedRole(JobBoards.Job j, List<String> roles) {
        return roles.stream().filter(r -> Filters.matchesRole(j.title(), r)).findFirst().orElse(null);
    }

    private String requireData() {
        String c = sponsors.coverage();
        if (c == null) {
            throw new IllegalStateException("No H-1B data loaded. Run the ingest command from the README first.");
        }
        return "US Department of Labor LCA disclosure data: " + c;
    }

    private static String sponsorNote(Opening o) {
        if (o.roleFilings() > 0) {
            return ", and they filed " + o.roleFilings() + " H-1B applications for similar titles";
        }
        return o.companyFilings() > 0 ? ", and they filed " + o.companyFilings() + " H-1B applications overall"
                : ", though I have no H-1B filings on record for them";
    }

    private static String place(String location) {
        return location == null || location.isBlank() ? "location not listed" : location.split(";")[0];
    }

    private static String money(Long wage) {
        return wage == null ? "" : String.format(Locale.US, "$%,d", wage);
    }

    private static String spokenDay(String isoDay) {
        return LocalDate.parse(isoDay).format(SPOKEN_DAY);
    }

    private static String spokenList(List<String> items) {
        List<String> l = items.stream().filter(Objects::nonNull).toList();
        if (l.size() <= 1) {
            return String.join("", l);
        }
        return l.subList(0, l.size() - 1).stream().collect(Collectors.joining(", ")) + " and " + l.get(l.size() - 1);
    }

    private static int clamp(Integer n, int dflt, int max) {
        return n == null || n < 1 ? dflt : Math.min(n, max);
    }
}
