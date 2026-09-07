package io.github.vihuynh72.brownie.spike.docxbinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MeetingMinutesData {

  private final Map<String, String> scalars = new LinkedHashMap<>();
  private final List<ActionItemData> actionItems = new ArrayList<>();

  public MeetingMinutesData put(String tag, String value) {
    scalars.put(tag, value);
    return this;
  }

  public MeetingMinutesData addActionItem(String task, String owner, String due) {
    actionItems.add(new ActionItemData(task, owner, due));
    return this;
  }

  public String get(String tag) {
    return asMap().get(tag);
  }

  public List<ActionItemData> actionItems() {
    return List.copyOf(actionItems);
  }

  /**
   * Flattens the scalar fields and the action-item list into one tag-to-value map, numbering
   * action items 1..N in list order (action.1.task, action.2.task, ...). This is the same shape
   * {@link FieldBinder#fillContentControls} already expects, whether those numbered tags came
   * from the fixed two-row comparison layout or from {@link RepeatingRegion}-cloned rows.
   */
  public Map<String, String> asMap() {
    Map<String, String> combined = new LinkedHashMap<>(scalars);
    for (int i = 0; i < actionItems.size(); i++) {
      ActionItemData item = actionItems.get(i);
      String prefix = "action." + (i + 1) + ".";
      combined.put(prefix + "task", item.task());
      combined.put(prefix + "owner", item.owner());
      combined.put(prefix + "due", item.due());
    }
    return combined;
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
        .addActionItem("Reserve the van for the regional competition", "Priya Rao", "March 20, 2026")
        .addActionItem("Confirm sponsor logo placement on the robot", "José Núñez", "March 18, 2026");
  }

  /** Same meeting scalars, but with {@code count} synthetic action items instead of two. */
  public static MeetingMinutesData sampleWithActionItemCount(int count) {
    MeetingMinutesData data = sample().withNoActionItems();
    for (int i = 1; i <= count; i++) {
      data.addActionItem("Synthetic task " + i, "Owner " + i, "2026-0" + (i % 9 + 1) + "-01");
    }
    return data;
  }

  /** Same meeting scalars, but with an empty action-item list, to exercise the empty-list case. */
  public static MeetingMinutesData sampleWithNoActionItems() {
    return sample().withNoActionItems();
  }

  /** The smallest realistic meeting: short strings everywhere, one short action item. */
  public static MeetingMinutesData sampleShort() {
    return new MeetingMinutesData()
        .put("meeting.title", "Weekly Sync")
        .put("meeting.organization", "Chess Club")
        .put("meeting.date", "March 5, 2026")
        .put("meeting.location", "Room 12")
        .put("meeting.attendees", "Sam, Lee")
        .put("meeting.decisions", "Meet again next week.")
        .addActionItem("Book the room", "Sam", "March 12, 2026");
  }

  /** Long-running text everywhere a field could wrap or overflow, to check flowing layout survives it. */
  public static MeetingMinutesData sampleLong() {
    String longDecision =
        "After extensive discussion covering the proposed budget increase, the club voted to approve"
            + " a 15% increase to the annual travel allocation, contingent on securing at least two"
            + " additional corporate sponsorships before the regional competition; the treasurer will"
            + " report back at the next meeting with an itemized breakdown of projected costs across"
            + " transportation, lodging, and registration fees for all five confirmed competitions this"
            + " season, including the newly added exhibition event in the neighboring district.";
    String longTask =
        "Draft, circulate for feedback, and finalize the itemized sponsorship proposal packet for the"
            + " three prospective corporate sponsors identified at this meeting, including the updated"
            + " budget breakdown and the club's competition schedule for the remainder of the season.";
    return new MeetingMinutesData()
        .put("meeting.title", "Annual Budget and Sponsorship Planning Session")
        .put("meeting.organization", "Riverside Robotics Club and Allied Engineering Society")
        .put("meeting.date", "March 12, 2026")
        .put("meeting.location", "Room 204, Student Union Building, West Wing")
        .put(
            "meeting.attendees",
            "Alex Chen, Priya Rao, José Núñez, Morgan Lee, Taylor Brooks, Jordan Kim, Casey Alvarez")
        .put("meeting.decisions", longDecision)
        .addActionItem(longTask, "Priya Rao", "March 20, 2026")
        .addActionItem("Confirm sponsor logo placement on the robot chassis and team uniforms", "José Núñez", "March 18, 2026");
  }

  /** Only the two fields the built-in templates require; every optional field is blank. */
  public static MeetingMinutesData sampleEmpty() {
    return new MeetingMinutesData()
        .put("meeting.title", "Officer Check-in")
        .put("meeting.organization", "")
        .put("meeting.date", "March 5, 2026")
        .put("meeting.location", "")
        .put("meeting.attendees", "")
        .put("meeting.decisions", "");
  }

  /** Several different accented and diacritic name forms, in both scalar fields and action-item cells. */
  public static MeetingMinutesData sampleAccentedNames() {
    return new MeetingMinutesData()
        .put("meeting.title", "International Exchange Planning")
        .put("meeting.organization", "Société Étudiante Internationale")
        .put("meeting.date", "March 12, 2026")
        .put("meeting.location", "Room 204, Student Union")
        .put("meeting.attendees", "José Núñez, Zoë Åström, François Müller, Renée Dubois")
        .put("meeting.decisions", "Approved the exchange itinerary as presented by Zoë Åström.")
        .addActionItem("Confirm visa paperwork", "François Müller", "March 20, 2026")
        .addActionItem("Book the São Paulo connecting flight", "Renée Dubois", "March 18, 2026");
  }

  private MeetingMinutesData withNoActionItems() {
    actionItems.clear();
    return this;
  }
}
