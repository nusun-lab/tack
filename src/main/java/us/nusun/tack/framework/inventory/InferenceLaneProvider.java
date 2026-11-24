package us.nusun.tack.framework.inventory;

import us.nusun.tack.framework.model.InferenceLaneDefinition;

import java.util.List;

public interface InferenceLaneProvider {
    List<InferenceLaneDefinition> inferenceLanes();
}
