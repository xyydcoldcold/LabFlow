package com.labflow.backend.system;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @GetMapping("/info")
    public Map<String, String> getSystemInfo() {
        return Map.of(
                "service", "labflow-api",
                "version", "0.1.0",
                "status", "UP"
        );
    }
}