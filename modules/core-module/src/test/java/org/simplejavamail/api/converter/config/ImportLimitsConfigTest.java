package org.simplejavamail.api.converter.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportLimitsConfigTest {

    @Test
    void emptyChoicesInheritAndInspectionDoesNotResolveTheUnlimitedFallback() {
        final ImportLimitsConfig.Builder choices = ImportLimitsConfig.builder();
        assertThat(choices.getMaximumInputBytes()).isNull();
        assertThat(choices.getMaximumPartCount()).isNull();
        assertThat(choices.getMaximumNestingDepth()).isNull();
        final ImportLimitsConfig value = choices.build();
        assertThat(value.getMaximumInputBytes()).isNull();
        assertThat(value.getMaximumPartCount()).isNull();
        assertThat(value.getMaximumNestingDepth()).isNull();
    }

    @Test
    void resetsSelectExplicitUnlimitedWhileRestorationReturnsToInheritance() {
        final ImportLimitsConfig.Builder choices = ImportLimitsConfig.builder()
                .withMaximumInputBytes(25_000_000L).withMaximumPartCount(1000).withMaximumNestingDepth(30);
        choices.resetMaximumInputBytes().resetMaximumPartCount().resetMaximumNestingDepth();
        assertThat(choices.getMaximumInputBytes()).isEqualTo(ImportLimitsConfig.Builder.DEFAULT_MAXIMUM_INPUT_BYTES).isZero();
        assertThat(choices.getMaximumPartCount()).isEqualTo(ImportLimitsConfig.Builder.DEFAULT_MAXIMUM_PART_COUNT).isZero();
        assertThat(choices.getMaximumNestingDepth()).isEqualTo(ImportLimitsConfig.Builder.DEFAULT_MAXIMUM_NESTING_DEPTH).isZero();
        final ImportLimitsConfig unlimited = choices.build();
        choices.useConfiguredMaximumInputBytes().useConfiguredMaximumPartCount().useConfiguredMaximumNestingDepth();
        assertThat(choices.build()).isEqualTo(ImportLimitsConfig.builder().build());
        assertThat(unlimited.getMaximumInputBytes()).isZero();
        assertThat(unlimited.getMaximumPartCount()).isZero();
        assertThat(unlimited.getMaximumNestingDepth()).isZero();
    }

    @Test
    void toBuilderMakesIndependentChoicesAndPreservesTheOtherMembers() {
        final ImportLimitsConfig original = ImportLimitsConfig.builder().withMaximumInputBytes(Long.MAX_VALUE)
                .withMaximumPartCount(1).withMaximumNestingDepth(0).build();
        final ImportLimitsConfig changed = original.toBuilder().useConfiguredMaximumPartCount().withMaximumNestingDepth(5).build();
        assertThat(changed.getMaximumInputBytes()).isEqualTo(Long.MAX_VALUE);
        assertThat(changed.getMaximumPartCount()).isNull();
        assertThat(changed.getMaximumNestingDepth()).isEqualTo(5);
        assertThat(original.getMaximumPartCount()).isEqualTo(1);
        assertThat(original.getMaximumNestingDepth()).isZero();
    }

    @Test
    void unfinishedNegativeChoicesRemainInspectableButCannotBecomeAValue() {
        final ImportLimitsConfig.Builder bytes = ImportLimitsConfig.builder().withMaximumInputBytes(-1);
        assertThat(bytes.getMaximumInputBytes()).isEqualTo(-1);
        assertThatThrownBy(bytes::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximumInputBytes");
        final ImportLimitsConfig.Builder parts = ImportLimitsConfig.builder().withMaximumPartCount(-1);
        assertThat(parts.getMaximumPartCount()).isEqualTo(-1);
        assertThatThrownBy(parts::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximumPartCount");
        final ImportLimitsConfig.Builder depth = ImportLimitsConfig.builder().withMaximumNestingDepth(-1);
        assertThat(depth.getMaximumNestingDepth()).isEqualTo(-1);
        assertThatThrownBy(depth::build).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maximumNestingDepth");
        assertThat(bytes.withMaximumInputBytes(0).build().getMaximumInputBytes()).isZero();
    }

    @Test
    void serializationKeepsInheritedUnlimitedAndPositiveChoicesDistinct() throws Exception {
        final ImportLimitsConfig original = ImportLimitsConfig.builder().withMaximumInputBytes(0)
                .withMaximumNestingDepth(Integer.MAX_VALUE).build();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        final ImportLimitsConfig restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (ImportLimitsConfig) input.readObject();
        }
        assertThat(restored).isEqualTo(original).isNotSameAs(original);
        assertThat(restored.getMaximumInputBytes()).isZero();
        assertThat(restored.getMaximumPartCount()).isNull();
        assertThat(restored.getMaximumNestingDepth()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void restorationRejectsNegativeChoicesThatBypassedConstruction() throws Exception {
        final ImportLimitsConfig invalid = ImportLimitsConfig.builder().withMaximumInputBytes(25).build();
        final java.lang.reflect.Field field = ImportLimitsConfig.class.getDeclaredField("maximumInputBytes");
        field.setAccessible(true);
        field.set(invalid, -1L);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(invalid);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThatThrownBy(input::readObject).isInstanceOf(java.io.InvalidObjectException.class)
                    .hasMessageContaining("maximumInputBytes").hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
