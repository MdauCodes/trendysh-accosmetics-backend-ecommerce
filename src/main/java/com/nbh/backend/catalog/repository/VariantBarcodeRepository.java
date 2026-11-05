package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.VariantBarcode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VariantBarcodeRepository extends JpaRepository<VariantBarcode, UUID> {
    List<VariantBarcode> findByVariantId(UUID variantId);

    /** Every barcode of these options in one query (the merchant feed covers the whole catalogue). */
    @org.springframework.data.jpa.repository.Query("select b from VariantBarcode b join fetch b.variant where b.variant.id in :ids")
    List<VariantBarcode> findByVariantIdIn(@org.springframework.data.repository.query.Param("ids") java.util.Collection<UUID> ids);
}
