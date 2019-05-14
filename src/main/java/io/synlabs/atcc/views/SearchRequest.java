package io.synlabs.atcc.views;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class SearchRequest {
    private Integer page;
    private Integer pageSize;
    private List<SortRequest> sorted = new ArrayList<>();
    private List<String> filtered = new ArrayList<>();

    @Override
    public String toString() {
        return "SearchRequest{" +
                "page='" + page + '\'' +
                ", pageSize='" + pageSize + '\'' +
                ", sorted='" + sorted + '\'' +
                ", filtered='" + filtered + '\'' +
                '}';
    }
}
