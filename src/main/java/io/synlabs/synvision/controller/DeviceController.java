package io.synlabs.synvision.controller;

import io.synlabs.synvision.service.DeviceService;
import io.synlabs.synvision.views.DeviceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

import static io.synlabs.synvision.auth.LicenseServerAuth.Privileges.DEVICE_WRITE;
import static io.synlabs.synvision.auth.LicenseServerAuth.Privileges.ROLE_WRITE;

/**
 * Created by itrs on 10/16/2019.
 */
@RestController
@RequestMapping("/api/device")
public class DeviceController  {

    @Autowired
    private DeviceService deviceService;

    @GetMapping
    @Secured(DEVICE_WRITE)
    public DeviceResponse list(){
        return new DeviceResponse(deviceService.listDevices());
    }
}
