package io.synlabs.synvision.service;

import io.synlabs.synvision.config.FileStorageProperties;
import io.synlabs.synvision.entity.ImportStatus;
import io.synlabs.synvision.entity.anpr.AnprEvent;
import io.synlabs.synvision.entity.atcc.AtccRawData;
import io.synlabs.synvision.entity.atcc.AtccSummaryData;
import io.synlabs.synvision.entity.atcc.AtccVideoData;
import io.synlabs.synvision.entity.vids.HighwayIncident;
import io.synlabs.synvision.enums.TimeSpan;
import io.synlabs.synvision.ex.FileStorageException;
import io.synlabs.synvision.ex.NotFoundException;
import io.synlabs.synvision.jpa.*;
import io.synlabs.synvision.views.VideoSummary;
import io.synlabs.synvision.views.atcc.AtccRawDataResponse;
import io.synlabs.synvision.views.atcc.AtccSummaryDataResponse;
import io.synlabs.synvision.views.common.DummyRequest;
import io.synlabs.synvision.views.common.Request;
import io.synlabs.synvision.views.common.ResponseWrapper;
import io.synlabs.synvision.views.common.SearchRequest;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.joda.time.DateTime;
import org.simpleflatmapper.csv.CsvWriter;
import org.simpleflatmapper.util.CheckedConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.data.domain.Sort.Direction.DESC;

@Service
public class AtccDataService extends BaseService {

    private static final Logger logger = LoggerFactory.getLogger(AtccDataService.class);

    private final AtccRawDataRepository rawDataRepository;

    private final AtccSummaryDataRepository summaryDataRepository;

    private final Path fileStorageLocation;

    private final ImportStatusRepository statusRepository;

    private AtccVideoDataRepository videoDataRepository;

    @Autowired
    private AnprEventRepository anprEventRepository;

    @Autowired
    private HighwayIncidentRepository incidentRepository;

    @Qualifier("dataSource")
    @Autowired
    private DataSource dataSource;

    @Value("${ffmpeg.path}")
    private String ffmpegpath;

    @Value("${ffprobe.path}")
    private String ffprobepath;

    public AtccDataService(AtccRawDataRepository rawDataRepository,
                           AtccSummaryDataRepository summaryDataRepository,
                           ImportStatusRepository statusRepository,
                           FileStorageProperties fileStorageProperties,
                           AtccVideoDataRepository videoDataRepository) {

        this.rawDataRepository = rawDataRepository;
        this.summaryDataRepository = summaryDataRepository;
        this.videoDataRepository = videoDataRepository;

        this.fileStorageLocation = Paths.get(fileStorageProperties.getUploadDir())
                .toAbsolutePath().normalize();

        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (Exception ex) {
            throw new FileStorageException("Could not create the directory where the uploaded files will be stored.", ex);
        }
        this.statusRepository = statusRepository;
    }


    public ResponseWrapper<AtccRawDataResponse> listRawData(SearchRequest searchRequest) {
        Page<AtccRawData> page = rawDataRepository.findAll(PageRequest.of(searchRequest.getPage(), searchRequest.getPageSize(), Sort.by(DESC, "date", "time")));

        List<AtccRawDataResponse> collect = page.get().map(ar -> {
            AtccRawDataResponse ard = new AtccRawDataResponse(ar);
            setVideoId(ard);
            return ard;
        }).collect(Collectors.toList());
        ResponseWrapper<AtccRawDataResponse> wrapper = new ResponseWrapper<>();
        wrapper.setData(collect);
        wrapper.setCurrPage(searchRequest.getPage());
        wrapper.setTotalElements(page.getTotalElements());
        return wrapper;
    }

    private VideoSummary getVideoSummary(AtccRawData ar) {
        return videoDataRepository.getAssociatedVideo(ar.getTimeStamp(), ar.getFeed());
    }

