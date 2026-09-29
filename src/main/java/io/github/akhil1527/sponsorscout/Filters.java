package io.github.akhil1527.sponsorscout;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Plain text rules shared by the job and sponsor lookups. */
final class Filters {

    private Filters() {
    }

    static final Map<String, String> STATES = new LinkedHashMap<>();

    static {
        String[] pairs = ("AL alabama,AK alaska,AZ arizona,AR arkansas,CA california,CO colorado,CT connecticut,"
                + "DE delaware,FL florida,GA georgia,HI hawaii,ID idaho,IL illinois,IN indiana,IA iowa,KS kansas,"
                + "KY kentucky,LA louisiana,ME maine,MD maryland,MA massachusetts,MI michigan,MN minnesota,"
                + "MS mississippi,MO missouri,MT montana,NE nebraska,NV nevada,NH new hampshire,NJ new jersey,"
                + "NM new mexico,NY new york,NC north carolina,ND north dakota,OH ohio,OK oklahoma,OR oregon,"
                + "PA pennsylvania,RI rhode island,SC south carolina,SD south dakota,TN tennessee,TX texas,UT utah,"
                + "VT vermont,VA virginia,WA washington,WV west virginia,WI wisconsin,WY wyoming,"
                + "DC district of columbia,PR puerto rico").split(",");
        for (String p : pairs) {
            STATES.put(p.substring(0, 2), p.substring(3));
        }
    }

    private static final String US_CITIES = "san francisco,san jose,seattle,new york,los angeles,chicago,boston,"
            + "austin,dallas,houston,atlanta,denver,phoenix,philadelphia,san diego,miami,charlotte,columbus,"
            + "nashville,minneapolis,portland,pittsburgh,cincinnati,cleveland,detroit,kansas city,saint louis,"
            + "st. louis,st louis,indianapolis,milwaukee,baltimore,tampa,orlando,jacksonville,raleigh,durham,"
            + "richmond,sacramento,salt lake city,las vegas,oklahoma city,memphis,louisville,tempe,scottsdale,"
            + "irvine,plano,irving,frisco,princeton,wilmington,bellevue,redmond,sunnyvale,mountain view,palo alto,"
            + "cupertino,santa clara,bentonville,omaha,des moines,madison,ann arbor,hartford,stamford,jersey city,"
            + "newark,arlington,alexandria,reston,mclean,herndon,brooklyn,the woodlands,menlo park,cambridge";

    // Countries and foreign cities seen on these boards. Word boundaries keep "india" out of "indiana".
    private static final String FOREIGN = "india,germany,poland,canada,mexico,brazil,argentina,colombia,chile,"
            + "peru,china,hong kong,taiwan,japan,korea,singapore,malaysia,thailand,vietnam,philippines,indonesia,"
            + "australia,new zealand,south africa,nigeria,kenya,egypt,israel,united arab emirates,uae,"
            + "saudi arabia,qatar,turkey,russia,ukraine,romania,bulgaria,czechia,czech republic,hungary,croatia,"
            + "serbia,greece,lithuania,latvia,estonia,switzerland,austria,sweden,norway,denmark,finland,"
            + "netherlands,belgium,spain,italy,portugal,luxembourg,ireland,united kingdom,u.k.,uk,england,"
            + "scotland,wales,france,sri lanka,bangladesh,pakistan,costa rica,uruguay,morocco,"
            + "pune,bengaluru,bangalore,hyderabad,chennai,mumbai,gurgaon,gurugram,noida,new delhi,berlin,munich,"
            + "hamburg,frankfurt,warsaw,krakow,toronto,vancouver,montreal,ottawa,calgary,waterloo,ontario,"
            + "quebec,vilnius,riga,tallinn,dublin,belfast,london,manchester,edinburgh,paris,amsterdam,brussels,"
            + "zurich,geneva,vienna,stockholm,oslo,copenhagen,helsinki,lisbon,madrid,barcelona,milan,rome,"
            + "athens,prague,budapest,bucharest,sofia,istanbul,tokyo,seoul,beijing,shanghai,shenzhen,taipei,"
            + "sydney,melbourne,auckland,manila,jakarta,bangkok,kuala lumpur,tel aviv,dubai,sao paulo,"
            + "mexico city,bogota,buenos aires";

