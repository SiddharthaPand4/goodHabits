package io.synlabs.synvision.controller.parking;

import io.synlabs.synvision.config.FileStorageProperties;
import io.synlabs.synvision.ex.FileStorageException;
import io.synlabs.synvision.service.parking.ParkingGuidanceService;
import io.synlabs.synvision.views.DashboardRequest;
import io.synlabs.synvision.views.UploadFileResponse;
import io.synlabs.synvision.views.parking.HourlyStatsResponse;
import io.synlabs.synvision.views.parking.ParkingDashboardResponse;
import io.synlabs.synvision.views.parking.ParkingEventCountResponse;
import io.synlabs.synvision.views.parking.ParkingEventDashboardResponse;
import io.synlabs.synvision.views.parking.ParkingSlotResponse;
import io.synlabs.synvision.views.incident.IncidentGroupCountResponse;
import io.synlabs.synvision.views.parking.*;
import io.synlabs.synvision.views.parking.ParkingSlotResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.annotation.Secured;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Collectors;

import static io.synlabs.synvision.auth.LicenseServerAuth.Privileges.PARKING_READ;
import static io.synlabs.synvision.auth.LicenseServerAuth.Privileges.PARKING_WRITE;

@RestController
@RequestMapping("/api/apms/guidance")
public class ParkingGuidanceController {

    private static final Logger logger = LoggerFactory.getLogger(ParkingGuidanceController.class);

    @Autowired
    private FileStorageProperties fileStorageProperties;

    @Autowired
    private ParkingGuidanceService guidanceService;

    @GetMapping("/slots")
    @Secured(PARKING_READ)
    public List<ParkingSlotResponse> getLots() {
        //return userService.getRoles().stream().map(RoleResponse::new).collect(Collectors.toList());
        return guidanceService.slots("lucknow").stream().map(ParkingSlotResponse::new).collect(Collectors.toList());
    }

    @PostMapping("slots")
    @Secured(PARKING_WRITE)
    public void updateSlot(@RequestBody UpdateSlotRequest request) {
        guidanceService.updateSlot(request);
    }


    @GetMapping("/stats")
    @Secured(PARKING_READ)
    public ParkingDashboardResponse stats() {
        return guidanceService.stats("lucknow");
    }

    @GetMapping("/checked-in/current/count")
    @Secured(PARKING_READ)
    public ParkingEventDashboardResponse getCheckedInVehicleCount() {
        return guidanceService.getCheckedInVehicleCount();
    }

    @GetMapping("/hourly")
    @Secured(PARKING_READ)
    public List<HourlyStatsResponse> hourly() {
        return guidanceService.hourly("lucknow");
    }


    @PostMapping("parking/vehicle/count")
    @Secured(PARKING_WRITE)
    public ParkingEventCountResponse getParkingVehicleCount(@RequestBody DashboardRequest request) {
        return guidanceService.getParkingVehicleCount(request);
    }

    //TODO attach this with the lot and do slot calculation
    //TODO upload to S3
    @PostMapping("/image")
    @Secured(PARKING_WRITE)
    public UploadFileResponse uploadFile(@RequestParam("file") MultipartFile file, @RequestParam("lot") String lotName) {

        if (file == null) throw new FileStorageException("Missing file in multipart");
        logger.info("File uploaded, now importing..{} with tag {}", file.getOriginalFilename(), lotName);
        try {
            Path fileStorageLocation = Paths.get(fileStorageProperties.getUploadDir(), "pgs").toAbsolutePath().normalize();

            if (!Files.exists(fileStorageLocation)) {
                File dir = new File(fileStorageLocation.toString());
                dir.mkdirs();
            }

            String fileName = StringUtils.cleanPath(file.getOriginalFilename());

            Path targetLocation = fileStorageLocation.resolve(fileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);
            guidanceService.updateParkingLotImage(lotName, fileName);
            return new UploadFileResponse(fileName, file.getContentType(), file.getSize(), lotName);
        } catch (IOException e) {
            throw new FileStorageException("Error copying file to storage");
        }
    }
}