    private void setVideoId(AtccRawDataResponse ard) {
        VideoSummary vs = videoDataRepository.getAssociatedVideo(ard.getTimeStamp(), ard.getTag());
        if (vs != null) {
            ard.setVid(vs.getId());
            ard.setVts(vs.getTimeStamp());
            ard.setOffset(vs.getOffset());
        }
    }

    public ResponseWrapper<AtccSummaryDataResponse> listSummaryData(SearchRequest searchRequest, String interval) {

        ResponseWrapper<AtccSummaryDataResponse> wrapper = new ResponseWrapper<>();

        long totalRecords = 0;
        AtccSummaryData atccSummaryData;
        List<AtccSummaryData> data = new ArrayList<>();
        Connection connection;

        switch (interval) {
            case "day":

                try {
                    String query = "SELECT COUNT(1) AS COUNT, type,`date`, 1 AS span, MIN(`date`) AS `from`, MAX(`date`) AS `to` FROM atcc_raw_data GROUP BY type, date ORDER BY `date` DESC, `time` DESC LIMIT ?, ? ;";

                    connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(query);
                    ps.setInt(1, searchRequest.getPageSize() * searchRequest.getPage());
                    ps.setInt(2, searchRequest.getPageSize());
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        atccSummaryData = getAtccSummaryData(rs);
                        atccSummaryData.setSpan(TimeSpan.Day);
                        data.add(atccSummaryData);
                    }

                    query = "SELECT COUNT(*) AS count FROM (SELECT TYPE FROM atcc_raw_data GROUP BY TYPE, `date`) AS atcc_summary_data";
                    ps = connection.prepareStatement(query);
                    rs = ps.executeQuery();
                    while (rs.next()) {
                        totalRecords = rs.getLong("count");
                    }
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }


                break;
            case "month":

                try {
                    String query = "SELECT COUNT(1) AS COUNT, type,`date`, 1 AS span, MIN(`date`) AS `from`, MAX(`date`) AS `to` FROM atcc_raw_data GROUP BY type, MONTH(`date`) ORDER BY `date` DESC, `time` DESC LIMIT ?, ? ;";

                    connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(query);
                    ps.setInt(1, searchRequest.getPageSize() * searchRequest.getPage());
                    ps.setInt(2, searchRequest.getPageSize());
                    ResultSet rs = ps.executeQuery();

                    while (rs.next()) {
                        atccSummaryData = getAtccSummaryData(rs);
                        atccSummaryData.setSpan(TimeSpan.Month);
                        data.add(atccSummaryData);
                    }

                    query = "SELECT COUNT(*) AS count FROM (SELECT TYPE FROM atcc_raw_data GROUP BY TYPE, MONTH(`date`)) AS atcc_summary_data";
                    ps = connection.prepareStatement(query);
                    rs = ps.executeQuery();
                    while (rs.next()) {
                        totalRecords = rs.getLong("count");
                    }
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                break;

            case "hour":
            default:
                try {
                    String query = "SELECT COUNT(1) AS COUNT, type,`date`, 1 AS span, SEC_TO_TIME(hour(time)*60*60) AS `from`, SEC_TO_TIME((hour(time) + 1)*60*60-1) AS `to` FROM atcc_raw_data GROUP BY type, `date`, hour(time) ORDER BY `date` DESC, `time` DESC LIMIT ?, ? ;";

                    connection = dataSource.getConnection();
                    PreparedStatement ps = connection.prepareStatement(query);
                    ps.setInt(1, searchRequest.getPageSize() * searchRequest.getPage());
                    ps.setInt(2, searchRequest.getPageSize());
                    ResultSet rs = ps.executeQuery();

                    while (rs.next()) {
                        atccSummaryData = getAtccSummaryData(rs);
                        atccSummaryData.setSpan(TimeSpan.Hour);
                        data.add(atccSummaryData);
                    }

                    query = "SELECT COUNT(*) AS count FROM (SELECT type FROM atcc_raw_data GROUP BY type, `date`, hour(time)) AS atcc_summary_data";
                    ps = connection.prepareStatement(query);
                    rs = ps.executeQuery();
                    while (rs.next()) {
                        totalRecords = rs.getLong("count");
                    }
                    connection.close();
                } catch (SQLException e) {
                    e.printStackTrace();
                }
                break;
        }

        List<AtccSummaryDataResponse> collect = data.stream().map(AtccSummaryDataResponse::new).collect(Collectors.toList());
        wrapper.setTotalElements(totalRecords);
        wrapper.setCurrPage(searchRequest.getPage());
        wrapper.setData(collect);

        return wrapper;
    }

