package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.CatalogVariantDetailDto;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.*;
import com.nbh.backend.packages.service.PackageAvailabilityService;
import com.nbh.backend.stock.service.StockAvailabilityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * A card says when its product or option last changed (owner, 2026-11-01: SEO): the shop's sitemap gives it to search
 * engines as the page's "last modified" date, so it is the later of the two.
 */
@ExtendWith(MockitoExtension.class)
class CatalogCardUpdatedAtUnitTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private ProductImageRepository imageRepository;
    @Mock private ProductReviewRepository reviewRepository;
    @Mock private ProductSaleEventRepository saleEventRepository;
    @Mock private ProductViewEventRepository viewEventRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private StockAvailabilityService availabilityService;
    @Mock private PackageAvailabilityService packageAvailability;
    @Mock private PromoCampaignService promoCampaignService;
    @Mock private ProductOptionValueRepository optionRepository;

    private Instant cardUpdatedAt(Instant productChanged, Instant optionChanged) {
        lenient().when(promoCampaignService.getActiveOrNull()).thenReturn(null);
        Clock clock = Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), ZoneOffset.UTC);
        CatalogService service = new CatalogService(productRepository, variantRepository, imageRepository,
                reviewRepository, saleEventRepository, viewEventRepository, categoryRepository, availabilityService,
                packageAvailability, new EffectivePriceResolver(clock), promoCampaignService, optionRepository, clock);
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSlug("p");
        product.setName("Gel");
        product.setPublished(true);
        product.setUpdatedAt(productChanged);
        ProductVariant v = new ProductVariant();
        v.setId(UUID.randomUUID());
        v.setProduct(product);
        v.setSku("GEL-1");
        v.setVariantName("GEL-1");
        v.setSellingPrice(new BigDecimal("100.00"));
        v.setActive(true);
        v.setUpdatedAt(optionChanged);
        when(imageRepository.findByProductIdOrderBySortOrderAsc(product.getId())).thenReturn(List.of());
        when(reviewRepository.aggregateRatings(any(), any())).thenReturn(List.of());
        when(variantRepository.findById(v.getId())).thenReturn(Optional.of(v));
        when(variantRepository.findByProductIdAndDeletedFalseAndIsActiveTrueAndSellingPriceIsNotNullOrderBySortOrderAsc(product.getId()))
                .thenReturn(List.of(v));
        when(availabilityService.available(anyCollection())).thenReturn(Map.of(v.getId(), BigDecimal.ONE));

        CatalogVariantDetailDto detail = service.variantDetail(v.getId());
        return detail.card().updatedAt();
    }

    @Test
    void anOptionChangedAfterItsProductCarriesTheOptionsDate() {
        Instant product = Instant.parse("2026-10-01T08:00:00Z");
        Instant option = Instant.parse("2026-10-20T08:00:00Z");
        assertThat(cardUpdatedAt(product, option)).isEqualTo(option);
    }

    @Test
    void aProductChangedAfterItsOptionCarriesTheProductsDate() {
        Instant product = Instant.parse("2026-10-25T08:00:00Z");
        Instant option = Instant.parse("2026-10-20T08:00:00Z");
        assertThat(cardUpdatedAt(product, option)).isEqualTo(product);
    }

    @Test
    void laterCopesWithAMissingMoment() {
        Instant at = Instant.parse("2026-10-25T08:00:00Z");
        assertThat(CatalogService.later(null, at)).isEqualTo(at);
        assertThat(CatalogService.later(at, null)).isEqualTo(at);
        assertThat(CatalogService.later(null, null)).isNull();
    }
}
