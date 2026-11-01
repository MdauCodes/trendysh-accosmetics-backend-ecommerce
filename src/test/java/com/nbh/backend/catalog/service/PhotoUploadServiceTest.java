package com.nbh.backend.catalog.service;

import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.imagecleanup.CloudinaryUploader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Staff uploads through the backend: only pictures, only into the dashboard's own folders, stored wherever photos live now. */
class PhotoUploadServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1};

    private final CloudinaryUploader storage = mock(CloudinaryUploader.class);
    private final PhotoUploadService service = new PhotoUploadService(storage);

    @Test
    void aPictureIsStoredInTheFolderAsked() {
        when(storage.upload(any(), eq("trendysh/categories"), anyString()))
                .thenReturn(new CloudinaryUploader.Uploaded("https://img.example.test/o/trendysh/categories/x.jpg", "trendysh/categories/x.jpg"));

        assertThat(service.store(JPEG, "trendysh/categories").url()).isEqualTo("https://img.example.test/o/trendysh/categories/x.jpg");
    }

    @Test
    void anythingElseIsRefusedBeforeItIsStored() {
        assertThatThrownBy(() -> service.store(JPEG, "../secrets")).isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> service.store("<svg onload=alert(1)>".getBytes(), null)).isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("not a picture");
        assertThatThrownBy(() -> service.store(new byte[0], null)).isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> service.store(new byte[PhotoUploadService.MAX_BYTES + 1], null))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("25 MB");
        verifyNoInteractions(storage);
    }
}
