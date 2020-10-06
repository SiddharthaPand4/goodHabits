package io.synlabs.synvision.service;

import io.synlabs.synvision.config.FileStorageProperties;
import io.synlabs.synvision.entity.avc.Survey;
import io.synlabs.synvision.ex.FileStorageException;
import io.synlabs.synvision.jpa.SurveyRepository;
import io.synlabs.synvision.views.SurveyRequest;
import io.synlabs.synvision.views.SurveyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Service
public class SurveyService extends BaseService {

    private static final Logger logger = LoggerFactory.getLogger(SurveyService.class);

    @Autowired
    private SurveyRepository surveyRepository;

    @Autowired
    private FileStorageProperties fileStorageProperties;

    public SurveyResponse createSurvey(SurveyRequest surveyRequest) {
        if (!Paths.get(fileStorageProperties.getUploadDir(), surveyRequest.getFolder()).toFile().mkdirs()) {
            throw new FileStorageException("Can't create folder for given survey");
        }
        return new SurveyResponse(surveyRepository.saveAndFlush(new Survey(surveyRequest)));
    }

    public boolean checkDuplicateSurvey(String surveyFolder) {
        return surveyRepository.findFirstByFolderName(surveyFolder) != null;
    }

    public void deleteSurvey(Long surveyId) {
        surveyRepository.deleteById(surveyId);
    }
}
