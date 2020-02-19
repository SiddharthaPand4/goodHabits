package io.synlabs.synvision.service;

import com.google.gson.Gson;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import io.synlabs.synvision.entity.anpr.*;
import io.synlabs.synvision.entity.core.Feed;
import io.synlabs.synvision.entity.parking.ParkingEvent;
import io.synlabs.synvision.entity.parking.QParkingEvent;
import io.synlabs.synvision.enums.VehicleType;
import io.synlabs.synvision.jpa.*;
import io.synlabs.synvision.views.anpr.*;
import io.synlabs.synvision.views.common.PageResponse;
import io.synlabs.synvision.views.parking.ParkingEventResponse;
import io.synlabs.synvision.views.parking.ParkingReportRequest;
import io.synlabs.synvision.views.parking.ParkingReportResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.persistence.EntityManager;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.data.domain.Sort.Direction.DESC;

/**
 * Created by itrs on 10/21/2019.
 */
@Service
public class AnprService extends BaseService {

    @Autowired
    private AnprEventRepository anprEventRepository;

    @Autowired
    private HotListVehicleRepository hotListVehicleRepository;

    @Autowired
    private ParkingEventRepository parkingEventRepository;

    @Autowired
    private SpeedSectionRepository speedSectionRepository;

    @Autowired
    private FeedRepository feedRepository;

    @Autowired
    private EntityManager entityManager;

    @Value("${file.upload-dir}")
    private String uploadDirPath;


    private static final Logger logger = LoggerFactory.getLogger(AnprService.class);

    public PageResponse<AnprResponse> list(AnprFilterRequest request) {
        BooleanExpression query = getQuery(request);
        int count = (int) anprEventRepository.count(query);
        int pageCount = (int) Math.ceil(count * 1.0 / request.getPageSize());
        Pageable paging = PageRequest.of(request.getPage() - 1, request.getPageSize(), Sort.by(DESC, "eventDate"));

        Page<AnprEvent> page = anprEventRepository.findAll(query, paging);
        //List<AnprResponse> list = page.get().map(AnprResponse::new).collect(Collectors.toList());

        List<AnprResponse> list = new ArrayList<>(page.getSize());
        page.get().forEach(item -> {
            list.add(new AnprResponse(item));
        });

        return (PageResponse<AnprResponse>) new AnprPageResponse(request.getPageSize(), pageCount, request.getPage(), list);
    }

