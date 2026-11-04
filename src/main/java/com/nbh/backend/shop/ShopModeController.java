package com.nbh.backend.shop;

import com.nbh.backend.common.exception.ConfigurationException;
import com.nbh.backend.payment.service.BankPaymentSettings;
import com.nbh.backend.shop.ShopModeDtos.ShopModeAdminDto;
import com.nbh.backend.shop.ShopModeDtos.ShopModeDto;
import com.nbh.backend.shop.ShopModeDtos.SetShopModeRequest;
import com.nbh.backend.shop.ShopModeDtos.SetWholesaleRequest;
import com.nbh.backend.shop.ShopModeDtos.WholesaleSwitchDto;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the shop takes orders. The read is public (the storefront asks on every visit: {@code /api/v1/catalog/**} is open);
 * changing it is for the owner and the developer only, the same two accounts that hold every permission, so the switch
 * cannot be flipped by a manager.
 */
@RestController
public class ShopModeController {

    private final ShopSettingsService settings;
    private final BankPaymentSettings bankSettings;

    public ShopModeController(ShopSettingsService settings, BankPaymentSettings bankSettings) {
        this.settings = settings;
        this.bankSettings = bankSettings;
    }

    @GetMapping("/api/v1/catalog/shop-mode")
    public ShopModeDto publicMode() {
        ShopSettings row = settings.get();
        return new ShopModeDto(row.getOrderingMode(), supportWhatsapp(), row.isWholesaleOn());
    }

    /** Every visit asks this: a payment settings row that is not seeded yet must not stop the storefront learning the mode. */
    private String supportWhatsapp() {
        try {
            return bankSettings.supportWhatsapp();
        } catch (ConfigurationException notSeeded) {
            return BankPaymentSettings.DEFAULT_SUPPORT_PHONE;
        }
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DEVELOPER')")
    @GetMapping("/api/v1/admin/shop-mode")
    public ShopModeAdminDto adminMode() {
        return ShopModeAdminDto.from(settings.get());
    }

    @PreAuthorize("hasAnyRole('SUPER_ADMIN','DEVELOPER')")
    @PutMapping("/api/v1/admin/shop-mode")
    public ShopModeAdminDto change(@Valid @RequestBody SetShopModeRequest request, @AuthenticationPrincipal StaffUser staff) {
        return ShopModeAdminDto.from(settings.setOrderingMode(request.orderingMode(), staff.getFullName()));
    }

    /** The wholesale switch (owner, 2026-11-04): whoever may change prices may turn wholesale off or on. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping("/api/v1/admin/wholesale")
    public WholesaleSwitchDto wholesale() {
        return WholesaleSwitchDto.from(settings.get());
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PutMapping("/api/v1/admin/wholesale")
    public WholesaleSwitchDto setWholesale(@Valid @RequestBody SetWholesaleRequest request,
                                           @AuthenticationPrincipal StaffUser staff) {
        return WholesaleSwitchDto.from(settings.setWholesaleOn(request.wholesaleOn(), staff.getFullName()));
    }
}
