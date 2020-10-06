package io.synlabs.synvision.entity.avc;

import io.synlabs.synvision.enums.TimeSpan;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.domain.AbstractPersistable;

import javax.persistence.*;
import java.util.Date;

@Getter
@Setter
@Entity
public class AvcSummaryData extends AbstractPersistable<Long> {

    private String type;

    @Temporal(TemporalType.DATE)
    private Date date;

    @Temporal(TemporalType.TIME)
    @Column(name = "data_from")
    private Date from;

    @Temporal(TemporalType.TIME)
    @Column(name = "data_to")
    private Date to;

    @Enumerated
    private TimeSpan span;

    private int count;
    
}