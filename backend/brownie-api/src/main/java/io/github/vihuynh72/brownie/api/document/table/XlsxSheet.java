package io.github.vihuynh72.brownie.api.document.table;

/**
 * One sheet of a workbook as its workbook.xml names it: the name a person
 * sees, the relationship id the reader uses to find the sheet's part, and
 * whether the workbook keeps it hidden (hidden or very hidden count alike --
 * a person choosing a sheet by name may still read either).
 */
record XlsxSheet(String name, String relationshipId, boolean hidden) {}
