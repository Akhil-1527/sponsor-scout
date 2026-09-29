package io.github.akhil1527.sponsorscout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SponsorScoutApplication {

    public static void main(String[] args) throws Exception {
        Path db = Path.of(System.getenv().getOrDefault("SCOUT_DB", "data/scout.db")).toAbsolutePath();
        Files.createDirectories(db.getParent());
        if (args.length > 1 && args[0].equals("ingest")) {
            LcaIngest.run("jdbc:sqlite:" + db, Arrays.stream(args).skip(1).map(Path::of).toList());
            return;
        }
        SpringApplication.run(SponsorScoutApplication.class, args);
    }
}
