package com.nbh.backend.banners;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CampaignDealRepository extends JpaRepository<CampaignDeal, UUID> {

    List<CampaignDeal> findByBannerIdOrderByPositionAsc(UUID bannerId);

    boolean existsByBannerId(UUID bannerId);

    List<CampaignDeal> findByBannerIdInOrderByPositionAsc(Collection<UUID> bannerIds);

    List<CampaignDeal> findByProductIdIn(Collection<UUID> productIds);

    /** Pieces sold under each deal, free ones included, on orders that were not cancelled or left unpaid to expire. */
    @Query(value = "SELECT CAST(i.campaign_deal_id AS VARCHAR), COALESCE(SUM(i.qty), 0) FROM customer_order_item i"
            + " JOIN customer_order o ON o.id = i.order_id"
            + " WHERE i.campaign_deal_id IN (:dealIds) AND i.parent_item_id IS NULL"
            + " AND o.status NOT IN ('CANCELLED', 'PAYMENT_FAILED', 'EXPIRED')"
            + " GROUP BY i.campaign_deal_id", nativeQuery = true)
    List<Object[]> soldUnderDeals(Collection<UUID> dealIds);
}
