package io.synlabs.atcc.service;

import io.synlabs.atcc.config.FileStorageProperties;
import io.synlabs.atcc.entity.AtccRawData;
import io.synlabs.atcc.entity.AtccSummaryData;
import io.synlabs.atcc.entity.ImportStatus;
import io.synlabs.atcc.ex.FileStorageException;
import io.synlabs.atcc.jpa.AtccRawDataRepository;
import io.synlabs.atcc.jpa.AtccSummaryDataRepository;
import io.synlabs.atcc.jpa.ImportStatusRepository;
import org.simpleflatmapper.converter.Context;
import org.simpleflatmapper.converter.ContextualConverter;
import org.simpleflatmapper.csv.CsvMapper;
import org.simpleflatmapper.csv.CsvMapperFactory;
import org.simpleflatmapper.csv.CsvParser;
import org.simpleflatmapper.map.property.ConverterProperty;
import org.simpleflatmapper.map.property.DateFormatProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.synlabs.atcc.views.AtccRawDataResponse;
import io.synlabs.atcc.views.AtccSummaryDataResponse;
import io.synlabs.atcc.views.ResponseWrapper;
import io.synlabs.atcc.views.SearchRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AtccDataService extends BaseService {

    private static final Logger logger = LoggerFactory.getLogger(AtccDataService.class);

    private final AtccRawDataRepository rawDataRepository;

    private final AtccSummaryDataRepository summaryDataRepository;

    private final Path fileStorageLocation;

    private final ImportStatusRepository statusRepository;

    public AtccDataService(AtccRawDataRepository rawDataRepository,
                           AtccSummaryDataRepository summaryDataRepository,
                           ImportStatusRepository statusRepository,
                           FileStorageProperties fileStorageProperties) {

        this.rawDataRepository = rawDataRepository;
        this.summaryDataRepository = summaryDataRepository;

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
        Page<AtccRawData> page = rawDataRepository.findAll(PageRequest.of(searchRequest.getPage(), searchRequest.getPageSize(), Sort.by(isDescending(searchRequest.getSorted()) ? Sort.Direction.DESC : Sort.Direction.ASC, getDefaultSortId(searchRequest.getSorted(), "id"))));
        List<AtccRawDataResponse> collect = page.get().map(AtccRawDataResponse::new).collect(Collectors.toList());
        ResponseWrapper<AtccRawDataResponse> wrapper = new ResponseWrapper<>();
        wrapper.setData(collect);
        wrapper.setCurrPage(searchRequest.getPage());
        wrapper.setTotalElements(page.getTotalElements());
        return wrapper;
    }

    public ResponseWrapper<AtccSummaryDataResponse> listSummaryData(SearchRequest searchRequest) {
        Page<AtccSummaryData> page = summaryDataRepository.findAll(PageRequest.of(searchRequest.getPage(), searchRequest.getPageSize(), Sort.by(isDescending(searchRequest.getSorted()) ? Sort.Direction.DESC : Sort.Direction.ASC, getDefaultSortId(searchRequest.getSorted(), "id"))));
        List<AtccSummaryDataResponse> collect = page.get().map(AtccSummaryDataResponse::new).collect(Collectors.toList());
        ResponseWrapper<AtccSummaryDataResponse> wrapper = new ResponseWrapper<>();
        wrapper.setData(collect);
        wrapper.setCurrPage(searchRequest.getPage());
        wrapper.setTotalElements(page.getTotalElements());
        return wrapper;
    }

    public String importFile(MultipartFile file) {
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

            List<AtccRawData> datalist = importData(targetLocation);

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

    private void addStatusSpan(List<AtccRawData> datalist, ImportStatus status) {
        if (datalist == null || datalist.isEmpty()) return;
        AtccRawData first = datalist.get(0);
        AtccRawData last = datalist.get(datalist.size() - 1);
        status.setFrom(first.getTime());
        status.setTo(last.getTime());
        status.setDataDate(first.getDate());
    }

    private List<AtccRawData> importData(Path fileName) {
        try {

            List<AtccRawData> raws = new LinkedList<>();

            CsvParser
                    .mapWith(getCsvFactory())
                    .forEach(fileName.toFile(), raws::add);

            return raws;
        } catch (Exception e) {
            logger.error("Error occurred while loading object list from file " + fileName, e);
            return Collections.emptyList();
        }

    }

    private CsvMapper<AtccRawData> getCsvFactory() {
        return CsvMapperFactory
                .newInstance()
                .addColumnProperty("Time", new DateFormatProperty("HH:mm:ss"))
                .addColumnProperty("Date", new DateFormatProperty("dd/MM/yyyy"))
                .addAlias("Class", "type")
                .addColumnProperty("Class", ConverterProperty.of(new ContextualConverter<String, String>() {
                    @Override
                    public String convert(String s, Context context) throws Exception {
                        switch (s) {
                            case "0":
                                return "2-Wheeler";
                            case "1":
                                return "4-Wheeler";
                            case "2":
                                return "Bus/Truck";
                            case "3":
                                return "OSW";
                            default:
                                return "NA";
                        }
                    }
                }))
                .newMapper(AtccRawData.class);
    }
}
