package io.synlabs.synvision.jpa;

import io.synlabs.synvision.entity.anpr.AnprEvent;
import io.synlabs.synvision.entity.core.Org;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Date;
import java.util.List;

/**
 * Created by itrs on 10/21/2019.
 */
public interface AnprEventRepository extends JpaRepository<AnprEvent, Long> {

    List<AnprEvent> findAllByEventDateBetweenAndArchivedFalse(Date eventStartDate, Date eventEndDate, Pageable paging);

    int countAllByEventDateBetweenAndArchivedFalse(Date eventStartDate, Date eventEndDate);

    int countAllByEventDateAndArchivedFalse(Date from);

    int countAllByArchivedFalse();

    List<AnprEvent> findAllByArchivedFalse(Pageable paging);
}