    // ISO codes that never collide with a US state code, so they can be rejected outright.
    private static final Set<String> ISO3 = Set.of(("IND DEU GBR CAN POL PHL CHN MEX BRA ARG COL CHL PER JPN KOR "
            + "SGP MYS THA VNM IDN AUS NZL ZAF EGY ISR ARE SAU TUR UKR ROU CZE HUN CHE AUT SWE NOR DNK FIN NLD BEL "
            + "ESP ITA PRT GRC LTU LVA EST IRL FRA LUX LKA BGD PAK CRI URY MAR").split(" "));
    private static final Set<String> ISO2 = Set.of(("GB UK FR JP SG CN BR CH AT BE NL SE NO DK FI PT GR CZ HU RO BG "
            + "HR EE TR RU UA AE QA EG ZA NG KE KR TW MY TH VN PH AU NZ CL PE CR LK BD PK ES IT PL IE").split(" "));

    private static final Pattern US_MARK = Pattern.compile("\\bus\\b|u\\.s\\.|usa|united states");
    private static final Pattern FOREIGN_RX = words(FOREIGN);
    private static final Pattern US_NAME_RX = words(String.join(",", STATES.values()) + "," + US_CITIES);
    private static final Pattern CODE3 = Pattern.compile("(?<![A-Za-z])([A-Z]{3})(?![A-Za-z])");
    private static final Pattern CODE2 = Pattern.compile("(?<![A-Za-z])([A-Z]{2})(?![A-Za-z])");

    private static Pattern words(String csv) {
        String alt = Arrays.stream(csv.split(",")).map(Pattern::quote).collect(Collectors.joining("|"));
        return Pattern.compile("(?<![a-z])(" + alt + ")(?![a-z])", Pattern.CASE_INSENSITIVE);
    }

    /** True if a job location is in the United States. A bare "Remote" is not enough. */
    static boolean isUs(String location) {
        String s = location == null ? "" : location;
        if (US_MARK.matcher(s.toLowerCase(Locale.ROOT)).find()) {
            return true;
        }
        if (FOREIGN_RX.matcher(s).find() || anyCode(CODE3, s, ISO3) || anyCode(CODE2, s, ISO2)) {
            return false;
        }
        return US_NAME_RX.matcher(s).find() || anyCode(CODE2, s, STATES.keySet());
    }

    /** True if the location names this state (code or full name), or is remote. */
    static boolean inState(String location, String state) {
        String code = stateCode(state);
        if (code == null || location == null) {
            return true;
        }
        String l = location.toLowerCase(Locale.ROOT);
        return l.contains("remote") || l.contains(STATES.get(code)) || anyCode(CODE2, location, Set.of(code));
    }

    /** "texas", "TX" or "tx" to "TX"; null if not a state. */
    static String stateCode(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String s = state.trim();
        if (STATES.containsKey(s.toUpperCase(Locale.ROOT))) {
            return s.toUpperCase(Locale.ROOT);
        }
        return STATES.entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(s))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static boolean anyCode(Pattern p, String s, Set<String> codes) {
        Matcher m = p.matcher(s);
        while (m.find()) {
            if (codes.contains(m.group(1))) {
                return true;
            }
        }
        return false;
    }

    // Posting text that rules out visa holders. Ported from the daily fetch script that fed real applications.
    private static final Pattern NO_SPONSOR = Pattern.compile(
            "(?:not|cannot|can't|unable\\s+to|will\\s+not|won't|do(?:es)?\\s+not|no)\\s+(?:able\\s+to\\s+|be\\s+able\\s+to\\s+|currently\\s+)?"
                    + "(?:provide|offer|sponsor|consider|support)\\w*\\s+(?:visa\\s+|work\\s+visa\\s+|h-?1b\\s+|employment\\s+|immigration\\s+|any\\s+)?"
                    + "(?:sponsorship|sponsor|visas?|h-?1b|candidates?\\s+(?:who|that)\\s+(?:need|require)\\s+sponsorship)"
                    + "|without\\s+(?:the\\s+need\\s+for\\s+|requiring\\s+|current\\s+or\\s+future\\s+|need\\s+of\\s+)?(?:visa\\s+|employer\\s+|company\\s+)?sponsorship"
                    + "|\\bno\\s+(?:visa\\s+|h-?1b\\s+)?sponsorship\\b(?!\\s+(?:needed|required|necessary))"
                    + "|(?:us|u\\.s\\.)\\s+citizen(?:s|ship)?\\s+(?:only|required|is\\s+required)|must\\s+be\\s+(?:a\\s+|an\\s+)?(?:us|u\\.s\\.)\\s+citizen"
                    + "|(?:green\\s*card|gc)\\s*(?:holders?)?\\s*(?:or|/)\\s*(?:us|u\\.s\\.)\\s*citizens?\\s*only"
                    + "|(?:active|current)\\s+(?:secret|top\\s+secret|ts/sci|dod)\\s+(?:security\\s+)?clearance",
            Pattern.CASE_INSENSITIVE);

