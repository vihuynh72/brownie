package io.github.vihuynh72.brownie.spike.docxbinding;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MeetingMinutesData {

  private final Map<String, String> values = new LinkedHashMap<>();

  public MeetingMinutesData put(String tag, String value) {
    values.put(tag, value);
    return this;
  }

  public String get(String tag) {
    return values.get(tag);
  }

  public Map<String, String> asMap() {
    return values;
  }

  /** One synthetic case: an accented attendee name and a two-row action list. */
  public static MeetingMinutesData sample() {
    return new MeetingMinutesData()
        .put("meeting.title", "Spring Budget Planning")
        .put("meeting.organization", "Riverside Robotics Club")
        .put("meeting.date", "March 12, 2026")
        .put("meeting.location", "Room 204, Student Union")
        .put("meeting.attendees", "Alex Chen, Priya Rao, José Núñez")
        .put("meeting.decisions", "Approved the spring competition travel budget as proposed.")
        .put("action.1.task", "Reserve the van for the regional competition")
        .put("action.1.owner", "Priya Rao")
        .put("action.1.due", "March 20, 2026")
        .put("action.2.task", "Confirm sponsor logo placement on the robot")
        .put("action.2.owner", "José Núñez")
        .put("action.2.due", "March 18, 2026");
  }
}
