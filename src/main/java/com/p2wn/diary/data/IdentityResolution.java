package com.p2wn.diary.data;

import java.util.UUID;

public record IdentityResolution(Status status, UUID uuid) {
    public enum Status { FOUND, NOT_FOUND, AMBIGUOUS }
    public static IdentityResolution found(UUID uuid) { return new IdentityResolution(Status.FOUND, uuid); }
    public static IdentityResolution notFound() { return new IdentityResolution(Status.NOT_FOUND, null); }
    public static IdentityResolution ambiguous() { return new IdentityResolution(Status.AMBIGUOUS, null); }
}
