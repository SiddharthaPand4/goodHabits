package io.synlabs.synvision.views.hotlist;

import io.synlabs.synvision.views.incident.IncidentsFilterRequest;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HotListVehicleFilterRequest extends IncidentsFilterRequest {
    private String lpr;
}