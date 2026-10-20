package dev.kairos.proto.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.DescriptorProto.ReservedRange;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto.EnumReservedRange;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import dev.kairos.proto.v1.DispatchProto;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ProtoCompatibilityTest {

    private static final FieldDescriptorProto RUN_ID = field("run_id", 1, FieldDescriptorProto.Type.TYPE_BYTES);
    private static final FieldDescriptorProto ATTEMPT = field("attempt", 2, FieldDescriptorProto.Type.TYPE_INT32);

    @Test
    void identicalSetsAreCompatible() {
        FileDescriptorSet set = set(message(RUN_ID, ATTEMPT), outcome("SUCCEEDED", 1));

        assertThat(ProtoCompatibility.violations(set, set)).isEmpty();
    }

    @Test
    void addingFieldsMessagesAndValuesIsCompatible() {
        FileDescriptorSet baseline = set(message(RUN_ID), outcome("SUCCEEDED", 1));
        FileDescriptorSet current = FileDescriptorSet.newBuilder()
                .addFile(file(message(RUN_ID, ATTEMPT), outcome("SUCCEEDED", 1, "FAILED", 2)).toBuilder()
                        .addMessageType(DescriptorProto.newBuilder().setName("Extra")))
                .build();

        assertThat(ProtoCompatibility.violations(baseline, current)).isEmpty();
    }

    @Test
    void renumberedFieldIsReported() {
        FileDescriptorSet baseline = set(message(RUN_ID, ATTEMPT));
        FileDescriptorSet current = set(message(RUN_ID, field("attempt", 3, FieldDescriptorProto.Type.TYPE_INT32)));

        assertThat(ProtoCompatibility.violations(baseline, current))
                .containsExactly("field kairos.v1.Dispatch.attempt (2) was renumbered to 3");
    }

    @Test
    void changingAFieldNumberOfTheRealDispatchContractIsReported() {
        FileDescriptorProto dispatch = DispatchProto.getDescriptor().toProto();
        FileDescriptorProto mutated = mutateMessage(
                dispatch,
                "Dispatch",
                message -> message.toBuilder()
                        .setField(0, message.getField(0).toBuilder().setNumber(99))
                        .build());

        assertThat(ProtoCompatibility.violations(
                        FileDescriptorSet.newBuilder().addFile(dispatch).build(),
                        FileDescriptorSet.newBuilder().addFile(mutated).build()))
                .containsExactly("field kairos.v1.Dispatch.run_id (1) was renumbered to 99");
    }

    @Test
    void removedFieldWithoutReservationIsReported() {
        FileDescriptorSet baseline = set(message(RUN_ID, ATTEMPT));
        FileDescriptorSet current = set(message(RUN_ID));

        assertThat(ProtoCompatibility.violations(baseline, current))
                .containsExactly(
                        "field kairos.v1.Dispatch.attempt (2) was removed without reserving its number and name");
    }

    @Test
    void removedFieldReservingOnlyItsNumberIsReported() {
        FileDescriptorSet baseline = set(message(RUN_ID, ATTEMPT));
        FileDescriptorSet current = set(message(RUN_ID).toBuilder()
                .addReservedRange(ReservedRange.newBuilder().setStart(2).setEnd(3))
                .build());

        assertThat(ProtoCompatibility.violations(baseline, current)).hasSize(1);
    }

    @Test
    void removedFieldReservingNumberAndNameIsCompatible() {
        FileDescriptorSet baseline = set(message(RUN_ID, ATTEMPT));
        FileDescriptorSet current = set(message(RUN_ID).toBuilder()
                .addReservedRange(ReservedRange.newBuilder().setStart(2).setEnd(3))
                .addReservedName("attempt")
                .build());

        assertThat(ProtoCompatibility.violations(baseline, current)).isEmpty();
    }

    @Test
    void reusingAReservedNumberOrNameIsReported() {
        DescriptorProto reserved = message(RUN_ID).toBuilder()
                .addReservedRange(ReservedRange.newBuilder().setStart(2).setEnd(3))
                .addReservedName("attempt")
                .build();
        FileDescriptorSet baseline = set(reserved);
        FileDescriptorSet reusedNumber = set(reserved.toBuilder()
                .addField(field("fence", 2, FieldDescriptorProto.Type.TYPE_INT32))
                .build());
        FileDescriptorSet reusedName = set(reserved.toBuilder()
                .addField(field("attempt", 5, FieldDescriptorProto.Type.TYPE_INT32))
                .build());

        assertThat(ProtoCompatibility.violations(baseline, reusedNumber))
                .containsExactly("field kairos.v1.Dispatch.fence (2) reuses a reserved number or name");
        assertThat(ProtoCompatibility.violations(baseline, reusedName))
                .containsExactly("field kairos.v1.Dispatch.attempt (5) reuses a reserved number or name");
    }

    @Test
    void renamedFieldIsReported() {
        FileDescriptorSet baseline = set(message(RUN_ID, ATTEMPT));
        FileDescriptorSet current = set(message(RUN_ID, field("try", 2, FieldDescriptorProto.Type.TYPE_INT32)));

        assertThat(ProtoCompatibility.violations(baseline, current))
                .containsExactly("field kairos.v1.Dispatch.attempt (2) was renamed to try");
    }

    @Test
    void retypedFieldIsReported() {
        FileDescriptorSet baseline = set(message(RUN_ID, ATTEMPT));
        FileDescriptorSet current = set(message(RUN_ID, field("attempt", 2, FieldDescriptorProto.Type.TYPE_INT64)));

        assertThat(ProtoCompatibility.violations(baseline, current))
                .containsExactly("field kairos.v1.Dispatch.attempt (2) changed type from TYPE_INT32 to TYPE_INT64");
    }

    @Test
    void changedMessageTypeIsReported() {
        FieldDescriptorProto request = field("request", 3, FieldDescriptorProto.Type.TYPE_MESSAGE).toBuilder()
                .setTypeName(".kairos.v1.RequestSnapshot")
                .build();
        FieldDescriptorProto other =
                request.toBuilder().setTypeName(".kairos.v1.Other").build();

        assertThat(ProtoCompatibility.violations(set(message(request)), set(message(other))))
                .containsExactly("field kairos.v1.Dispatch.request (3) changed type from"
                        + " .kairos.v1.RequestSnapshot to .kairos.v1.Other");
    }

    @Test
    void changedCardinalityIsReported() {
        FileDescriptorSet baseline = set(message(RUN_ID, ATTEMPT));
        FileDescriptorSet repeated = set(message(
                RUN_ID,
                ATTEMPT.toBuilder()
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)
                        .build()));
        FileDescriptorSet optional =
                set(message(RUN_ID, ATTEMPT.toBuilder().setProto3Optional(true).build()));

        assertThat(ProtoCompatibility.violations(baseline, repeated))
                .containsExactly("field kairos.v1.Dispatch.attempt (2) changed cardinality");
        assertThat(ProtoCompatibility.violations(baseline, optional))
                .containsExactly("field kairos.v1.Dispatch.attempt (2) changed cardinality");
    }

    @Test
    void removedMessageAndEnumAreReported() {
        FileDescriptorSet baseline = set(message(RUN_ID), outcome("SUCCEEDED", 1));
        FileDescriptorSet current = FileDescriptorSet.newBuilder()
                .addFile(FileDescriptorProto.newBuilder()
                        .setName("kairos/v1/x.proto")
                        .setPackage("kairos.v1"))
                .build();

        assertThat(ProtoCompatibility.violations(baseline, current))
                .containsExactly("message kairos.v1.Dispatch was removed", "enum kairos.v1.Outcome was removed");
    }

    @Test
    void nestedTypesAreCompared() {
        DescriptorProto nested = DescriptorProto.newBuilder()
                .setName("Inner")
                .addField(field("value", 1, FieldDescriptorProto.Type.TYPE_STRING))
                .addEnumType(EnumDescriptorProto.newBuilder().setName("Kind").addValue(value("KIND_UNSPECIFIED", 0)))
                .build();
        FileDescriptorSet baseline =
                set(message(RUN_ID).toBuilder().addNestedType(nested).build());
        FileDescriptorSet current = set(message(RUN_ID).toBuilder()
                .addNestedType(DescriptorProto.newBuilder().setName("Inner"))
                .build());

        assertThat(ProtoCompatibility.violations(baseline, current))
                .containsExactly(
                        "field kairos.v1.Dispatch.Inner.value (1) was removed without reserving its number and name",
                        "enum kairos.v1.Dispatch.Inner.Kind was removed");
    }

    @Test
    void enumValueChangesAreReported() {
        FileDescriptorSet baseline = set(message(RUN_ID), outcome("SUCCEEDED", 1, "FAILED", 2));

        assertThat(ProtoCompatibility.violations(baseline, set(message(RUN_ID), outcome("SUCCEEDED", 1))))
                .containsExactly(
                        "enum value kairos.v1.Outcome.FAILED (2) was removed without reserving its number and name");
        assertThat(ProtoCompatibility.violations(baseline, set(message(RUN_ID), outcome("SUCCEEDED", 1, "BROKEN", 2))))
                .containsExactly("enum value kairos.v1.Outcome.FAILED (2) was renamed to BROKEN");
    }

    @Test
    void reservedEnumValuesAreHonoured() {
        EnumDescriptorProto reserved = outcome("SUCCEEDED", 1).toBuilder()
                .addReservedRange(EnumReservedRange.newBuilder().setStart(2).setEnd(2))
                .addReservedName("FAILED")
                .build();
        FileDescriptorSet baseline = set(message(RUN_ID), outcome("SUCCEEDED", 1, "FAILED", 2));

        assertThat(ProtoCompatibility.violations(baseline, set(message(RUN_ID), reserved)))
                .isEmpty();
        assertThat(ProtoCompatibility.violations(
                        set(message(RUN_ID), reserved),
                        set(
                                message(RUN_ID),
                                reserved.toBuilder()
                                        .addValue(value("RETRYING", 2))
                                        .build())))
                .containsExactly("enum value kairos.v1.Outcome.RETRYING (2) reuses a reserved number or name");
    }

    @Test
    void enumAliasesUseTheFirstDeclaration() {
        EnumDescriptorProto aliased = outcome("SUCCEEDED", 1, "OK", 1);

        assertThat(ProtoCompatibility.violations(
                        set(message(RUN_ID), outcome("SUCCEEDED", 1)), set(message(RUN_ID), aliased)))
                .isEmpty();
    }

    @Test
    void filesWithoutPackageAreIndexedByName() {
        FileDescriptorSet baseline = FileDescriptorSet.newBuilder()
                .addFile(FileDescriptorProto.newBuilder().setName("x.proto").addMessageType(message(RUN_ID)))
                .build();

        assertThat(ProtoCompatibility.violations(baseline, FileDescriptorSet.getDefaultInstance()))
                .containsExactly("message Dispatch was removed");
    }

    private static FileDescriptorProto mutateMessage(
            FileDescriptorProto file, String name, UnaryOperator<DescriptorProto> change) {
        FileDescriptorProto.Builder builder = file.toBuilder();
        for (int i = 0; i < builder.getMessageTypeCount(); i++) {
            if (builder.getMessageType(i).getName().equals(name)) {
                builder.setMessageType(i, change.apply(builder.getMessageType(i)));
            }
        }
        return builder.build();
    }

    private static FieldDescriptorProto field(String name, int number, FieldDescriptorProto.Type type) {
        return FieldDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .setType(type)
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                .build();
    }

    private static DescriptorProto message(FieldDescriptorProto... fields) {
        DescriptorProto.Builder builder = DescriptorProto.newBuilder().setName("Dispatch");
        for (FieldDescriptorProto field : fields) {
            builder.addField(field);
        }
        return builder.build();
    }

    private static EnumValueDescriptorProto value(String name, int number) {
        return EnumValueDescriptorProto.newBuilder()
                .setName(name)
                .setNumber(number)
                .build();
    }

    private static EnumDescriptorProto outcome(Object... namesAndNumbers) {
        EnumDescriptorProto.Builder builder = EnumDescriptorProto.newBuilder().setName("Outcome");
        for (int i = 0; i < namesAndNumbers.length; i += 2) {
            builder.addValue(value((String) namesAndNumbers[i], (Integer) namesAndNumbers[i + 1]));
        }
        return builder.build();
    }

    private static FileDescriptorProto file(DescriptorProto message, EnumDescriptorProto... enums) {
        FileDescriptorProto.Builder builder = FileDescriptorProto.newBuilder()
                .setName("kairos/v1/dispatch.proto")
                .setPackage("kairos.v1")
                .addMessageType(message);
        for (EnumDescriptorProto enumType : enums) {
            builder.addEnumType(enumType);
        }
        return builder.build();
    }

    private static FileDescriptorSet set(DescriptorProto message, EnumDescriptorProto... enums) {
        return FileDescriptorSet.newBuilder().addFile(file(message, enums)).build();
    }
}
