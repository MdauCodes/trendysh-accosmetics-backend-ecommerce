package com.nbh.backend.banners;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OwnerPoseRepository extends JpaRepository<OwnerPose, UUID> {

    List<OwnerPose> findAllByOrderByCreatedAtDesc();
}
