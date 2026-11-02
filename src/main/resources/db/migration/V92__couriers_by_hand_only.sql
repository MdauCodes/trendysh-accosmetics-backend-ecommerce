-- G4S, Wells Fargo and Pickup Mtaani come off the delivery lists (owner, 2 Nov 2026). A customer who wants one types its name:
-- a name typed by hand has no price, so staff agree the fee by call or WhatsApp. Staff can offer them again in Delivery options.
UPDATE transport_operator SET active = FALSE
WHERE lower(name) IN ('g4s', 'wells fargo', 'pickup mtaani', 'pickup mtaani (outside nairobi)');
