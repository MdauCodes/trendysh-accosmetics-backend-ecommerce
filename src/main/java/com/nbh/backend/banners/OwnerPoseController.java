package com.nbh.backend.banners;

import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** The owner's photos and the poses made from them (owner, 2026-11-04). See {@link OwnerPoseService}. */
@RestController
@PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
public class OwnerPoseController {

    private final OwnerPoseService poses;

    public OwnerPoseController(OwnerPoseService poses) {
        this.poses = poses;
    }

    @GetMapping("/api/v1/admin/owner-poses")
    public OwnerPoseService.Library library() {
        return poses.library();
    }

    @PostMapping("/api/v1/admin/owner-photos")
    public OwnerPoseService.Library addPhoto(@RequestBody OwnerPoseService.AddPhoto in) {
        return poses.addPhoto(in);
    }

    @DeleteMapping("/api/v1/admin/owner-photos/{id}")
    public OwnerPoseService.Library removePhoto(@PathVariable UUID id) {
        return poses.removePhoto(id);
    }

    @PostMapping("/api/v1/admin/owner-poses/make")
    public OwnerPoseService.Library make(@RequestBody OwnerPoseService.MakeRequest in,
                                         @AuthenticationPrincipal StaffUser staff) {
        return poses.make(in, staff == null ? null : staff.getFullName());
    }

    @PostMapping("/api/v1/admin/owner-poses/{id}/approve")
    public OwnerPoseService.PoseView approve(@PathVariable UUID id, @AuthenticationPrincipal StaffUser staff) {
        return poses.approve(id, staff == null ? null : staff.getFullName());
    }

    @PostMapping("/api/v1/admin/owner-poses/{id}/reject")
    public OwnerPoseService.PoseView reject(@PathVariable UUID id) {
        return poses.reject(id);
    }

    @PutMapping("/api/v1/admin/owner-poses/{id}/layout")
    public OwnerPoseService.PoseView setLayout(@PathVariable UUID id, @RequestBody OwnerPoseService.Layout in) {
        return poses.setLayout(id, in);
    }
}
