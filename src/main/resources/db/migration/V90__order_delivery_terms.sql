-- What staff agreed with the customer about delivery on WhatsApp (owner, 2 Nov 2026): the fee goes in the existing
-- agreed_delivery_price, and this is how it gets paid. Empty until staff agree it.
--  * ON_ARRIVAL     the customer pays the rider, Metro or bus when it arrives
--  * PAY_BUSINESS   the customer pays the shop, which pays the provider: the fee is added to the order's total
--  * PROVIDER_TILL  the customer pays the provider's till directly
ALTER TABLE customer_order
    ADD COLUMN delivery_pay_way VARCHAR(20)
        CHECK (delivery_pay_way IN ('ON_ARRIVAL', 'PAY_BUSINESS', 'PROVIDER_TILL')),
    ADD COLUMN delivery_till VARCHAR(40),
    ADD COLUMN delivery_terms_agreed_at TIMESTAMP WITH TIME ZONE;

-- V38 kept a fee on the order to TumaBoda bookings, which are gone. A fee is now one the customer agreed to pay the shop.
ALTER TABLE customer_order DROP CONSTRAINT customer_order_delivery_fee_only_for_tumaboda;
ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_delivery_fee_not_negative
        CHECK (delivery_fee >= 0);
