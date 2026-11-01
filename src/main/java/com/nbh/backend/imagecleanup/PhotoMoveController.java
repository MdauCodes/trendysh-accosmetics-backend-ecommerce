package com.nbh.backend.imagecleanup;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Developer-only: moving the stored photo addresses off Cloudinary, see {@link PhotoMoveService}. Each POST starts the
 * work in the background; GET /status shows how far it is and the last report.
 */
@RestController
@RequestMapping("/api/v1/admin/photo-move")
@PreAuthorize("hasRole('DEVELOPER')")
public class PhotoMoveController {

    private final PhotoMoveService moves;

    public PhotoMoveController(PhotoMoveService moves) {
        this.moves = moves;
    }

    @GetMapping("/status")
    public PhotoMoveService.Status status() {
        return moves.status();
    }

    @PostMapping("/run")
    public PhotoMoveService.Status run(@RequestParam(defaultValue = "true") boolean dryRun) {
        return moves.start(dryRun);
    }

    @PostMapping("/undo")
    public PhotoMoveService.Status undo() {
        return moves.startUndo();
    }
}
