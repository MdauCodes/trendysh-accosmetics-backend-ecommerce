-- A delivery option in more than one section (owner, 6 Nov 2026: "a delivery option can be in both Nairobi/Kiambu and outside
-- Nairobi, and we can move it from one to the other"), its places each in an area, and options shown only on request.
--
-- transport_operator.kinds: the sections it is listed under at checkout, comma-separated, from METRO, RIDER and OUTSIDE.
-- transport_operator.kind stays as the first of them, so code from before this reads the same option where it was.
ALTER TABLE transport_operator ADD COLUMN kinds VARCHAR(30);
UPDATE transport_operator SET kinds = kind;
ALTER TABLE transport_operator ALTER COLUMN kinds SET NOT NULL;
ALTER TABLE transport_operator ADD CONSTRAINT transport_operator_kinds_check
    CHECK (kinds ~ '^(METRO|RIDER|OUTSIDE)(,(METRO|RIDER|OUTSIDE)){0,2}$');

-- transport_stop.area: INSIDE (Nairobi and Kiambu) or OUTSIDE. It only decides anything while its option is listed both inside
-- and outside: a customer then sees the places of the area they chose (null = both). Filled from where each option is now.
ALTER TABLE transport_stop ADD COLUMN area VARCHAR(10) CHECK (area IN ('INSIDE', 'OUTSIDE'));
UPDATE transport_stop s SET area = CASE WHEN o.kind = 'OUTSIDE' THEN 'OUTSIDE' ELSE 'INSIDE' END
FROM transport_operator o WHERE o.id = s.operator_id;

-- transport_operator.on_request: offered, but only to a customer who searches for it by name; never among the buttons, the full
-- list or the delivery page's price guide. Pickup Mtaani goes there (owner, 6 Nov 2026: the client has had many problems with
-- its deliveries, so it is shown only if a customer insists on it).
ALTER TABLE transport_operator ADD COLUMN on_request BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE transport_operator SET on_request = TRUE, active = TRUE WHERE name ILIKE 'pickup mtaani%';
