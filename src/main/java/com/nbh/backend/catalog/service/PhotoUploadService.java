package com.nbh.backend.catalog.service;

import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import com.nbh.backend.imagecleanup.S3PhotoStore;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Staff photo uploads through the backend (POST /admin/uploads/image), which replaced the browser sending the file
 * straight to Cloudinary with a signature: the backend checks the file is a picture and stores it wherever the shop's
 * photos are kept now ({@link CloudinaryUploader}: the shop's own bucket, or Cloudinary until it is switched over), so the
 * dashboard no longer needs to know which.
 */
@Service
public class PhotoUploadService {

    /** The folders the dashboard uploads into (product photos unless a screen says otherwise). */
    static final Set<String> FOLDERS = Set.of("trendysh/products", "trendysh/categories", "trendysh/professions",
            "products", "promo-banners", "banners", "deals", "packages");
    static final int MAX_BYTES = 25 * 1024 * 1024;

    public record Stored(String url) {}

    private final CloudinaryUploader storage;

    public PhotoUploadService(CloudinaryUploader storage) {
        this.storage = storage;
    }

    public Stored store(byte[] bytes, String folder) {
        String where = folder == null || folder.isBlank() ? "trendysh/products" : folder.trim();
        if (!FOLDERS.contains(where)) {
            throw new UnprocessableException("Photos cannot be stored in \"" + where + "\".");
        }
        if (bytes == null || bytes.length == 0) {
            throw new UnprocessableException("The file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new UnprocessableException("That picture is over 25 MB; make it smaller and try again.");
        }
        if (S3PhotoStore.imageType(bytes).isEmpty()) {
            throw new UnprocessableException("That file is not a picture (PNG, JPEG, WebP, GIF or AVIF).");
        }
        return new Stored(storage.upload(bytes, where, UUID.randomUUID().toString()).url());
    }
}