    private AtccSummaryData getAtccSummaryData(ResultSet rs) throws SQLException {
        AtccSummaryData atccSummaryData = new AtccSummaryData();
        atccSummaryData.setCount(rs.getInt("count"));
        atccSummaryData.setType(rs.getString("type"));
        atccSummaryData.setDate(rs.getDate("date"));
        atccSummaryData.setFrom(rs.getDate("from"));
        atccSummaryData.setTo(rs.getDate("to"));
        return atccSummaryData;
    }

    public String importVideo(MultipartFile file, String tag) {
        // Normalize file name
        String fileName = StringUtils.cleanPath(file.getOriginalFilename());
        ImportStatus status = new ImportStatus();
        status.setFilename(fileName);
        status.setImportDate(new Date());

        try {
            // Check if the file's name contains invalid characters
            if (fileName.contains("..")) {
                throw new FileStorageException("Sorry! Filename contains invalid path sequence " + fileName);
            }

            // Copy file to the target location (Replacing existing file with the same name)
            Path targetLocation = this.fileStorageLocation.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            videoDataRepository.save(populateFields(fileName, tag));
            status.setStatus("OK");

            return fileName;
        } catch (IOException ex) {
            status.setStatus("FAILED");
            status.setError(ex.getMessage());
            throw new FileStorageException("Could not store file " + fileName + ". Please try again!", ex);
        } finally {
            statusRepository.save(status);
        }
    }

    private AtccVideoData populateFields(String fileName, String tag) {
        double dts = Double.parseDouble(fileName.split("_")[1]);
        long ts = (long) dts;
        DateTime videoDate = new DateTime(ts * 1000L);
        AtccVideoData videoData = new AtccVideoData();
        videoData.setDate(videoDate.toDate());
        videoData.setTime(videoDate.toDate());
        videoData.setTimeStamp(ts);
        videoData.setFeed(tag);
        videoData.setFilename(fileName);
        videoData.setOffset(13);
        return videoData;
    }

    private void addStatusSpan(List<AtccRawData> datalist, ImportStatus status) {
        if (datalist == null || datalist.isEmpty()) return;
        AtccRawData first = datalist.get(0);
        AtccRawData last = datalist.get(datalist.size() - 1);
        status.setFrom(first.getTime());
        status.setTo(last.getTime());
        status.setDataDate(first.getDate());
    }

    private List<AtccRawData> importData(Path fileName, String tag) {
        try {
            List<AtccRawData> raws = new LinkedList<>();
            getCSVRecords(fileName, raws, tag);
            return raws;
        } catch (Exception e) {
            logger.error("Error occurred while loading object list from file " + fileName, e);
            return Collections.emptyList();
        }

    }

    private void getCSVRecords(Path fileName, List<AtccRawData> raws, String tag) throws IOException, ParseException {
        Reader reader = Files.newBufferedReader(fileName);
        CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .withSkipHeaderRecord()
                .withTrim());

        SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm:ss");
        SimpleDateFormat sdfDate = new SimpleDateFormat("dd/MM/yyyy");

        sdfTime.setTimeZone(TimeZone.getTimeZone("GMT"));
        sdfDate.setTimeZone(TimeZone.getTimeZone("GMT"));

