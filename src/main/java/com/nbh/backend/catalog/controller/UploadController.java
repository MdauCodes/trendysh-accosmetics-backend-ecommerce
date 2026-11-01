package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.service.CloudinarySignatureService;
import com.nbh.backend.catalog.service.PhotoUploadService;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Staff-only photo uploads. {@code POST /image} sends the file through the backend to wherever the shop's photos are
 * kept ({@link PhotoUploadService}); the older signature for a straight-to-Cloudinary upload stays for a dashboard that
 * has not reloaded since (see {@link CloudinarySignatureService}).
 */
@RestController
@RequestMapping("/api/v1/admin/uploads")
public class UploadController {

    private final CloudinarySignatureService signatureService;
    private final PhotoUploadService uploads;

    public UploadController(CloudinarySignatureService signatureService, PhotoUploadService uploads) {
        this.signatureService = signatureService;
        this.uploads = uploads;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/cloudinary-signature")
    public CloudinarySignatureService.UploadSignature getSignature(
            @RequestParam(required = false, defaultValue = "trendysh/products") String folder) {
        return signatureService.sign(folder);
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoUploadService.Stored upload(@RequestPart("file") MultipartFile file,
                                            @RequestParam(required = false, defaultValue = "trendysh/products") String folder)
            throws IOException {
        return uploads.store(file.getBytes(), folder);
    }
}
