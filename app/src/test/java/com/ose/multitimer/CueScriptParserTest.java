package com.ose.multitimer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

public final class CueScriptParserTest {
    @Test
    public void parsesPipeRowsWithSpeechAndOptionalBeep() {
        List<CueEvent> events = CueScriptParser.parse(
                "00:00 | say \"Start\" | beep\n"
                        + "00:30 | say \"Rest\"\n"
                        + "01:00 | beep 750",
                500L);

        assertEquals(3, events.size());
        assertEquals(0L, events.get(0).offsetMillis);
        assertEquals("Start", events.get(0).speechText);
        assertEquals(500L, events.get(0).beepDurationMillis);
        assertEquals("Rest", events.get(1).speechText);
        assertEquals(0L, events.get(1).beepDurationMillis);
        assertNull(events.get(2).speechText);
        assertEquals(750L, events.get(2).beepDurationMillis);
    }

    @Test
    public void parsesTomlLikeRowsAndSortsByAbsoluteTime() {
        List<CueEvent> events = CueScriptParser.parse(
                "[[event]]\n"
                        + "at = \"01:00\"\n"
                        + "say = \"Next set\"\n"
                        + "beep = true\n"
                        + "\n"
                        + "[[event]]\n"
                        + "at = \"00:30\"\n"
                        + "say = \"Rest\"\n",
                600L);

        assertEquals(2, events.size());
        assertEquals(30_000L, events.get(0).offsetMillis);
        assertEquals("Rest", events.get(0).speechText);
        assertEquals(60_000L, events.get(1).offsetMillis);
        assertEquals(600L, events.get(1).beepDurationMillis);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownActions() {
        CueScriptParser.parse("00:10 | vibrate", 500L);
    }
}
