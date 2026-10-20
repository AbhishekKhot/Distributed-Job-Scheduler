package dev.kairos.proto.compat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares a released descriptor set (the baseline) with the current one and lists every change
 * that breaks consumers of messages written with the baseline.
 *
 * <p>The rules follow IMPLEMENTATION_CHECKLIST section 4.7: field and enum value numbers are never
 * reused or changed, and removed fields are reserved. A change is reported when:
 *
 * <ul>
 *   <li>a message or enum of the baseline no longer exists;
 *   <li>a field keeps its name but moves to another number (renumbered);
 *   <li>a field keeps its number but changes its name, type, message or enum type, or cardinality;
 *   <li>a field or enum value is removed without reserving both its number and its name;
 *   <li>a new field or enum value takes a number or name the baseline reserved;
 *   <li>an enum value keeps its number but changes its name.
 * </ul>
 *
 * <p>Adding messages, enums, fields and enum values is compatible. Comments and options are not
 * compared. This class is stateless and thread-safe.
 */
public final class ProtoCompatibility {

    private ProtoCompatibility() {}

    /**
     * Lists the breaking changes between two descriptor sets.
     *
     * @param baseline the released descriptor set
     * @param current the descriptor set built from the current sources
     * @return one human-readable line per breaking change, in a stable order; empty when the current
     *     set is compatible with the baseline
     */
    public static List<String> violations(FileDescriptorSet baseline, FileDescriptorSet current) {
        Map<String, DescriptorProto> currentMessages = new LinkedHashMap<>();
        Map<String, EnumDescriptorProto> currentEnums = new LinkedHashMap<>();
        index(current, currentMessages, currentEnums);
        Map<String, DescriptorProto> baselineMessages = new LinkedHashMap<>();
        Map<String, EnumDescriptorProto> baselineEnums = new LinkedHashMap<>();
        index(baseline, baselineMessages, baselineEnums);

        List<String> violations = new ArrayList<>();
        baselineMessages.forEach((name, old) -> {
            DescriptorProto now = currentMessages.get(name);
            if (now == null) {
                violations.add("message " + name + " was removed");
            } else {
                compareMessage(name, old, now, violations);
            }
        });
        baselineEnums.forEach((name, old) -> {
            EnumDescriptorProto now = currentEnums.get(name);
            if (now == null) {
                violations.add("enum " + name + " was removed");
            } else {
                compareEnum(name, old, now, violations);
            }
        });
        return List.copyOf(violations);
    }

    private static void index(
            FileDescriptorSet set, Map<String, DescriptorProto> messages, Map<String, EnumDescriptorProto> enums) {
        for (FileDescriptorProto file : set.getFileList()) {
            String prefix = file.getPackage().isEmpty() ? "" : file.getPackage() + ".";
            for (DescriptorProto message : file.getMessageTypeList()) {
                indexMessage(prefix + message.getName(), message, messages, enums);
            }
            for (EnumDescriptorProto enumType : file.getEnumTypeList()) {
                enums.put(prefix + enumType.getName(), enumType);
            }
        }
    }

    private static void indexMessage(
            String name,
            DescriptorProto message,
            Map<String, DescriptorProto> messages,
            Map<String, EnumDescriptorProto> enums) {
        messages.put(name, message);
        for (DescriptorProto nested : message.getNestedTypeList()) {
            indexMessage(name + "." + nested.getName(), nested, messages, enums);
        }
        for (EnumDescriptorProto nested : message.getEnumTypeList()) {
            enums.put(name + "." + nested.getName(), nested);
        }
    }

