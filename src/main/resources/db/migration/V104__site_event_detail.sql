-- Checkout step tracking: a short text with an event (the delivery kind chosen, or why placing the order failed).
ALTER TABLE site_event ADD COLUMN detail VARCHAR(24);
