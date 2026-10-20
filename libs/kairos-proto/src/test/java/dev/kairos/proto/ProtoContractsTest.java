package dev.kairos.proto;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.DescriptorProtos.SourceCodeInfo.Location;
import dev.kairos.proto.compat.ProtoCompatibility;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the contract files as written: package and Java options, a comment on every message,
 * field, enum and enum value, and compatibility with the committed baseline. The build passes the
 * descriptor set protoc generated (with comments) and the baseline path as system properties.
 */
class ProtoContractsTest {

    // Paths in SourceCodeInfo locations (descriptor.proto field numbers).
    private static final int FILE_MESSAGE_TYPE = 4;
    private static final int FILE_ENUM_TYPE = 5;
    private static final int MESSAGE_FIELD = 2;
    private static final int MESSAGE_ENUM_TYPE = 4;
    private static final int ENUM_VALUE = 2;

    private static FileDescriptorSet contracts = FileDescriptorSet.getDefaultInstance();

    @BeforeAll
    static void readDescriptorSet() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of(System.getProperty("kairos.proto.descriptorSet")))) {
            contracts = FileDescriptorSet.parseFrom(in);
        }
    }

    @Test
    void containsEveryContractFile() {
        assertThat(contracts.getFileList())
                .extracting(FileDescriptorProto::getName)
                .containsExactlyInAnyOrder(
                        "kairos/v1/common.proto",
                        "kairos/v1/dispatch.proto",
                        "kairos/v1/result.proto",
                        "kairos/v1/cancel.proto",
                        "kairos/v1/completed.proto",
                        "kairos/v1/dead.proto");
    }

    @Test
    void everyFileUsesTheVersionedPackageAndJavaOptions() {
        assertThat(contracts.getFileList()).allSatisfy(file -> {
            assertThat(file.getSyntax()).isEqualTo("proto3");
            assertThat(file.getPackage()).isEqualTo("kairos.v1");
            assertThat(file.getOptions().getJavaPackage()).isEqualTo("dev.kairos.proto.v1");
            assertThat(file.getOptions().getJavaMultipleFiles()).isTrue();
        });
    }

    @Test
    void everyMessageFieldEnumAndValueHasAComment() {
        List<String> undocumented = new ArrayList<>();
        for (FileDescriptorProto file : contracts.getFileList()) {
            Map<List<Integer>, Location> locations = new HashMap<>();
            for (Location location : file.getSourceCodeInfo().getLocationList()) {
                locations.put(location.getPathList(), location);
            }
            for (int m = 0; m < file.getMessageTypeCount(); m++) {
                checkMessage(file.getMessageType(m), List.of(FILE_MESSAGE_TYPE, m), locations, undocumented);
            }
            for (int e = 0; e < file.getEnumTypeCount(); e++) {
                checkEnum(file.getEnumType(e), List.of(FILE_ENUM_TYPE, e), locations, undocumented);
            }
        }
        assertThat(undocumented).as("elements without a leading comment").isEmpty();
    }

    @Test
    void currentContractsAreCompatibleWithTheCommittedBaseline() throws IOException {
        FileDescriptorSet.Builder baseline = FileDescriptorSet.newBuilder();
        try (Reader reader =
                Files.newBufferedReader(Path.of(System.getProperty("kairos.proto.baseline")), StandardCharsets.UTF_8)) {
            com.google.protobuf.TextFormat.getParser().merge(reader, baseline);
        }

        assertThat(ProtoCompatibility.violations(baseline.build(), contracts)).isEmpty();
    }

    private static void checkMessage(
            DescriptorProto message, List<Integer> path, Map<List<Integer>, Location> locations, List<String> out) {
        requireComment(message.getName(), path, locations, out);
        for (int f = 0; f < message.getFieldCount(); f++) {
            requireComment(
                    message.getName() + "." + message.getField(f).getName(),
                    append(path, MESSAGE_FIELD, f),
                    locations,
                    out);
        }
        for (int e = 0; e < message.getEnumTypeCount(); e++) {
            checkEnum(message.getEnumType(e), append(path, MESSAGE_ENUM_TYPE, e), locations, out);
        }
        // Nested messages are only the synthetic map entry types, which have no source of their own.
    }

    private static void checkEnum(
            EnumDescriptorProto enumType,
            List<Integer> path,
            Map<List<Integer>, Location> locations,
            List<String> out) {
        requireComment(enumType.getName(), path, locations, out);
        for (int v = 0; v < enumType.getValueCount(); v++) {
            requireComment(
                    enumType.getName() + "." + enumType.getValue(v).getName(),
                    append(path, ENUM_VALUE, v),
                    locations,
                    out);
        }
    }

    private static void requireComment(
            String element, List<Integer> path, Map<List<Integer>, Location> locations, List<String> out) {
        Location location = locations.get(path);
        if (location == null || location.getLeadingComments().isBlank()) {
            out.add(element);
        }
    }

    private static List<Integer> append(List<Integer> path, int kind, int index) {
        List<Integer> result = new ArrayList<>(path);
        result.add(kind);
        result.add(index);
        return List.copyOf(result);
    }
}
