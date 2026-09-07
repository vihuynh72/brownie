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

  private MeetingMinutesData withNoActionItems() {
    actionItems.clear();
    return this;
  }
}