    private static void compareMessage(String name, DescriptorProto old, DescriptorProto now, List<String> out) {
        Map<Integer, FieldDescriptorProto> nowByNumber = new LinkedHashMap<>();
        Map<String, FieldDescriptorProto> nowByName = new LinkedHashMap<>();
        for (FieldDescriptorProto field : now.getFieldList()) {
            nowByNumber.put(field.getNumber(), field);
            nowByName.put(field.getName(), field);
        }
        for (FieldDescriptorProto oldField : old.getFieldList()) {
            String where = "field " + name + "." + oldField.getName() + " (" + oldField.getNumber() + ")";
            FieldDescriptorProto sameName = nowByName.get(oldField.getName());
            if (sameName != null && sameName.getNumber() != oldField.getNumber()) {
                out.add(where + " was renumbered to " + sameName.getNumber());
                continue;
            }
            FieldDescriptorProto sameNumber = nowByNumber.get(oldField.getNumber());
            if (sameNumber == null) {
                if (!isReservedField(now, oldField.getNumber())
                        || !now.getReservedNameList().contains(oldField.getName())) {
                    out.add(where + " was removed without reserving its number and name");
                }
                continue;
            }
            compareField(where, oldField, sameNumber, out);
        }
        for (FieldDescriptorProto field : now.getFieldList()) {
            if (isReservedField(old, field.getNumber())
                    || old.getReservedNameList().contains(field.getName())) {
                out.add("field " + name + "." + field.getName() + " (" + field.getNumber()
                        + ") reuses a reserved number or name");
            }
        }
    }

    private static void compareField(
            String where, FieldDescriptorProto old, FieldDescriptorProto now, List<String> out) {
        if (!old.getName().equals(now.getName())) {
            out.add(where + " was renamed to " + now.getName());
        }
        if (old.getType() != now.getType() || !old.getTypeName().equals(now.getTypeName())) {
            out.add(where + " changed type from " + describeType(old) + " to " + describeType(now));
        }
        if (old.getLabel() != now.getLabel() || old.getProto3Optional() != now.getProto3Optional()) {
            out.add(where + " changed cardinality");
        }
    }

    private static String describeType(FieldDescriptorProto field) {
        return field.getTypeName().isEmpty() ? field.getType().name() : field.getTypeName();
    }

    private static boolean isReservedField(DescriptorProto message, int number) {
        // Message reserved ranges are half-open: start inclusive, end exclusive.
        return message.getReservedRangeList().stream()
                .anyMatch(range -> number >= range.getStart() && number < range.getEnd());
    }

    private static void compareEnum(String name, EnumDescriptorProto old, EnumDescriptorProto now, List<String> out) {
        Map<Integer, EnumValueDescriptorProto> nowByNumber = new LinkedHashMap<>();
        for (EnumValueDescriptorProto value : now.getValueList()) {
            // Aliases (allow_alias) share a number; the first declaration is the canonical one.
            nowByNumber.putIfAbsent(value.getNumber(), value);
        }
        for (EnumValueDescriptorProto oldValue : old.getValueList()) {
            String where = "enum value " + name + "." + oldValue.getName() + " (" + oldValue.getNumber() + ")";
            EnumValueDescriptorProto sameNumber = nowByNumber.get(oldValue.getNumber());
            if (sameNumber == null) {
                if (!isReservedValue(now, oldValue.getNumber())
                        || !now.getReservedNameList().contains(oldValue.getName())) {
                    out.add(where + " was removed without reserving its number and name");
                }
            } else if (!sameNumber.getName().equals(oldValue.getName())) {
                out.add(where + " was renamed to " + sameNumber.getName());
            }
        }
        for (EnumValueDescriptorProto value : now.getValueList()) {
            if (isReservedValue(old, value.getNumber())
                    || old.getReservedNameList().contains(value.getName())) {
                out.add("enum value " + name + "." + value.getName() + " (" + value.getNumber()
                        + ") reuses a reserved number or name");
            }
        }
    }

    private static boolean isReservedValue(EnumDescriptorProto enumType, int number) {
        // Enum reserved ranges are closed: both ends inclusive.
        return enumType.getReservedRangeList().stream()
                .anyMatch(range -> number >= range.getStart() && number <= range.getEnd());
    }
}
