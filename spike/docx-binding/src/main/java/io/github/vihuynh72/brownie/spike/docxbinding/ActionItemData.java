package io.github.vihuynh72.brownie.spike.docxbinding;

/** One row of the action-item list: what, who, and by when. Any field may be blank but not null. */
record ActionItemData(String task, String owner, String due) {}
