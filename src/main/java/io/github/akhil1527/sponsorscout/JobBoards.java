package io.github.akhil1527.sponsorscout;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Live openings from public ATS job-board APIs (Greenhouse, Lever, Ashby, SmartRecruiters).
 * The whole list is cached and refreshed every 30 minutes so a voice request never waits on 100+ boards.
 */
@Component
class JobBoards {

    private static final Logger log = LoggerFactory.getLogger(JobBoards.class);

    record Board(String company, String ats, String slug) {
    }

    record Job(String id, String company, String title, String location, String url, String posted) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final JsonMapper json;
    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<String, String> texts = new ConcurrentHashMap<>();
    private volatile Map<String, Job> jobs;

    JobBoards(JsonMapper json) throws IOException {
        this.json = json;
        try (var in = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/boards.csv"), UTF_8))) {
            in.lines().skip(1).map(line -> line.split(","))
                    .forEach(p -> boards.put(p[1] + ":" + p[2], new Board(p[0], p[1], p[2])));
        }
    }

    @Scheduled(initialDelay = 0, fixedDelay = 30, timeUnit = TimeUnit.MINUTES)
    synchronized void refresh() {
        long start = System.currentTimeMillis();
        Map<String, Job> next = new ConcurrentHashMap<>();
        List<String> failed = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture.allOf(boards.values().stream()
                .map(b -> get(listUrl(b))
                        .exceptionallyCompose(e -> get(listUrl(b))) // one retry: a few boards drop under load
                        .thenAccept(body -> parse(b, body).forEach(j -> next.put(j.id(), j)))
                        .exceptionally(e -> {
                            failed.add(b.company() + " (" + e.getMessage() + ")");
                            return null;
                        }))
                .toArray(CompletableFuture[]::new)).join();
        jobs = next;
        log.info("Loaded {} jobs from {} boards in {} ms. Failed: {}", next.size(), boards.size() - failed.size(),
                System.currentTimeMillis() - start, failed);
    }

    Collection<Job> all() {
        if (jobs == null) {
            synchronized (this) {
                if (jobs == null) {
                    refresh();
                }
            }
        }
        return jobs.values();
    }

    Job find(String id) {
        all();
        return id == null ? null : jobs.get(id);
    }

    /** Plain posting text per job id, fetched once. Empty when the board gives none. */
    Map<String, String> texts(List<Job> list) {
        CompletableFuture.allOf(list.stream()
                .filter(j -> !texts.containsKey(j.id()))
                .map(j -> get(detailUrl(j))
                        .thenApply(body -> detailText(j, body))
                        .exceptionally(e -> "")
                        .thenAccept(t -> texts.put(j.id(), t)))
                .toArray(CompletableFuture[]::new)).join();
        return list.stream().collect(Collectors.toMap(Job::id, j -> texts.getOrDefault(j.id(), ""), (a, b) -> a));
    }

    private CompletableFuture<String> get(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "sponsor-scout/0.1 (+https://github.com/Akhil-1527/sponsor-scout)")
                .GET().build();
        return http.sendAsync(req, BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() != 200) {
                throw new IllegalStateException(url + " returned " + r.statusCode());
            }
            return r.body();
        });
    }

    private static String listUrl(Board b) {
        return switch (b.ats()) {
            case "greenhouse" -> "https://boards-api.greenhouse.io/v1/boards/" + b.slug() + "/jobs";
            case "lever" -> "https://api.lever.co/v0/postings/" + b.slug() + "?mode=json";
            case "ashby" -> "https://api.ashbyhq.com/posting-api/job-board/" + b.slug();
            case "smartrecruiters" -> "https://api.smartrecruiters.com/v1/companies/" + b.slug() + "/postings?limit=100";
            default -> throw new IllegalArgumentException("Unknown ATS " + b.ats());
        };
    }

    private static String detailUrl(Job j) {
        String[] p = j.id().split(":", 3);
        return switch (p[0]) {
            case "greenhouse" -> "https://boards-api.greenhouse.io/v1/boards/" + p[1] + "/jobs/" + p[2];
            case "smartrecruiters" -> "https://api.smartrecruiters.com/v1/companies/" + p[1] + "/postings/" + p[2];
            default -> j.url(); // Lever and Ashby text arrives with the list, so this is never fetched
        };
    }

    private List<Job> parse(Board b, String body) {
        JsonNode root = json.readTree(body);
        List<Job> out = new ArrayList<>();
        String prefix = b.ats() + ":" + b.slug() + ":";
        switch (b.ats()) {
            case "greenhouse" -> {
                for (JsonNode j : root.path("jobs")) {
                    String posted = s(j.path("first_published")).isEmpty() ? s(j.path("updated_at"))
                            : s(j.path("first_published"));
                    out.add(new Job(prefix + s(j.path("id")), b.company(), s(j.path("title")),
                            s(j.path("location").path("name")), s(j.path("absolute_url")), day(posted)));
                }
            }
            case "lever" -> {
                for (JsonNode j : root) {
                    Job job = new Job(prefix + s(j.path("id")), b.company(), s(j.path("text")),
                            s(j.path("categories").path("location")), s(j.path("hostedUrl")),
                            day(Instant.ofEpochMilli(j.path("createdAt").asLong()).toString()));
                    StringBuilder t = new StringBuilder(s(j.path("descriptionPlain")));
                    for (JsonNode l : j.path("lists")) {
                        t.append('\n').append(s(l.path("text"))).append('\n').append(plain(s(l.path("content"))));
                    }
                    texts.put(job.id(), t.append('\n').append(s(j.path("additionalPlain"))).toString());
                    out.add(job);
                }
            }
            case "ashby" -> {
                for (JsonNode j : root.path("jobs")) {
                    String location = s(j.path("location"));
                    String country = s(j.path("address").path("postalAddress").path("addressCountry"));
                    if (!country.isEmpty() && !location.contains(country)) {
                        location = location + ", " + country;
                    }
                    Job job = new Job(prefix + s(j.path("id")), b.company(), s(j.path("title")), location,
                            s(j.path("jobUrl")), day(s(j.path("publishedAt"))));
                    texts.put(job.id(), s(j.path("descriptionPlain")));
                    out.add(job);
                }
            }
            case "smartrecruiters" -> {
                for (JsonNode j : root.path("content")) {
                    out.add(new Job(prefix + s(j.path("id")), b.company(), s(j.path("name")),
                            s(j.path("location").path("fullLocation")),
                            "https://jobs.smartrecruiters.com/" + b.slug() + "/" + s(j.path("id")),
                            day(s(j.path("releasedDate")))));
                }
            }
            default -> {
            }
        }
        return out;
    }

    private String detailText(Job j, String body) {
        JsonNode root = json.readTree(body);
        if (j.id().startsWith("greenhouse:")) {
            return plain(s(root.path("content")));
        }
        StringBuilder t = new StringBuilder();
        for (JsonNode section : root.path("jobAd").path("sections").values()) {
            t.append(plain(s(section.path("text")))).append('\n');
        }
        return t.toString();
    }

    /** HTML (possibly entity-escaped, as Greenhouse sends it) to plain text. */
    static String plain(String html) {
        String s = HtmlUtils.htmlUnescape(html).replaceAll("<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(s).replaceAll("[ \\t\\x0B\\f\\r\\u00A0]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
    }

    private static String s(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? "" : n.asString();
    }

    private static String day(String iso) {
        return iso.length() >= 10 ? iso.substring(0, 10) : null;
    }
}
