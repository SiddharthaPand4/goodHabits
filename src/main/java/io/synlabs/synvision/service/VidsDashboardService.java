package io.synlabs.synvision.service;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQuery;
import io.synlabs.synvision.entity.atcc.QAtccEvent;
import io.synlabs.synvision.entity.vids.HighwayTrafficState;
import io.synlabs.synvision.entity.vids.QHighwayIncident;
import io.synlabs.synvision.enums.HighwayIncidentType;
import io.synlabs.synvision.jpa.HighwayTrafficStateRepository;
import io.synlabs.synvision.views.DashboardResponse;
import io.synlabs.synvision.views.vids.VidsDashboardResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Service
public class VidsDashboardService {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    HighwayTrafficStateRepository trafficStateRepository;

    public VidsDashboardResponse dashboardstats() {
        LocalDateTime localDateTime = LocalDateTime.now();
        Date now = Date.from( localDateTime.atZone( ZoneId.systemDefault()).toInstant());

        //hour atcc
        Date hr1 = Date.from( localDateTime.minusHours(1).atZone( ZoneId.systemDefault()).toInstant());

        //daily atcc from 6 am today
        Date startofday = Date.from( localDateTime.withHour(6).withMinute(0).withSecond(0).atZone( ZoneId.systemDefault()).toInstant());

        QAtccEvent rawData = QAtccEvent.atccEvent;
        JPAQuery<Tuple> query = new JPAQuery<>(entityManager);

        List<Tuple> result = query
                .select(
                        rawData.type,
                        rawData.count())
                .from(rawData)
                .where(rawData.eventDate.between(startofday, now))
                .groupBy(rawData.type)
                .fetch();

        List<DashboardResponse> todaystats = toList(result);

        result = query
                .select(
                        rawData.type,
                        rawData.count())
                .from(rawData)
                .where(rawData.eventDate.between(hr1, now))
                .groupBy(rawData.type)
                .fetch();

        List<DashboardResponse> onehourstats = toList(result);

        //current traffic status
        List<DashboardResponse> incidents = getIncidentStats(startofday, now);

        HighwayTrafficState state = trafficStateRepository.findFirstByOrderByUpdateDateDesc();
        return new VidsDashboardResponse(onehourstats, todaystats, incidents, state);
    }

    private List<DashboardResponse> getIncidentStats(Date startofday, Date now) {
        QHighwayIncident inci = QHighwayIncident.highwayIncident;
        JPAQuery<Tuple> query = new JPAQuery<>(entityManager);

        List<Tuple> result = query
                .select(
                        inci.incidentType,
                        inci.count())
                .from(inci)
                .where(inci.incidentDate.between(startofday, now))
                .groupBy(inci.incidentType)
                .fetch();

        List<DashboardResponse> stats = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            Tuple tuple = result.get(i);
            String vehicleType = tuple.get(0, HighwayIncidentType.class).name();
            Long vehicleCount = tuple.get(1, Long.class);
            stats.add(new DashboardResponse(vehicleType, Math.toIntExact(vehicleCount)));
            result.set(i, null);
        }
        return stats;
    }

    private List<DashboardResponse> toList(List<Tuple> result) {
        List<DashboardResponse> stats = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            Tuple tuple = result.get(i);
            String vehicleType = tuple.get(0, String.class);
            Long vehicleCount = tuple.get(1, Long.class);
            stats.add(new DashboardResponse(vehicleType, Math.toIntExact(vehicleCount)));
            result.set(i, null);
        }
        return stats;
    }
}