        for (CSVRecord csvRecord : csvParser) {
            // Accessing Values by Column Index
            String time = csvRecord.get(0);
            String date = csvRecord.get(1);
            String timestamp = csvRecord.get(2);
            String lane = csvRecord.get(3);
            String speed = csvRecord.get(4);
            String direction = csvRecord.get(5);
            String class_no = csvRecord.get(6);
            String type = "";
            String vid = csvRecord.size() == 7 ? "" : csvRecord.get(7);

            long ts = (long) Double.parseDouble(timestamp);

            AtccRawData atccRawData = new AtccRawData();
            atccRawData.setTime(sdfTime.parse(time));

            atccRawData.setDate(sdfDate.parse(date));

            atccRawData.setTimeStamp(ts);
            atccRawData.setLane(0);
            atccRawData.setSpeed(new BigDecimal(speed));
            atccRawData.setDirection(getDirection(direction));
            atccRawData.setFeed(tag);
            atccRawData.setVid(vid);
            switch (class_no) {
                case "0":
                case "lmv":
                    type = "LMV";
                    break;
                case "lcv":
                case "1":
                    type = "LCV";
                    break;
                case "2":
                case "truck":
                    type = "Truck/Bus";
                    break;
                case "3":
                case "2w":
                    type = "2-Wheeler";
                    break;
                case "4":
                    type = "OSV";
                    break;
                default:
                    type = "NA";
            }

            atccRawData.setType(type);
            raws.add(atccRawData);
        }
    }

    private int getDirection(String direction) {
        switch (direction) {
            case "fwd":
                return 1;
            case "stop":
                return 0;
            case "rev":
                return -1;
        }
        return 1;
    }

    public String importFile(MultipartFile file, String tag) {
        String fileName = StringUtils.cleanPath(file.getOriginalFilename());

        long cnt = statusRepository.countByFilenameAndFeedAndStatus(fileName, tag, "OK");

        if (cnt > 0) {
            logger.warn("File {}  - {} is already imported, not importing again", tag, fileName);
            return fileName;
        }

        ImportStatus status = new ImportStatus();
        status.setFilename(fileName);
        status.setImportDate(new Date());
        status.setFeed(tag);
        try {
            // Check if the file's name contains invalid characters
            if (fileName.contains("..")) {
                throw new FileStorageException("Sorry! Filename contains invalid path sequence " + fileName);
            }

            // Copy file to the target location (Replacing existing file with the same name)
            Path targetLocation = this.fileStorageLocation.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            List<AtccRawData> datalist = importData(targetLocation, tag);

            rawDataRepository.saveAll(datalist);

            addStatusSpan(datalist, status);
            status.setStatus("OK");

            return fileName;
        } catch (IOException ex) {
            status.setStatus("FAILED");
            status.setError(ex.getMessage());
            throw new FileStorageException("Could not store file " + fileName + ". Please try again!", ex);
        } finally {
            statusRepository.save(status);
        }

    }

    public Resource makeSummaryData(String interval) throws IOException {

        String filename = "summary-" + interval + "-" + UUID.randomUUID().toString() + ".csv";
        Path filePath = this.fileStorageLocation.resolve(filename).normalize();

        SearchRequest request = new SearchRequest();
        request.setPage(0);
        request.setPageSize(1000);
        ResponseWrapper<AtccSummaryDataResponse> wrapper = listSummaryData(request, interval);
        List<AtccSummaryDataResponse> data = wrapper.getData();

        CsvWriter.CsvWriterDSL<AtccSummaryDataResponse> writerDsl =
                CsvWriter
                        .from(AtccSummaryDataResponse.class)
                        .columns("type", "date", "from", "to", "span", "count");
        try (FileWriter fileWriter = new FileWriter(filePath.toFile())) {
            CsvWriter<AtccSummaryDataResponse> writer = writerDsl.to(fileWriter);
            data.forEach(CheckedConsumer.toConsumer(writer::append));
        }

        Resource resource = new UrlResource(filePath.toUri());

        if (resource.exists()) {
            return resource;
        } else {
            throw new NotFoundException("File not found " + filename);
        }
    }

    public File listRawData() throws IOException {

        String filename = "rawdata-" + UUID.randomUUID().toString() + ".csv";
        Path filePath = this.fileStorageLocation.resolve(filename).normalize();


        //List<AtccRawData> data =  rawDataRepository.findAll(Sort.by(DESC, "timeStamp"));
        int currPage = 0;
        Page<AtccRawData> page = rawDataRepository.findAll(PageRequest.of(currPage, 10000, Sort.by(DESC, "timeStamp")));
        int totalPages = page.getTotalPages();

        logger.info("Page Size - 10000, Current Page - ", currPage);
        logger.info("Total Pages - ", totalPages);
        File file = filePath.toFile();
        try (FileWriter fileWriter = new FileWriter(file)) {

            CsvWriter.CsvWriterDSL<AtccRawData> writerDsl =
                    CsvWriter
                            .from(AtccRawData.class)
                            .columns("date", "time", "timestamp", "lane", "speed", "direction", "type", "feed", "vid");

            CsvWriter<AtccRawData> writer = writerDsl.to(fileWriter);
            page.get().forEach(CheckedConsumer.toConsumer(writer::append));

            for (currPage = 1; currPage < totalPages; currPage++) {
                logger.info("Current Page - {}", currPage);
                page = rawDataRepository.findAll(PageRequest.of(currPage, 10000, Sort.by(DESC, "timeStamp")));
                page.get().forEach(CheckedConsumer.toConsumer(writer::append));
            }

        }

        /*Resource resource = new UrlResource(filePath.toUri());*/

        if (file.exists()) {
            return file;
        } else {
            throw new NotFoundException("File not found " + filename);
        }
    }


    @Transactional(readOnly = true)
    public File streamRawData() throws IOException {

        String filename = "rawdata-" + UUID.randomUUID().toString() + ".csv";
        Path filePath = this.fileStorageLocation.resolve(filename).normalize();

        File file = filePath.toFile();
        try (FileWriter fileWriter = new FileWriter(file)) {

            CsvWriter.CsvWriterDSL<AtccRawData> writerDsl =
                    CsvWriter
                            .from(AtccRawData.class)
                            .columns("date", "time", "timestamp", "lane", "speed", "direction", "type", "feed", "vid");

            CsvWriter<AtccRawData> writer = writerDsl.to(fileWriter);

            try (Stream<AtccRawData> atccRawDataStream = rawDataRepository.getAll()) {
                atccRawDataStream.forEach(CheckedConsumer.toConsumer(writer::append));
            }
        }

        /*Resource resource = new UrlResource(filePath.toUri());*/

        if (file.exists()) {
            return file;
        } else {
            throw new NotFoundException("File not found " + filename);
        }
    }


    public Resource loadFileAsResource(String id) {
        String fileName = id;
        try {
            Optional<AtccVideoData> data = videoDataRepository.findById(Long.parseLong(id));
            if (data.isPresent()) {
                fileName = data.get().getFilename();
                Path filePath = this.fileStorageLocation.resolve(fileName).normalize();
                Resource resource = new UrlResource(filePath.toUri());
                if (resource.exists()) {
                    return resource;
                } else {
                    throw new NotFoundException("File not found " + fileName);
                }
            } else {
                throw new NotFoundException("File not found " + fileName);
            }
        } catch (NumberFormatException ex) {
            throw new NotFoundException("number format for " + id, ex);
        } catch (MalformedURLException ex) {
            throw new NotFoundException("File not found " + fileName, ex);
        }
    }

    public Resource getScreenshot(Long id) throws IOException {

        if (id == null) throw new NotFoundException("Not a valid id");

        Optional<AtccRawData> odata = rawDataRepository.findById(id);

        if (odata.isPresent()) {
            AtccRawData data = odata.get();
            VideoSummary summary = getVideoSummary(data);

            if (summary == null) {
                throw new NotFoundException("video not found");
            }

            Path screenshotfile = getScreenshotFileName(data);

            if (Files.exists(screenshotfile)) {
                return new UrlResource(screenshotfile.toUri());
            }

            FFmpeg ffmpeg = new FFmpeg(ffmpegpath);
            FFprobe ffprobe = new FFprobe(ffprobepath);

            //offset is data.ts - video.ts
            long offset = data.getTimeStamp() - summary.getTimeStamp();
            Path filePath = this.fileStorageLocation.resolve(summary.getFileName()).normalize();
            FFmpegBuilder builder = new FFmpegBuilder()
                    .setInput(filePath.toString())
                    .overrideOutputFiles(true)
                    .addOutput(screenshotfile.toString())
                    .setStartOffset(offset, TimeUnit.SECONDS)
                    .addExtraArgs("-vframes", "1")
                    .done();

            FFmpegExecutor executor = new FFmpegExecutor(ffmpeg, ffprobe);

            // Run a one-pass encode
            executor.createJob(builder).run();

            Resource resource = new UrlResource(screenshotfile.toUri());
            if (resource.exists()) {
                return new UrlResource(screenshotfile.toUri());
            } else {
                throw new NotFoundException("ffmpeg not working :( no screenshot generated");
            }
        } else {
            throw new NotFoundException("Not a valid id");
        }

    }

    private Path getScreenshotFileName(AtccRawData data) {
        return this.fileStorageLocation.resolve(data.getFeed() + "_" + data.getTimeStamp() + ".jpg").normalize();
    }

    public Resource downloadVehicleImage(Long id) {
        return downloadByTag("vehicle", id);
    }

    public Resource downloadLprImage(Long id) {
        return downloadByTag("anpr", id);
    }

    public Resource downloadByTag(String tag, Long mid) {

        long id = new DummyRequest().unmask(mid);
        String filename = null;
        try {
            Optional<AnprEvent> eventop = anprEventRepository.findById(id);
            if (eventop.isPresent()) {
                filename = eventop.get().getVehicleImage() + ".jpg";

                Path filePath = Paths.get(this.fileStorageLocation.toString(), tag, filename).toAbsolutePath().normalize();
                Resource resource = new UrlResource(filePath.toUri());
                if (resource.exists()) {
                    return resource;
                } else {
                    throw new NotFoundException("File not found " + filename);
                }
            } else {
                throw new NotFoundException("File not found " + filename);
            }

        } catch (MalformedURLException ex) {
            throw new NotFoundException("File not found " + filename, ex);
        }
    }

    public Resource downloadIncidentImage(Long id) {

        String filename = null;
        String tag = "vids-image";
        try {
            Optional<HighwayIncident> incident = incidentRepository.findById(id);
            if (incident.isPresent()) {
                filename = incident.get().getIncidentImage();

                Path filePath = Paths.get(this.fileStorageLocation.toString(), tag, filename).toAbsolutePath().normalize();
                Resource resource = new UrlResource(filePath.toUri());
                if (resource.exists()) {
                    return resource;
                } else {
                    throw new NotFoundException("File not found " + filename);
                }
            } else {
                throw new NotFoundException("File not found " + filename);
            }

        } catch (MalformedURLException ex) {
            throw new NotFoundException("File not found " + filename, ex);
        }
    }

    public Resource downloadIncidentVideo(Long id) {

        String filename = null;
        String tag = "vids-video";
        try {
            Optional<HighwayIncident> incident = incidentRepository.findById(id);
            if (incident.isPresent()) {
                filename = incident.get().getIncidentVideo();

                Path filePath = Paths.get(this.fileStorageLocation.toString(), tag, filename).toAbsolutePath().normalize();
                Resource resource = new UrlResource(filePath.toUri());
                if (resource.exists()) {
                    return resource;
                } else {
                    throw new NotFoundException("File not found " + filename);
                }
            } else {
                throw new NotFoundException("File not found " + filename);
            }

        } catch (MalformedURLException ex) {
            throw new NotFoundException("File not found " + filename, ex);
        }
    }
}
