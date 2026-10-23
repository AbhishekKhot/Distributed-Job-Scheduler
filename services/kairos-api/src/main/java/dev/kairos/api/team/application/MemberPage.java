package dev.kairos.api.team.application;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One page of the member list.
 *
 * @param members the members of this page, newest first
 * @param next the key to continue after, or {@code null} on the last page
 */
public record MemberPage(List<Member> members, @Nullable MemberKey next) {

    /** Validates the components and copies the members. */
    public MemberPage {
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }
}
