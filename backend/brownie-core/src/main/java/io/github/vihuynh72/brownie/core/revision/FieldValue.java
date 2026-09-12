package io.github.vihuynh72.brownie.core.revision;

import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldType;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The complete set of currently supported field values. The value itself
 * carries both type and cardinality so it can be checked against a template
 * definition before it is persisted.
 */
public sealed interface FieldValue permits FieldValue.TextValue, FieldValue.DateValue,
        FieldValue.RepeatedTextValue, FieldValue.RepeatedDateValue {

    FieldType type();

    FieldCardinality cardinality();

    record TextValue(String value) implements FieldValue {

        public TextValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public FieldType type() {
            return FieldType.TEXT;
        }

        @Override
        public FieldCardinality cardinality() {
            return FieldCardinality.SCALAR;
        }
    }

    record DateValue(LocalDate value) implements FieldValue {

        public DateValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public FieldType type() {
            return FieldType.DATE;
        }

        @Override
        public FieldCardinality cardinality() {
            return FieldCardinality.SCALAR;
        }
    }

    record RepeatedTextValue(List<String> values) implements FieldValue {

        public RepeatedTextValue {
            values = List.copyOf(values);
        }

        @Override
        public FieldType type() {
            return FieldType.TEXT;
        }

        @Override
        public FieldCardinality cardinality() {
            return FieldCardinality.REPEATED;
        }
    }

    record RepeatedDateValue(List<LocalDate> values) implements FieldValue {

        public RepeatedDateValue {
            values = List.copyOf(values);
        }

        @Override
        public FieldType type() {
            return FieldType.DATE;
        }

        @Override
        public FieldCardinality cardinality() {
            return FieldCardinality.REPEATED;
        }
    }
}