    static boolean noSponsor(String text) {
        return text != null && NO_SPONSOR.matcher(text).find();
    }

    private static final Pattern YEARS = Pattern.compile(
            "(\\d{1,2})\\s*\\+?\\s*(?:(?:-|–|to)\\s*(\\d{1,2})\\s*)?\\+?\\s*(?:years|yrs)['’]?\\s+(?:of\\s+)?(?:[\\w-]+\\s+){0,4}?experience",
            Pattern.CASE_INSENSITIVE);

    /** Largest "N+ years ... experience" lower bound in a posting, or null if it names none. */
    static Integer minYears(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = YEARS.matcher(text);
        Integer max = null;
        while (m.find()) {
            int y = Integer.parseInt(m.group(1));
            if (y <= 30 && (max == null || y > max)) {
                max = y;
            }
        }
        return max;
    }

    private static final Pattern SENIOR = Pattern.compile(
            "\\b(senior|sr|snr|lead|principal|staff|manager|director|vp|vice president|head|chief|architect)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOP_LEVEL = Pattern.compile(
            "\\b(principal|staff|director|vp|vice president|head|chief|distinguished)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern STUDENT = Pattern.compile(
            "\\b(intern|internship|new grad|co-op|apprentice)\\b", Pattern.CASE_INSENSITIVE);

    /** Keeps senior titles away from junior profiles, and internships away from experienced ones. */
    static boolean fitsLevel(String title, Integer years) {
        if (years == null) {
            return true;
        }
        if (years >= 2 && STUDENT.matcher(title).find()) {
            return false;
        }
        if (years >= 9) {
            return true;
        }
        return !(years >= 5 ? TOP_LEVEL : SENIOR).matcher(title).find();
    }

    private static final Pattern LEGAL_SUFFIX = Pattern.compile(
            "(,?\\s+(inc|llc|l\\.l\\.c|corp|corporation|co|ltd|limited|n\\.a|national association|llp|lp|plc)\\.?)+$", Pattern.CASE_INSENSITIVE);

    /** Employer name as a person would say it: "Citibank, N.A." to "Citibank". */
    static String spokenName(String employer) {
        return LEGAL_SUFFIX.matcher(employer.trim()).replaceAll("");
    }

    /** Every word of the role appears in the title: "java developer" matches "Java Full Stack Developer". */
    static boolean matchesRole(String title, String role) {
        String t = title.toLowerCase(Locale.ROOT);
        return Arrays.stream(role.toLowerCase(Locale.ROOT).split("[^a-z0-9+#.]+"))
                .filter(w -> !w.isEmpty())
                .allMatch(t::contains);
    }

    private static final Pattern SUFFIX = Pattern.compile(
            " (INC|INCORPORATED|LLC|L L C|LLP|LP|LTD|LIMITED|CORP|CORPORATION|CO|COMPANY|PLC|PC|NA|THE|HOLDINGS|GROUP)(?= )");

    /** Employer name reduced for matching: "The Goldman Sachs Group, Inc." to "GOLDMAN SACHS". */
    static String employerKey(String name) {
        String s = " " + name.toUpperCase(Locale.ROOT).replace("&", " AND ").replaceAll("[^A-Z0-9 ]", " ") + " ";
        s = s.replaceAll(" +", "  ");
        s = SUFFIX.matcher(s).replaceAll(" ");
        return s.trim().replaceAll(" +", " ");
    }
}
