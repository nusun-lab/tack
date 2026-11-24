package us.nusun.tack.framework.model;

public record MissionRunCapacity(
        String missionId,
        int maximum,
        int active,
        int remaining
) {
    public MissionRunCapacity {
        if (missionId == null || missionId.isBlank()) {
            throw new IllegalArgumentException("MissionRunCapacity missing missionId");
        }
        if (maximum < 0) {
            throw new IllegalArgumentException("MissionRunCapacity maximum must be non-negative");
        }
        if (active < 0) {
            throw new IllegalArgumentException("MissionRunCapacity active count must be non-negative");
        }
        remaining = Math.max(0, maximum - active);
    }

    public boolean full() {
        return active >= maximum;
    }
}
