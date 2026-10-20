package dev.kairos.proto.compat;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import dev.kairos.proto.v1.CommonProto;
import dev.kairos.proto.v1.DispatchProto;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProtoCompatibilityCheckTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Path current;
    private Path baseline;

    @BeforeEach
    void writeCurrentDescriptorSet() throws IOException {
        current = dir.resolve("current.binpb");
        baseline = dir.resolve("compat/kairos-v1.txtpb");
        Files.write(current, contracts(DispatchProto.getDescriptor().toProto()).toByteArray());
    }

    @Test
    void updateWritesABaselineWithoutSourceInfoThatThenPasses() throws IOException {
        assertThat(run("update", current, baseline)).isEqualTo(ProtoCompatibilityCheck.OK);

        String text = Files.readString(baseline);
        assertThat(text)
                .startsWith("# Released Kairos Protobuf contracts")
                .contains("name: \"kairos/v1/common.proto\"");
        assertThat(ProtoCompatibilityCheck.readText(baseline).getFile(0).hasSourceCodeInfo())
                .isFalse();
        assertThat(run("check", baseline, current)).isEqualTo(ProtoCompatibilityCheck.OK);
        assertThat(stdout()).contains("compatible with kairos-v1.txtpb");
    }

    @Test
    void checkFailsWhenAFieldNumberChanges() throws IOException {
        run("update", current, baseline);
        FileDescriptorProto dispatch = DispatchProto.getDescriptor().toProto();
        FileDescriptorProto.Builder mutated = dispatch.toBuilder();
        mutated.getMessageTypeBuilder(0).getFieldBuilder(2).setNumber(42);
        Files.write(current, contracts(mutated.build()).toByteArray());

        assertThat(run("check", baseline, current)).isEqualTo(ProtoCompatibilityCheck.INCOMPATIBLE);
        assertThat(stderr()).contains("field kairos.v1.Dispatch.fence (3) was renumbered to 42");
    }

    @Test
    void missingBaselineIsAUsageError() {
        assertThat(run("check", baseline, current)).isEqualTo(ProtoCompatibilityCheck.USAGE);
        assertThat(stderr()).contains("File not found");
    }

    @Test
    void corruptDescriptorIsAUsageError() throws IOException {
        run("update", current, baseline);
        Files.write(current, new byte[] {(byte) 0xff, 0x01});

        assertThat(run("check", baseline, current)).isEqualTo(ProtoCompatibilityCheck.USAGE);
        assertThat(stderr()).contains("Could not read or write a descriptor set");
    }

    @Test
    void wrongArgumentsAreUsageErrors() {
        assertThat(ProtoCompatibilityCheck.run(new String[] {"check"}, printer(out), printer(err)))
                .isEqualTo(ProtoCompatibilityCheck.USAGE);
        assertThat(ProtoCompatibilityCheck.run(new String[] {"compare", "a", "b"}, printer(out), printer(err)))
                .isEqualTo(ProtoCompatibilityCheck.USAGE);
        assertThat(stderr()).contains("Usage:").contains("Unknown command");
    }

    private int run(String command, Path first, Path second) {
        return ProtoCompatibilityCheck.run(
                new String[] {command, first.toString(), second.toString()}, printer(out), printer(err));
    }

    private static FileDescriptorSet contracts(FileDescriptorProto dispatch) {
        return FileDescriptorSet.newBuilder()
                .addFile(dispatch)
                .addFile(CommonProto.getDescriptor().toProto())
                .build();
    }

    private static PrintStream printer(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
