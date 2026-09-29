package io.github.akhil1527.sponsorscout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FiltersTest {

    @Test
    void usLocations() {
        assertTrue(Filters.isUs("Austin, TX"));
        assertTrue(Filters.isUs("Remote - United States"));
        assertTrue(Filters.isUs("Indianapolis, IN"));
        assertTrue(Filters.isUs("Indiana"));
        assertTrue(Filters.isUs("US-CA-Dublin"));
        assertFalse(Filters.isUs("IN-Pune"));
        assertFalse(Filters.isUs("London, UK"));
        assertFalse(Filters.isUs("Toronto, Canada"));
        assertFalse(Filters.isUs("Bengaluru"));
        assertFalse(Filters.isUs("Remote"));
        assertFalse(Filters.isUs(null));
    }

    @Test
    void stateMatching() {
        assertEquals("TX", Filters.stateCode("texas"));
        assertEquals("NY", Filters.stateCode("ny"));
        assertNull(Filters.stateCode("ontario"));
        assertTrue(Filters.inState("Austin, TX", "Texas"));
        assertTrue(Filters.inState("Remote - US", "TX"));
        assertFalse(Filters.inState("New York, NY", "TX"));
    }

    @Test
    void sponsorshipLanguage() {
        assertTrue(Filters.noSponsor("We are unable to sponsor visas for this role."));
        assertTrue(Filters.noSponsor("Must be a U.S. citizen."));
        assertTrue(Filters.noSponsor("Candidates must be authorized to work without sponsorship."));
        assertFalse(Filters.noSponsor("Visa sponsorship is available for this position."));
        assertFalse(Filters.noSponsor("No sponsorship needed? Great, apply anyway."));
    }

    @Test
    void yearsOfExperience() {
        assertEquals(5, Filters.minYears("5+ years of experience with Java and 3 years of experience in AWS"));
        assertEquals(2, Filters.minYears("2-4 years of professional experience"));
        assertNull(Filters.minYears("Great team, strong mentorship."));
    }

    @Test
    void levelAndRole() {
        assertFalse(Filters.fitsLevel("Senior Software Engineer", 2));
        assertTrue(Filters.fitsLevel("Senior Software Engineer", 6));
        assertFalse(Filters.fitsLevel("Staff Software Engineer", 6));
        assertFalse(Filters.fitsLevel("Software Engineer Intern", 4));
        assertTrue(Filters.fitsLevel("Software Engineer Intern", 0));
        assertTrue(Filters.fitsLevel("Internal Tools Engineer", 4));
        assertTrue(Filters.matchesRole("Java Full Stack Developer", "java developer"));
        assertFalse(Filters.matchesRole("JavaScript Engineer", "java developer"));
    }

    @Test
    void employerKeys() {
        assertEquals("STRIPE", Filters.employerKey("Stripe, Inc."));
        assertEquals("GOLDMAN SACHS", Filters.employerKey("The Goldman Sachs Group, Inc."));
        assertEquals("AMAZON COM SERVICES", Filters.employerKey("Amazon.com Services LLC"));
        assertEquals("AT AND T SERVICES", Filters.employerKey("AT&T Services, Inc."));
        assertEquals("Citibank", Filters.spokenName("Citibank, N.A."));
        assertEquals("Capital One Services", Filters.spokenName("Capital One Services, LLC"));
        assertEquals("Stripe", Filters.spokenName("Stripe, Inc."));
        assertEquals("Capital One", Filters.spokenName("Capital One, National Association"));
    }
}