    public BooleanExpression getQuery(AnprFilterRequest request) {

        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            String fromDate = request.getFromDate();
            String toDate = request.getToDate();
            QAnprEvent root = QAnprEvent.anprEvent;
            BooleanExpression query = root.archived.isFalse();

            if (request.getLpr() != null) {
                query = query.and(root.anprText.likeIgnoreCase("%" + request.getLpr() + "%"));
            }

            if (request.getFromDate() != null) {
                String fromTime = request.getFromTime() == null ? "00:00:00" : request.getFromTime();
                String starting = fromDate + " " + fromTime;
                Date startingDate = dateFormat.parse(starting);
                query = query.and(root.eventDate.after(startingDate));
            }

            if (request.getToDate() != null) {
                String toTime = request.getToTime() == null ? "00:00:00" : request.getToTime();
                String ending = toDate + " " + toTime;
                Date endingDate = dateFormat.parse(ending);
                query = query.and(root.eventDate.before(endingDate));
            }
            return query;
        } catch (Exception e) {
            logger.error("Error in parsing date", e);
        }
        return null;
    }

    public void archiveAnprEvent(AnprRequest request) {
        AnprEvent anprEvent = anprEventRepository.getOne(request.getId());
        anprEvent.setArchived(true);
        anprEventRepository.saveAndFlush(anprEvent);
    }

    public void archiveAnprEvents(AnprRequest request) {

        List<AnprEvent> events;
        int pageSize = 5;
        int currentPage = 0;

        QAnprEvent event = QAnprEvent.anprEvent;
        JPAQuery<AnprEvent> query = createAnprQuery(event);
        query.where(event.anprText.eq(request.anprText))
                .where(event.archived.isFalse());

        //pagination
        int count = (int) query.fetchCount();
        int pageCount = (int) Math.ceil(count * 1.0 / pageSize);

        for (currentPage = 0; currentPage < pageCount; currentPage++) {
            int offset = (currentPage) * pageSize;
            query.offset(offset);
            query.limit(pageSize);
            events = query.fetch();

            events.forEach(e -> {
                e.setArchived(true);
            });
            anprEventRepository.saveAll(events);
        }

    }

    public void addAnprEvent(CreateAnprRequest request) {

        AnprEvent anprEvent = request.toEntity();
        Feed feed = feedRepository.findOneByName(anprEvent.getSource());
        anprEvent.setFeed(feed);
        anprEvent.setHotlisted(checkHotListed(anprEvent));
        anprEvent.setSectionSpeedViolated(checkSectionSpeed(anprEvent));
        anprEventRepository.save(anprEvent);
    }

    private boolean checkSectionSpeed(AnprEvent anprEvent) {

        //find the feed configured for this source as there can be multple entry and exit cameras in a section
        Feed feed = anprEvent.getFeed();

        if (feed != null && feed.isCheckSectionSpeed()) {

            //now find the section and all entry sites for this exit site
            SpeedSection section = speedSectionRepository.findOneByExitSite(feed.getSite());

            //now locate the first anpr event
            AnprEvent first = anprEventRepository.findOneByAnprTextAndFeedSite(anprEvent.getAnprText(), section.getEntrySite());
            if (first != null) {
                long seconds = Duration.between(first.getEventDate().toInstant(), anprEvent.getEventDate().toInstant()).getSeconds();

                //1 mps = 3.6 kmph
                double avgspeed = 3.6 * (section.getSectionDistance() * 1.0 ) / seconds;
                anprEvent.setSpeed((float)avgspeed);
                return avgspeed > section.getMaxSpeed();
            }
        }
        return false;
    }


    private void parkingEvent(AnprEvent anprEvent) {
        //new parking event record
        ParkingEvent parkingEvent = parkingEventRepository.findByEventIdAndCheckInIsNull(anprEvent.getEventId());
        if (parkingEvent == null) {
            parkingEvent = new ParkingEvent();
        }
        parkingEvent.setCheckIn(anprEvent.getEventDate());
        parkingEvent.setEventId(anprEvent.getEventId());
        parkingEvent.setVehicleNo(anprEvent.getAnprText());
        parkingEvent.setOrg(anprEvent.getOrg());

        if (anprEvent.getVehicleClass().equals("car")) {
            parkingEvent.setType(VehicleType.Car);
        } else {
            parkingEvent.setType(VehicleType.Bike);
        }

        parkingEventRepository.save(parkingEvent);
    }

    public AnprResponse updateAnprEvent(AnprRequest request) {
        AnprEvent anprEvent = anprEventRepository.getOne(request.getId());
        anprEvent.setAnprText(request.getAnprText());
        anprEvent = anprEventRepository.saveAndFlush(anprEvent);
        return new AnprResponse(anprEvent);
    }

    private boolean checkHotListed(AnprEvent anprEvent) {
        HotListVehicle hottie = hotListVehicleRepository.findOneByLpr(anprEvent.getAnprText());
        return hottie != null;
    }

    public PageResponse<AnprResponse> listIncidents(AnprFilterRequest request) {
        BooleanExpression query = getIncidentQuery(request);
        int count = (int) anprEventRepository.count(query);
        int pageCount = (int) Math.ceil(count * 1.0 / request.getPageSize());
        Pageable paging = PageRequest.of(request.getPage() - 1, request.getPageSize(), Sort.by(DESC, "eventDate"));

        Page<AnprEvent> page = anprEventRepository.findAll(query, paging);
        //List<AnprResponse> list = page.get().map(AnprResponse::new).collect(Collectors.toList());

        List<AnprResponse> list = new ArrayList<>(page.getSize());
        page.get().forEach(item -> {
            list.add(new AnprResponse(item));
        });
        return (PageResponse<AnprResponse>) new AnprPageResponse(request.getPageSize(), pageCount, request.getPage(), list);
    }

    public PageResponse<AnprResponse> listHotListedIncidents(AnprFilterRequest request) {

        QAnprEvent event = QAnprEvent.anprEvent;
        QHotListVehicle hotListVehicle = QHotListVehicle.hotListVehicle;

        JPAQuery<AnprEvent> query = createAnprQuery(event);
        query = addFiltersInAnprQuery(request, event, query);

        // for hotListed vehicles
        query = query.innerJoin(hotListVehicle).on(event.anprText.eq(hotListVehicle.lpr));
        query = query.where(hotListVehicle.archived.isFalse());


        //pagination
        int count = (int) query.fetchCount();
        int pageCount = (int) Math.ceil(count * 1.0 / request.getPageSize());

        query.orderBy(event.eventDate.desc());

        int offset = (request.getPage() - 1) * request.getPageSize();
        query.offset(offset);
        if (request.getPageSize() > 0) {
            query.limit(request.getPageSize());
        }

        List<AnprEvent> data = query.fetch();

        List<AnprResponse> list = new ArrayList<>(request.getPageSize());
        data.forEach(item -> {
            list.add(new AnprResponse(item));
        });
        return (PageResponse<AnprResponse>) new AnprPageResponse(request.getPageSize(), pageCount, request.getPage(), list);
    }

    private JPAQuery<AnprEvent> addFiltersInAnprQuery(AnprFilterRequest request, QAnprEvent event, JPAQuery<AnprEvent> query) {
        query = query.where(event.archived.isFalse());

        if (!StringUtils.isEmpty(request.getLpr())) {
            query = query.where(event.anprText.likeIgnoreCase("%" + request.getLpr() + "%"));
        }

        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            String fromDate = request.getFromDate();
            String toDate = request.getToDate();

            if (request.getFromDate() != null) {
                String fromTime = request.getFromTime() == null ? "00:00:00" : request.getFromTime();
                String starting = fromDate + " " + fromTime;
                Date startingDate = dateFormat.parse(starting);
                query = query.where(event.eventDate.after(startingDate));
            }

            if (request.getToTime() != null) {
                String toTime = request.getToTime() == null ? "00:00:00" : request.getToTime();
                String ending = toDate + " " + toTime;
                Date endingDate = dateFormat.parse(ending);
                query = query.where(event.eventDate.after(endingDate));
            }
        } catch (Exception e) {
            logger.error("Error in parsing date", e);
        }
        return query;
    }

    private JPAQuery<AnprEvent> createAnprQuery(QAnprEvent event) {
        JPAQuery<AnprEvent> query = new JPAQuery<>(entityManager);

        query = query.select(event).from(event);
        return query;
    }

    private BooleanExpression getIncidentQuery(AnprFilterRequest request) {
        BooleanExpression query = getQuery(request);
        QAnprEvent root = QAnprEvent.anprEvent;
        query = query.and(root.direction.eq("rev").or(root.helmetMissing.isTrue()));
        return query;
    }

    public PageResponse<IncidentRepeatCount> listRepeatedIncidents(AnprFilterRequest request) {

        QAnprEvent event = QAnprEvent.anprEvent;
        JPAQuery<Tuple> query = new JPAQuery<>(entityManager);

        query = query.select(event.anprText, event.anprText.count()).from(event);

        // for repeated incidents
        query = query.where(event.direction.eq("rev"));

        if (!StringUtils.isEmpty(request.getLpr())) {
            query = query.where(event.anprText.eq(request.getLpr()));
        }

        query = query.groupBy(event.anprText)
                .having(event.anprText.count().gt(1))
                .orderBy(event.anprText.count().desc());

        //pagination start
        int count = (int) anprEventRepository.countReverseDirectionRepeatedIncidents();
        int pageCount = (int) Math.ceil(count * 1.0 / request.getPageSize());
        int offset = (request.getPage() - 1) * request.getPageSize();
        query.offset(offset);
        if (request.getPageSize() > 0) {
            query.limit(request.getPageSize());
        }
        //pagination ends

        List<Tuple> data = query.fetch();
        List<IncidentRepeatCount> list = new ArrayList<>(request.getPageSize());
        data.forEach(item -> {
            String anprText = item.get(event.anprText);
            Long repeatedTimes = item.get(1, Long.class);

            IncidentRepeatCount res = new IncidentRepeatCount(anprText, repeatedTimes);
            list.add(res);
        });
        return (PageResponse<IncidentRepeatCount>) new IncidentRepeatPageResponse(request.getPageSize(), pageCount, request.getPage(), list);
    }

    public PageResponse<IncidentRepeatCount> listRepeatedHelmetMissingIncidents(AnprFilterRequest request) {

        QAnprEvent event = QAnprEvent.anprEvent;
        JPAQuery<Tuple> query = new JPAQuery<>(entityManager);

        query = query.select(event.anprText, event.anprText.count()).from(event);

        // for repeated incidents
        query = query.where(event.helmetMissing.isTrue());

        if (!StringUtils.isEmpty(request.getLpr())) {
            query = query.where(event.anprText.eq(request.getLpr()));
        }

        query = query.groupBy(event.anprText)
                .having(event.anprText.count().gt(1))
                .orderBy(event.anprText.count().desc());
        //pagination start
        int count = (int) anprEventRepository.countHelmetMissingRepeatedIncidents();
        int pageCount = (int) Math.ceil(count * 1.0 / request.getPageSize());
        int offset = (request.getPage() - 1) * request.getPageSize();
        query.offset(offset);
        if (request.getPageSize() > 0) {
            query.limit(request.getPageSize());
        }
        //pagination ends


        List<Tuple> data = query.fetch();
        List<IncidentRepeatCount> list = new ArrayList<>(request.getPageSize());
        data.forEach(item -> {
            String anprText = item.get(event.anprText);
            Long repeatedTimes = item.get(1, Long.class);

            IncidentRepeatCount res = new IncidentRepeatCount(anprText, repeatedTimes);
            list.add(res);
        });
        return (PageResponse<IncidentRepeatCount>) new IncidentRepeatPageResponse(request.getPageSize(), pageCount, request.getPage(), list);
    }

    public PageResponse<AnprResponse> getIncidentsTimeline(AnprFilterRequest request) {

        QAnprEvent event = QAnprEvent.anprEvent;
        JPAQuery<AnprEvent> query = new JPAQuery<>(entityManager);

        query = query.select(event).from(event);
        //query = query.where(event.helmetMissing.isTrue());

        query = query.where(event.anprText.eq(request.getLpr()));
        if (StringUtils.isEmpty(request.getIncidentType())) {
            request.setIncidentType("all");
        }
        switch (request.getIncidentType()) {
            case "Reverse":
                query = query.where(event.direction.eq("rev"));
                break;
            case "Helmet-Missing":
                query = query.where(event.helmetMissing.isTrue());
                break;
            default:
                query = query.where((event.helmetMissing.isTrue()).or(event.direction.eq("rev")));
        }

        //pagination start
        int count = (int) query.fetchCount();
        int pageCount = (int) Math.ceil(count * 1.0 / request.getPageSize());
        int offset = (request.getPage() - 1) * request.getPageSize();
        query.offset(offset);
        if (request.getPageSize() > 0) {
            query.limit(request.getPageSize());
        }
        //pagination ends

        query.orderBy(event.eventDate.desc());

        List<AnprEvent> data = query.fetch();
        List<AnprResponse> list = new ArrayList<>(request.getPageSize());
        data.forEach(item -> {
            AnprResponse res = new AnprResponse(item);
            list.add(res);
        });
        return (PageResponse<AnprResponse>) new AnprPageResponse(request.getPageSize(), pageCount, request.getPage(), list);
    }

    public PageResponse<AnprResponse> getEventsListByLpr(AnprFilterRequest request) {

        QAnprEvent event = QAnprEvent.anprEvent;
        JPAQuery<AnprEvent> query = new JPAQuery<>(entityManager);

        query = query.select(event).from(event);


        query = query.where(event.anprText.eq(request.getLpr()));
        if (StringUtils.isEmpty(request.getIncidentType())) {
            request.setIncidentType("all");
        }


        //pagination start
        int count = (int) query.fetchCount();
        int pageCount = (int) Math.ceil(count * 1.0 / request.getPageSize());
        int offset = (request.getPage() - 1) * request.getPageSize();
        query.offset(offset);
        if (request.getPageSize() > 0) {
            query.limit(request.getPageSize());
        }
        //pagination ends

        query.orderBy(event.eventDate.desc());

        List<AnprEvent> data = query.fetch();
        List<AnprResponse> list = new ArrayList<>(request.getPageSize());
        data.forEach(item -> {
            AnprResponse res = new AnprResponse(item);
            list.add(res);
        });
        return (PageResponse<AnprResponse>) new AnprPageResponse(request.getPageSize(), pageCount, request.getPage(), list);
    }

    public PageResponse<AnprResponse> getEventsCountListByLpr(AnprFilterRequest request) {
        QAnprEvent event = QAnprEvent.anprEvent;
        JPAQuery<Tuple> query = new JPAQuery<>(entityManager);

        query = query.select(event.anprText, event.anprText.count()).from(event);

        query = query.where(event.archived.isFalse());

        if (!StringUtils.isEmpty(request.getLpr())) {
            query = query.where(event.anprText.eq(request.getLpr()));
        }

        query = query.groupBy(event.anprText)

                .orderBy(event.anprText.count().desc());

        //pagination start
        int count = (int) anprEventRepository.findTotalEventsCountListOfEachLpr();
        int pageCount = (int) Math.ceil(count * 1.0 / request.getPageSize());
        int offset = (request.getPage() - 1) * request.getPageSize();
        query.offset(offset);
        if (request.getPageSize() > 0) {
            query.limit(request.getPageSize());
        }
        //pagination ends

        List<Tuple> data = query.fetch();
        List<IncidentRepeatCount> list = new ArrayList<>(request.getPageSize());
        data.forEach(item -> {
            String anprText = item.get(event.anprText);
            Long repeatedTimes = item.get(1, Long.class);

            IncidentRepeatCount res = new IncidentRepeatCount(anprText, repeatedTimes);
            list.add(res);
        });
        return (PageResponse<AnprResponse>) new IncidentRepeatPageResponse(request.getPageSize(), pageCount, request.getPage(), list);

    }

    public String downloadAnprEvents(AnprReportRequest request) throws IOException {
        int page = 1;
        int offset = 0;
        int limit = 1000;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            request.setFrom(sdf.parse(request.getFromDateString()));
            request.setTo(sdf.parse(request.getToDateString()));
        } catch (ParseException e) {
            logger.info("Couldn't parse date", request.getFrom());
        }

        QAnprEvent anprEvent = QAnprEvent.anprEvent;
        JPAQuery<AnprEvent> query = new JPAQuery<>(entityManager);
        List<AnprEvent> result = null;
        List<com.querydsl.core.Tuple> result1 = null;
        String xAxis = StringUtils.isEmpty(request.getXAxis()) ? "" : request.getXAxis();

        switch (xAxis) {
            case "All Entry-Exit":
                query
                        .select(anprEvent)
                        .from(anprEvent)
                        .where(anprEvent.eventDate.between(request.getFrom(), request.getTo()))
                        .orderBy(anprEvent.eventDate.asc());
                break;

        }
        long totalRecordsCount = query.fetchCount();
        Path path = Paths.get(uploadDirPath);
        String filename = null;
        FileWriter fileWriter = null;

        switch(request.getReportType()) {
            case "CSV":
                filename = path.resolve(UUID.randomUUID().toString() + ".csv").toString();
                fileWriter = new FileWriter(filename);

                fileWriter.append("Sr. No");
                fileWriter.append(',');
                fileWriter.append("Event Date");
                fileWriter.append(',');
                fileWriter.append("Event Time");
                fileWriter.append(',');
                fileWriter.append("Vehicle Id");
                fileWriter.append(',');
                fileWriter.append("LPR");
                fileWriter.append(',');
                fileWriter.append("Vehicle Image");
                fileWriter.append(',');
                fileWriter.append("Direction");
                fileWriter.append(',');
                fileWriter.append("Location");
                fileWriter.append('\n');
                while (totalRecordsCount > offset) {
                    offset = (page - 1) * limit;
                    if (offset > 0) {
                        query.offset(offset);
                    }
                    query.limit(limit);
                    result = query.fetch();

                    int i = 0;
                    for (AnprEvent event : result) {
                        fileWriter.append(String.valueOf('"')).append(String.valueOf(i + 1)).append(String.valueOf('"'));
                        fileWriter.append(',');
                        fileWriter.append(String.valueOf('"')).append(toFormattedDate(event.getEventDate(), "dd-MM-yyyy")).append(String.valueOf('"'));
                        fileWriter.append(',');
                        fileWriter.append(String.valueOf('"')).append(toFormattedDate(event.getEventDate(), "HH:mm:ss")).append(String.valueOf('"'));
                        fileWriter.append(',');
                        fileWriter.append(String.valueOf('"')).append(event.getVehicleId()).append(String.valueOf('"'));
                        fileWriter.append(',');
                        fileWriter.append(String.valueOf('"')).append(event.getAnprText()).append(String.valueOf('"'));
                        fileWriter.append(',');
                        fileWriter.append(String.valueOf('"')).append(event.getVehicleImage().concat(".png")).append(String.valueOf('"'));
                        fileWriter.append(',');
                        fileWriter.append(String.valueOf('"')).append(event.getDirection()).append(String.valueOf('"'));
                        fileWriter.append(',');
                        fileWriter.append(String.valueOf('"')).append(event.getSource()).append(String.valueOf('"'));

                        fileWriter.append('\n');
                        i++;
                    }
                    page++;
                }
                break;

            case "JSON":
                filename = path.resolve(UUID.randomUUID().toString() + ".json").toString();
                fileWriter = new FileWriter(filename);
                while (totalRecordsCount > offset) {
                    offset = (page - 1) * limit;
                    if (offset > 0) {
                        query.offset(offset);
                    }
                    query.limit(limit);

                    result = query.fetch();

                    List<AnprResponse> responses=result.stream().map(AnprResponse::new).collect(Collectors.toList());

                    if(responses.size()>0) {
                        Gson gson = new Gson();
                        gson.toJson(responses, fileWriter);
                    }
                    page++;
                }

                break;
        }


        fileWriter.flush();
        fileWriter.close();
        return filename;
    }

    public String downloadAnprEventsOnDailyBasis(AnprReportRequest request) throws IOException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        SimpleDateFormat sdf1 = new SimpleDateFormat("dd/MM/yyyy");
        try {
            request.setFrom(sdf.parse(request.getFromDateString()));
            request.setTo(sdf.parse(request.getToDateString()));
        } catch (ParseException e) {
            logger.info("Couldn't parse date", request.getFrom());
        }

        QAnprEvent anprEvent = QAnprEvent.anprEvent;
        JPAQuery<AnprEvent> query = new JPAQuery<>(entityManager);
        Date eventDate = null;
        Long eventCount= null ;
        List<com.querydsl.core.Tuple> result = null;
        String xAxis = StringUtils.isEmpty(request.getXAxis()) ? "" : request.getXAxis();
        Map<Date, AnprReportResponse> totalEventsByDate = new TreeMap<Date, AnprReportResponse>();

        switch (xAxis) {

            case "DayWise Summary":
                result = query
                        .select(anprEvent.eventDate,
                                anprEvent.count())
                        .from(anprEvent)
                        .where(anprEvent.eventDate.between(request.getFrom(),request.getTo()))
                        .groupBy(anprEvent.eventDate.dayOfMonth(), anprEvent.eventDate.month(), anprEvent.eventDate.year())
                        .orderBy(anprEvent.eventDate.asc())
                        .fetch();


                for (int i = 0; i < result.size(); i++) {
                    com.querydsl.core.Tuple tuple = result.get(i);

                    eventDate = tuple.get(0, Date.class);
                    String eventDateString = toFormattedDate(eventDate,"dd/MM/yyyy");

                    try {
                        eventDate= sdf1.parse(eventDateString);
                    } catch (ParseException e) {
                        e.printStackTrace();
                    }
                    eventCount = tuple.get(1, Long.class);

                    totalEventsByDate.put(eventDate,new AnprReportResponse(eventCount,eventDateString));
                    result.set(i, null);
                }


                break;
        }
        List<AnprReportResponse> responses = new ArrayList<>();
        for(Date date: totalEventsByDate.keySet()){
            responses.add(new AnprReportResponse( totalEventsByDate.get(date).getTotalEvents(),toFormattedDate(date,"dd/MM/yyyy")));
        }

        Path path = Paths.get(uploadDirPath);
        String filename=null;
        FileWriter fileWriter= null;
        switch (request.getReportType()){
            case "CSV":
                filename = path.resolve(UUID.randomUUID().toString() + ".csv").toString();
                fileWriter = new FileWriter(filename);
                fileWriter.append("Sr. No");
                fileWriter.append(',');
                fileWriter.append("Date");
                fileWriter.append(',');
                fileWriter.append("Total Events");
                fileWriter.append('\n');

                int i=0;
                for (AnprReportResponse response1: responses) {

                    fileWriter.append(String.valueOf('"')).append(String.valueOf(i+1)).append(String.valueOf('"'));
                    fileWriter.append(',');
                    fileWriter.append(String.valueOf('"')).append(response1.getDate()).append(String.valueOf('"'));
                    fileWriter.append(',');
                    fileWriter.append(String.valueOf('"')).append(String.valueOf(response1.getTotalEvents())).append(String.valueOf('"'));

                    fileWriter.append('\n');

                    i++;

                }
                break;

            case "JSON":
                filename = path.resolve(UUID.randomUUID().toString() + ".json").toString();
                fileWriter = new FileWriter(filename);

                Gson gson = new Gson();
                gson.toJson(responses, fileWriter);

                break;

        }


        fileWriter.flush();
        fileWriter.close();
        return  filename;
    }
}
