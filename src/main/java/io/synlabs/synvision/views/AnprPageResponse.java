package io.synlabs.synvision.views;

import io.synlabs.synvision.views.common.PageResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by itrs on 10/21/2019.
 */
public class AnprPageResponse extends PageResponse {

    private List<AnprResponse> events = new ArrayList<>();

    public AnprPageResponse(int pageSize,int pageCount, int pageNumber, List<AnprResponse> anpr)
    {
        super(pageSize, pageCount, pageNumber);
        this.events = anpr;
    }

    public List<AnprResponse> getEvents() {
        return events;
    }
}
