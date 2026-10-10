package com.anandharajan.payguard.resolution;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/resolution-cases")
public final class ResolutionController {

    private final ResolutionService service;

    public ResolutionController(ResolutionService service) {
        this.service = service;
    }

    @PostMapping
    public ResolutionCaseResponse resolve(
            @RequestBody ResolutionCaseRequest request,
            Authentication authentication
    ) {
        return service.resolve(request, authentication.getName());
    }
}
